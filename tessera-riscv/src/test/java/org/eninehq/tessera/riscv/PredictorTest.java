package org.eninehq.tessera.riscv;

import static org.eninehq.tessera.riscv.RandomProgram.b;
import static org.eninehq.tessera.riscv.RandomProgram.i;
import static org.eninehq.tessera.riscv.RandomProgram.j;
import static org.eninehq.tessera.riscv.RandomProgram.s;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntUnaryOperator;
import org.eninehq.tessera.core.Elaborated;
import org.eninehq.tessera.core.Tessera;
import org.eninehq.tessera.sim.Simulator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.opentest4j.AssertionFailedError;

/**
 * Gshare and the return-address stack (model code over the generator's speculative-state hook) on directed
 * programs, every retirement checked against the ISS, memory and CSRs ({@link CoreRunner}):
 * <ul>
 *   <li>{@code calls}: two functions called from alternating sites, one nesting a call through x5; returns change
 *       target every time, so a BTB alone mispredicts them and a RAS does not;</li>
 *   <li>{@code pattern}: an alternating branch and a period-4 branch, which 2-bit counters miss and gshare learns;</li>
 *   <li>{@code deep}: recursion 12 deep (deeper than the 8-entry RAS), returns through a memory stack.</li>
 * </ul>
 * Negative controls: {@link ScheduledCore.TestBug#NO_REPAIR} (repair disabled; still correct, but measurably slower)
 * and {@link ScheduledCore.TestBug#RAS_TRUSTED} (returns predicted by the RAS not checked; the co-simulation must
 * fail). Cycles, retired instructions and, where the core has the hook, redirects go to {@code predictorOut}.
 */
class PredictorTest {
    static final int MEM = RiscvTests.MEM_WORDS;
    static final Map<String, String> ROWS = new TreeMap<>();
    static final Map<String, Elaborated<? extends RvCore>> BUILT = new ConcurrentHashMap<>();
    static final ScheduledCore.Predictor GSRAS = ScheduledCore.Predictor.btb(64).withGshare(8).withRas(8);

    /** A few-instruction assembler with labels, enough for the directed programs. */
    static final class Asm {
        private final List<IntUnaryOperator> code = new ArrayList<>();
        private final Map<String, Integer> labels = new HashMap<>();

        Asm label(String l) {
            labels.put(l, code.size() * 4);
            return this;
        }

        Asm w(int word) {
            code.add(pc -> word);
            return this;
        }

        Asm addi(int rd, int rs1, int imm) { return w(i(imm, rs1, 0, rd, 0x13)); }

        Asm andi(int rd, int rs1, int imm) { return w(i(imm, rs1, 7, rd, 0x13)); }

        Asm lw(int rd, int rs1, int imm) { return w(i(imm, rs1, 2, rd, 0x03)); }

        Asm sw(int rs2, int rs1, int imm) { return w(s(imm, rs2, rs1, 2)); }

        Asm jalr(int rd, int rs1, int imm) { return w(i(imm, rs1, 0, rd, 0x67)); }

        Asm jal(int rd, String l) {
            code.add(pc -> j(target(l) - pc, rd));
            return this;
        }

        Asm br(int f3, int rs1, int rs2, String l) {
            code.add(pc -> b(target(l) - pc, rs2, rs1, f3));
            return this;
        }

        Asm beq(int rs1, int rs2, String l) { return br(0, rs1, rs2, l); }

        Asm bne(int rs1, int rs2, String l) { return br(1, rs1, rs2, l); }

        private int target(String l) {
            Integer t = labels.get(l);
            if (t == null) throw new IllegalArgumentException("no label " + l);
            return t;
        }

        int[] build() {
            int[] out = new int[code.size()];
            for (int k = 0; k < out.length; k++) out[k] = code.get(k).applyAsInt(k * 4);
            return out;
        }
    }

    static int[] calls() {
        return new Asm()
                .addi(6, 0, 200)
                .label("loop")
                .jal(1, "g").addi(11, 11, 1)
                .jal(1, "f").addi(12, 12, 1)
                .jal(1, "g")
                .jal(1, "f")
                .addi(6, 6, -1).bne(6, 0, "loop")
                .w(RandomProgram.EBREAK)
                .label("g").addi(13, 13, 1).jal(5, "f2").addi(13, 13, 1).jalr(0, 1, 0)
                .label("f2").addi(10, 10, 3).jalr(0, 5, 0)
                .label("f").addi(10, 10, 1).jalr(0, 1, 0)
                .build();
    }

    static int[] pattern() {
        return new Asm()
                .addi(6, 0, 400).addi(7, 0, 0).addi(9, 0, 2)
                .label("loop")
                .andi(8, 7, 3).beq(8, 9, "skip")       // taken when i % 4 == 2
                .addi(10, 10, 1)
                .label("skip")
                .andi(8, 7, 1).bne(8, 0, "skip2")       // taken when i is odd
                .addi(11, 11, 1)
                .label("skip2")
                .addi(7, 7, 1).addi(6, 6, -1).bne(6, 0, "loop")
                .w(RandomProgram.EBREAK)
                .build();
    }

    static int[] deep() {
        return new Asm()
                .addi(2, 0, 0x7f0).addi(6, 0, 20)
                .label("outer")
                .addi(10, 0, 12).jal(1, "rec")
                .addi(6, 6, -1).bne(6, 0, "outer")
                .w(RandomProgram.EBREAK)
                .label("rec")
                .beq(10, 0, "base")
                .addi(2, 2, -4).sw(1, 2, 0).addi(10, 10, -1)
                .jal(1, "rec")
                .lw(1, 2, 0).addi(2, 2, 4)
                .label("base")
                .jalr(0, 1, 0)
                .build();
    }

    static final Map<String, int[]> PROGRAMS = new LinkedHashMap<>();

    static {
        PROGRAMS.put("calls", calls());
        PROGRAMS.put("pattern", pattern());
        PROGRAMS.put("deep", deep());
    }

    static Elaborated<? extends RvCore> core(String id) {
        return BUILT.computeIfAbsent(id, k -> switch (k) {
            case "C8-gsras/NO_REPAIR" -> Tessera.elaborate(() -> new ScheduledCore(MEM, null, ScheduledCore.FIVE_STAGE_MUL_SUM_WB,
                    Cores.common(true), GSRAS, ScheduledCore.TestBug.NO_REPAIR));
            case "C8-gsras/RAS_TRUSTED" -> Tessera.elaborate(() -> new ScheduledCore(MEM, null, ScheduledCore.FIVE_STAGE_MUL_SUM_WB,
                    Cores.common(true), GSRAS, ScheduledCore.TestBug.RAS_TRUSTED));
            default -> Tessera.elaborate(() -> Cores.get(k).build(MEM, null));
        });
    }

    /** Cycles, retired, and the hook's redirects (-1 without the hook), every retirement checked. */
    static long[] measure(String id, String program) {
        var e = core(id);
        try (var sim = CoreRunner.sim(e)) {
            var counter = new RedirectCounter(sim);
            sim.addListener(counter);
            var r = CoreRunner.run(e, sim, PROGRAMS.get(program), 400_000, true);
            long[] out = {r.cycles(), r.retired(), counter.count()};
            synchronized (ROWS) {
                ROWS.put(id + "," + program, String.format("%s,%s,%d,%d,%.4f,%d", id, program, out[0], out[1], (double) out[0] / out[1], out[2]));
            }
            return out;
        }
    }

    /** Counts cycles in which a resolve point redirects, from the repair hook's wires (named core_repair<i>_l<lane>_taken). */
    static final class RedirectCounter implements Simulator.Listener {
        private final List<String> wires = new ArrayList<>();
        private long n;

        RedirectCounter(Simulator sim) {
            for (int ri = 0; ri < 3; ri++)
                for (int l = 0; l < 2; l++) {
                    String w = "core_repair" + ri + "_l" + l + "_taken";
                    try {
                        sim.peek(w);
                        wires.add(w);
                    } catch (RuntimeException absent) {
                        // no such resolve point or lane, or a core without the hook
                    }
                }
        }

        @Override
        public void beforeEdge(Simulator sim, long cycle) {
            for (String w : wires)
                if (sim.peek(w) != 0) {
                    n++;
                    return;
                }
        }

        long count() { return wires.isEmpty() ? -1 : n; }
    }

    static final List<String> CORES = List.of("C8", "C8-gshare", "C8-ras", "C8-gsras", "C10", "C10-gsras", "C10-ras", "C8-gs12ras", "C10-gs12ras");

    @Test
    void everyPredictorRunsTheDirectedProgramsCorrectly() {
        for (String c : CORES) for (String p : PROGRAMS.keySet()) measure(c, p);
    }

    static List<org.junit.jupiter.params.provider.Arguments> pairCases() {
        var out = new ArrayList<org.junit.jupiter.params.provider.Arguments>();
        for (var c : List.of("C10-gsras", "C10-gs12ras", "C10-ras"))
            for (long seed = 0; seed < 60; seed++) out.add(org.junit.jupiter.params.provider.Arguments.of(c, seed));
        return out;
    }

    /** Two lanes: the pairing programs of {@link DualIssueTest} (slot 1 dropped, split and refetched) on the predictor cores. */
    @org.junit.jupiter.params.ParameterizedTest(name = "{0} pairs seed {1}")
    @org.junit.jupiter.params.provider.MethodSource("pairCases")
    void dualLanePredictorsMatchTheIss(String id, long seed) {
        var e = core(id);
        try (var sim = CoreRunner.sim(e)) {
            var r = CoreRunner.run(e, sim, RandomProgram.pairs(seed, 300), 200_000, true);
            assertTrue(r.retired() > 20, "too short: " + r.retired());
        }
    }

    @Test
    void theRasRemovesReturnMispredictions() {
        long base = measure("C8", "calls")[0], ras = measure("C8-ras", "calls")[0];
        long both = measure("C8-gsras", "calls")[0];
        assertTrue(ras < base * 0.9, "C8-ras " + ras + " vs C8 " + base + " cycles on calls");
        assertTrue(both < base * 0.9, "C8-gsras " + both + " vs C8 " + base);
        long base2 = measure("C10", "calls")[0], ras2 = measure("C10-gsras", "calls")[0];
        assertTrue(ras2 < base2 * 0.9, "C10-gsras " + ras2 + " vs C10 " + base2);
    }

    @Test
    void gshareLearnsHistoryPatterns() {
        long base = measure("C8", "pattern")[0], gs = measure("C8-gshare", "pattern")[0];
        assertTrue(gs < base * 0.95, "C8-gshare " + gs + " vs C8 " + base + " cycles on pattern");
        long base2 = measure("C10", "pattern")[0], gs2 = measure("C10-gsras", "pattern")[0];
        assertTrue(gs2 < base2 * 0.95, "C10-gsras " + gs2 + " vs C10 " + base2);
    }

    /** Negative control: without the repair the core is still correct (execute checks every guess) but slower. */
    @Test
    void withoutTheRepairTheCoreIsCorrectButSlower() {
        for (String p : List.of("calls", "pattern")) {
            long[] good = measure("C8-gsras", p), bad = measure("C8-gsras/NO_REPAIR", p);
            assertEquals(good[1], bad[1]);
            assertTrue(bad[2] > good[2], p + ": redirects without repair " + bad[2] + " vs " + good[2]);
            assertTrue(bad[0] > good[0], p + ": cycles without repair " + bad[0] + " vs " + good[0]);
        }
    }

    /** Negative control: a core that trusts its RAS executes wrong returns once the recursion overflows it; co-simulation catches it. */
    @Test
    void trustingTheRasIsCaught() {
        measure("C8-gsras", "deep");
        var err = assertThrows(AssertionFailedError.class, () -> measure("C8-gsras/RAS_TRUSTED", "deep"));
        System.out.println("PREDICTOR RAS_TRUSTED caught: " + err.getMessage().lines().findFirst().orElse(""));
        synchronized (ROWS) {
            ROWS.put("C8-gsras/RAS_TRUSTED,deep", "C8-gsras/RAS_TRUSTED,deep,,,,CAUGHT");
        }
    }

    @AfterAll
    static void write() throws IOException {
        ROWS.values().forEach(r -> System.out.println("PREDICTOR " + r));
        String to = System.getProperty("predictorOut", "");
        if (to.isBlank() || ROWS.isEmpty()) return;
        Path out = Path.of(to);
        Files.createDirectories(out.toAbsolutePath().getParent());
        var lines = new ArrayList<String>(List.of("core,program,cycles,retired,cpi,redirect_cycles"));
        lines.addAll(ROWS.values());
        Files.write(out, lines);
    }
}
