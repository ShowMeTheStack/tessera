package org.eninehq.tessera.riscv;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.eninehq.tessera.core.Elaborated;
import org.eninehq.tessera.core.Tessera;
import org.eninehq.tessera.pipeline.Schedule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Dual issue (C9, C10): programs built to hit the pair check ({@link RandomProgram#pairs}), each retired
 * instruction checked against the ISS in machine mode, on both dual-issue cores and, for comparison, on C5.
 */
class DualIssueTest {
    static final Map<String, Elaborated<? extends RvCore>> BUILT = new ConcurrentHashMap<>();

    static Elaborated<? extends RvCore> core(String id) {
        return BUILT.computeIfAbsent(id, k -> Tessera.elaborate(() -> Cores.get(k).build(RiscvTests.MEM_WORDS, null)));
    }

    static List<Arguments> cases() {
        var out = new ArrayList<Arguments>();
        for (var c : List.of("C9", "C10", "C5")) for (long seed = 0; seed < 60; seed++) out.add(Arguments.of(c, seed));
        return out;
    }

    @ParameterizedTest(name = "{0} pairs seed {1}")
    @MethodSource("cases")
    void matchesIssOnPairingPrograms(String id, long seed) {
        var e = core(id);
        try (var sim = CoreRunner.sim(e)) {
            var r = CoreRunner.run(e, sim, RandomProgram.pairs(seed, 300), 200_000, true);
            assertTrue(r.retired() > 20, "too short: " + r.retired());
        }
    }

    /** Pairs really issue: on straight-line independent ALU code C9 retires two instructions in most cycles. */
    @Test
    void independentPairsIssueTogether() {
        var p = new ArrayList<Integer>();
        for (int k = 0; k < 200; k++) p.add(RandomProgram.i(k, 0, 0, 1 + (k % 8), 0x13));   // addi x(1+k%8), x0, k
        p.add(RandomProgram.EBREAK);
        var e = core("C9");
        try (var sim = CoreRunner.sim(e)) {
            var r = CoreRunner.run(e, sim, p.stream().mapToInt(Integer::intValue).toArray(), 10_000, true);
            assertEquals(201, r.retired());
            assertTrue(r.cpi() < 0.6, "CPI " + r.cpi());
        }
    }

    /**
     * A misaligned JALR in lane 1, beside an independent ADDI in lane 0 (the pair starts at an 8-byte boundary):
     * lane 1 replays it, it comes back in lane 0, and the trap goes to mtvec (the EBREAK) as on the ISS.
     */
    @Test
    void laneOneTrapIsReplayedInLaneZero() {
        var p = new ArrayList<Integer>();
        p.add(RandomProgram.u(0, 30, 0x37));                                   // 0: lui x30, 0
        p.add(RandomProgram.i(0x40, 30, 0, 30, 0x13));                        // 4: addi x30, x30, 0x40
        p.add(RandomProgram.i(0x305, 30, 1, 0, 0x73));                        // 8: csrw mtvec, x30
        p.add(RandomProgram.i(0, 0, 0, 0, 0x13));                             // c: nop
        p.add(RandomProgram.i(5, 0, 0, 1, 0x13));                             // 10: addi x1, x0, 5  (lane 0)
        p.add(RandomProgram.i(0x22, 0, 0, 2, 0x67));                          // 14: jalr x2, 0x22(x0): misaligned (lane 1)
        while (p.size() < 16) p.add(RandomProgram.i(1, 3, 0, 3, 0x13));       // addi x3, x3, 1: not reached
        p.add(RandomProgram.EBREAK);                                          // 40: mtvec
        var e = core("C9");
        try (var sim = CoreRunner.sim(e)) {
            int[] img = p.stream().mapToInt(Integer::intValue).toArray();
            var r = CoreRunner.run(e, sim, img, 1000, true);
            assertEquals(7, r.retired());   // lui, addi, csrw, nop, addi, the trapping jalr, ebreak
        }
    }

    /** The pairing programs put jumps and branches in lane 1, and some end through a misaligned jump. */
    @Test
    void pairingProgramsReachTheCases() {
        var e = core("C9");
        RvCore c = e.top();
        long laneOneJumps = 0, trapEnds = 0, replays = 0;
        for (long seed = 0; seed < 60; seed++) {
            int[] img = RandomProgram.pairs(seed, 300);
            for (int w : img) if ((w & 0x7F) == 0x67 && ((w >>> 20) & 3) == 2) { trapEnds++; break; }
            try (var sim = CoreRunner.sim(e)) {
                long[] imem = sim.memory(c.imem), dmem = sim.memory(c.dmem);
                java.util.Arrays.fill(imem, 0);
                java.util.Arrays.fill(dmem, 0);
                for (int i = 0; i < img.length; i++) imem[i] = dmem[i] = Integer.toUnsignedLong(img[i]);
                sim.reset(1);
                var ch1 = c.retirePorts().get(1);
                for (int k = 0; k < 20_000 && sim.peek(c.halted) == 0; k++) {
                    if (sim.peek("lane1_replay") == 1 && sim.peek("lane1_fire") == 1) replays++;
                    if (sim.peek(ch1.valid()) == 1) {
                        long op = sim.peek(ch1.insn()) & 0x7F;
                        if (op == 0x63 || op == 0x6F || op == 0x67) laneOneJumps++;
                    }
                    sim.step();
                }
            }
        }
        System.out.println("DUAL pairing programs: " + laneOneJumps + " jumps/branches retired in lane 1, " + trapEnds
                + " programs end with a misaligned jump, " + replays + " of them replayed from lane 1");
        assertTrue(laneOneJumps > 100, "lane-1 jumps " + laneOneJumps);
        assertTrue(trapEnds >= 3, "trap endings " + trapEnds);
        assertTrue(replays >= 1, "no misaligned jump reached lane 1");
    }

    @Test
    void reportsTheIssueRules() {
        var e = core("C9");
        String report = ((ScheduledCore) e.top()).dualPipeline.report();
        assertTrue(report.contains("issue: two lanes"), report);
        assertTrue(report.contains("pairing in ID"), report);
    }

    @Test
    void dualIssueNeedsTheReadStageSecond() {
        var bad = Schedule.stages("IF", "ID", "RF", "EX", "MEM", "WB")
                .place("IF", "fetch").place("ID", "decode").place("RF", org.eninehq.tessera.pipeline.InOrderModel.READ_REGS)
                .place("EX", "execute").place("MEM", "memory").place("WB", org.eninehq.tessera.pipeline.InOrderModel.WRITE_REGS, "retire")
                .withLanes(2);
        var ex = org.junit.jupiter.api.Assertions.assertThrows(org.eninehq.tessera.core.TesseraException.class,
                () -> Tessera.elaborate(() -> new ScheduledCore(64, null, bad, Cores.common(true))));
        assertTrue(ex.getMessage().contains("register reads must be in the second stage"), ex.getMessage());
    }
}
