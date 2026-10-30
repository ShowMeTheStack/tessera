package org.eninehq.tessera.riscv;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.eninehq.tessera.core.Elaborated;
import org.eninehq.tessera.core.Tessera;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Symmetric lanes (C9s, C10s and their replay versions): loads and stores in both lanes, on the two ports of
 * a true dual-port data memory. Memory-pair programs ({@link RandomProgram#memPairs}) and the dual-issue pairing
 * programs, each retired instruction, every memory write and the CSRs checked against the ISS; and counts showing
 * the cases are reached (lane-1 loads and stores, port waits, same-word replays, split pairs).
 */
class SymmetricLaneTest {
    static final Map<String, Elaborated<? extends RvCore>> BUILT = new ConcurrentHashMap<>();
    static final List<String> CORES = List.of("C9s", "C10s", "C9s-rep", "C10s-rep");

    static Elaborated<? extends RvCore> core(String id) {
        return BUILT.computeIfAbsent(id, k -> Tessera.elaborate(() -> Cores.get(k).build(RiscvTests.MEM_WORDS, null)));
    }

    static List<Arguments> cases() {
        var out = new ArrayList<Arguments>();
        for (var c : CORES) for (long seed = 0; seed < 60; seed++) {
            out.add(Arguments.of(c, "mem", seed));
            out.add(Arguments.of(c, "pairs", seed));
        }
        // the memory-pair programs on the cores with lane-0-only memory, for comparison
        for (var c : List.of("C9", "C5")) for (long seed = 0; seed < 60; seed++) out.add(Arguments.of(c, "mem", seed));
        return out;
    }

    @ParameterizedTest(name = "{0} {1} seed {2}")
    @MethodSource("cases")
    void matchesIss(String id, String kind, long seed) {
        var e = core(id);
        int[] img = kind.equals("mem") ? RandomProgram.memPairs(seed, 300) : RandomProgram.pairs(seed, 300);
        try (var sim = CoreRunner.sim(e)) {
            var r = CoreRunner.run(e, sim, img, 200_000, true);
            assertTrue(r.retired() > 20, "too short: " + r.retired());
        }
    }

    /** Counts over the 60 memory-pair programs: lane 1 really loads and stores, and every special case happens. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("coreIds")
    void memoryPairsReachTheCases(String id) {
        var e = core(id);
        RvCore c = e.top();
        long lane1Loads = 0, lane1Stores = 0, busy = 0, sameWord = 0, retired = 0, cycles = 0, splits = 0, trapEnds = 0;
        for (long seed = 0; seed < 60; seed++) {
            int[] img = RandomProgram.memPairs(seed, 300);
            try (var sim = CoreRunner.sim(e)) {
                long[] imem = sim.memory(c.imem), dmem = sim.memory(c.dmem);
                java.util.Arrays.fill(imem, 0);
                java.util.Arrays.fill(dmem, 0);
                for (int i = 0; i < img.length; i++) imem[i] = dmem[i] = Integer.toUnsignedLong(img[i]);
                sim.reset(1);
                var ports = c.retirePorts();
                for (int k = 0; k < 20_000 && sim.peek(c.halted) == 0; k++) {
                    if (sim.peek("dmem0_port_busy") == 1 || sim.peek("dmem1_port_busy") == 1) busy++;
                    if (id.endsWith("-rep") && sim.peek("lane1_same_word") == 1 && sim.peek("lane1_fire") == 1) sameWord++;
                    if (sim.peek("core_split") == 1) splits++;
                    for (var p : ports) if (sim.peek(p.valid()) == 1) retired++;
                    if (sim.peek(ports.get(1).valid()) == 1) {
                        long op = sim.peek(ports.get(1).insn()) & 0x7F;
                        if (op == 0x03) lane1Loads++;
                        if (op == 0x23) lane1Stores++;
                    }
                    if (sim.peek(c.rvfiValid) == 1 && sim.peek(c.rvfiTrap) == 1) trapEnds++;
                    cycles++;
                    sim.step();
                }
            }
        }
        System.out.printf("SYMLANE %s memPairs x60: %d cycles, %d retired, CPI %.3f; lane 1 retired %d loads, %d stores;"
                + " %d port-wait cycles, %d same-word replays, %d split cycles, %d traps%n",
                id, cycles, retired, (double) cycles / retired, lane1Loads, lane1Stores, busy, sameWord, splits, trapEnds);
        assertTrue(lane1Loads > 200, "lane-1 loads " + lane1Loads);
        assertTrue(lane1Stores > 200, "lane-1 stores " + lane1Stores);
        assertTrue(busy > 100, "port waits " + busy);
        if (id.endsWith("-rep")) assertTrue(sameWord > 20, "same-word replays " + sameWord);
        assertTrue(trapEnds >= 3, "misaligned-access traps " + trapEnds);
    }

    static List<String> coreIds() {
        return CORES;
    }

    @Test
    void reportsThePairRule() {
        String report = ((ScheduledCore) core("C9s").top()).dualPipeline.report();
        assertTrue(report.contains("also when lane 0 has memStore and lane 1 has memAccess"), report);
        String rep = ((ScheduledCore) core("C9s-rep").top()).dualPipeline.report();
        assertTrue(!rep.contains("memStore"), rep);
    }

    /**
     * A load in lane 1 beside a divide in lane 0, one pair after a store to the same word: the load reads the word
     * while the store writes it (the old word, read-first), and then waits in "memory" while the divider holds it.
     * The bypass entry must survive those cycles (mutant sym-bypasshold, which the matrix does not catch).
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("coreIds")
    void loadBesideDivideKeepsTheBypass(String id) {
        var p = new ArrayList<Integer>();
        p.add(RandomProgram.u(RandomProgram.DATA_BASE >>> 12, 31, 0x37));     // 0: lui x31, DATA_BASE
        p.add(RandomProgram.i(0, 0, 0, 0, 0x13));                            // 4: nop
        p.add(RandomProgram.i(77, 0, 0, 1, 0x13));                           // 8: addi x1, x0, 77
        p.add(RandomProgram.i(5, 0, 0, 2, 0x13));                            // c: addi x2, x0, 5
        p.add(RandomProgram.i(0, 0, 0, 0, 0x13));                            // 10: nop
        p.add(RandomProgram.i(0, 0, 0, 0, 0x13));                            // 14: nop
        p.add(RandomProgram.s(0, 1, 31, 2));                                 // 18: sw x1, 0(x31)   (lane 0)
        p.add(RandomProgram.i(7, 0, 0, 3, 0x13));                            // 1c: addi x3, x0, 7  (lane 1)
        p.add(RandomProgram.r(1, 2, 1, 4, 4, 0x33));                         // 20: div x4, x1, x2  (lane 0)
        p.add(RandomProgram.i(0, 31, 2, 6, 0x03));                           // 24: lw x6, 0(x31)   (lane 1)
        p.add(RandomProgram.r(0, 6, 4, 0, 7, 0x33));                         // 28: add x7, x4, x6
        p.add(RandomProgram.EBREAK);
        var e = core(id);
        RvCore c = e.top();
        try (var sim = CoreRunner.sim(e)) {
            long[] imem = sim.memory(c.imem), dmem = sim.memory(c.dmem);
            java.util.Arrays.fill(imem, 0);
            java.util.Arrays.fill(dmem, 0);
            for (int i = 0; i < p.size(); i++) imem[i] = dmem[i] = Integer.toUnsignedLong(p.get(i));
            sim.reset(1);
            long lwInLane1 = 0;
            for (int k = 0; k < 200 && sim.peek(c.halted) == 0; k++) {
                if (sim.peek(c.rvfi1.valid()) == 1 && sim.peek(c.rvfi1.pcRdata()) == 0x24) lwInLane1++;
                sim.step();
            }
            assertEquals(1, lwInLane1, "the lw retires in lane 1, beside the div");
        }
        try (var sim = CoreRunner.sim(e)) {
            var r = CoreRunner.run(e, sim, p.stream().mapToInt(Integer::intValue).toArray(), 1000, true);
            assertEquals(12, r.retired());
        }
    }

    /** A load and a store in lane 1, beside independent ALU instructions in lane 0: two per cycle. */
    @Test
    void loadsAndStoresPairWithAluInstructions() {
        var p = new ArrayList<Integer>();
        p.add(RandomProgram.u(RandomProgram.DATA_BASE >>> 12, 31, 0x37));     // lui x31, DATA_BASE
        p.add(RandomProgram.i(0, 0, 0, 0, 0x13));                            // nop: the pairs start at 8-byte boundaries
        for (int k = 0; k < 100; k++) {
            p.add(RandomProgram.i(k, 0, 0, 1 + (k % 4), 0x13));                       // addi x(1..4), x0, k
            p.add(k % 2 == 0 ? RandomProgram.i(4 * (k % 16), 31, 2, 5 + (k % 3), 0x03) // lw x(5..7), ..(x31)
                    : RandomProgram.s(4 * (k % 16), 5 + ((k + 1) % 3), 31, 2));       // sw x(5..7), ..(x31)
        }
        p.add(RandomProgram.EBREAK);
        var e = core("C9s");
        try (var sim = CoreRunner.sim(e)) {
            var r = CoreRunner.run(e, sim, p.stream().mapToInt(Integer::intValue).toArray(), 10_000, true);
            assertEquals(203, r.retired());
            System.out.println("SYMLANE alu+mem pairs: CPI " + r.cpi());
            // each lw is in the pair after a sw in the same lane: it waits a cycle for the port (4 instructions in 3 cycles)
            assertTrue(r.cpi() < 0.8, "CPI " + r.cpi());
        }
        var e9 = core("C9s");
        assertTrue(e9.top().dmem.def().ports().size() == 2, "the data memory has two ports");
    }
}
