package org.eninehq.tessera.riscv;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.eninehq.tessera.core.Elaborated;
import org.eninehq.tessera.core.Tessera;
import org.eninehq.tessera.test.Verilator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** The out-of-order core: every committed instruction matches the ISS, on riscv-tests and random programs. */
class OooCoreTest {
    static final Map<String, Elaborated<? extends RvCore>> CORES = new LinkedHashMap<>();
    static final Map<String, long[]> STATS = new java.util.concurrent.ConcurrentHashMap<>();

    static {
        CORES.put("ooo", Tessera.elaborate(() -> new OooCore(RiscvTests.MEM_WORDS, null)));
        CORES.put("ooo-bram", Tessera.elaborate(() -> new OooCore(RiscvTests.MEM_WORDS, null, new RvCore.Config(false, true, true))));
        // the window riscv-formal checks: 4 ROB entries, 3 reservation stations
        CORES.put("ooo-small", Tessera.elaborate(() -> new OooCore(RiscvTests.MEM_WORDS, null, RvCore.Config.DEFAULT, 4, 3)));
        // the board configuration: Zicsr, block RAM, debug port
        CORES.put("ooo-fpga", Tessera.elaborate(() -> new OooCore(RiscvTests.MEM_WORDS, null, RvCore.Config.fpga())));
        CORES.put("ooo-small-zicsr", Tessera.elaborate(() -> new OooCore(RiscvTests.MEM_WORDS, null, new RvCore.Config(true, false, false), 4, 3)));
        CORES.put("5-stage", Tessera.elaborate(() -> new ScheduledCore(RiscvTests.MEM_WORDS, null, ScheduledCore.FIVE_STAGE)));
    }

    static List<Arguments> riscvTests() {
        var out = new ArrayList<Arguments>();
        for (var c : CORES.keySet()) for (var t : RiscvTests.names()) out.add(Arguments.of(c, t));
        return out;
    }

    static List<Arguments> randomPrograms() {
        var out = new ArrayList<Arguments>();
        for (var c : CORES.keySet()) for (long seed = 300; seed < 340; seed++) out.add(Arguments.of(c, seed));
        return out;
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("riscvTests")
    void passesRiscvTests(String core, String test) {
        var e = CORES.get(core);
        try (var sim = CoreRunner.sim(e)) {
            var r = CoreRunner.run(e, sim, RiscvTests.image(test), 200_000);
            assertEquals("pass", RiscvTests.verdict(r.gp()), test);
            STATS.merge(core, new long[]{r.cycles(), r.retired()}, (a, b) -> new long[]{a[0] + b[0], a[1] + b[1]});
        }
    }

    @ParameterizedTest(name = "{0} seed {1}")
    @MethodSource("randomPrograms")
    void matchesIssOnRandomPrograms(String core, long seed) {
        var e = CORES.get(core);
        try (var sim = CoreRunner.sim(e)) {
            var r = CoreRunner.run(e, sim, RandomProgram.generate(seed, 400, seed % 2 == 1), 100_000);
            assertTrue(r.retired() > 100);
        }
    }

    /** Independent work overlaps a long divide: the out-of-order core runs this much faster than in order. */
    @Test
    void overlapsIndependentWorkWithADivide() {
        int[] prog = Benchmarks.divideOverlap();
        long ooo, inorder;
        try (var sim = CoreRunner.sim(CORES.get("ooo-bram"))) {
            ooo = CoreRunner.run(CORES.get("ooo-bram"), sim, prog, 100_000).cycles();
        }
        var fpga5 = Tessera.elaborate(() -> new ScheduledCore(RiscvTests.MEM_WORDS, null, ScheduledCore.FIVE_STAGE, RvCore.Config.fpga()));
        try (var sim = CoreRunner.sim(fpga5)) {
            inorder = CoreRunner.run(fpga5, sim, prog, 100_000).cycles();
        }
        System.out.printf("divide + independent work: out-of-order %d cycles, in-order 5-stage %d cycles%n", ooo, inorder);
        assertTrue(ooo * 10 < inorder * 7, "out of order should hide most of the divide latency");
    }

    @Test
    void lintClean() throws Exception {
        assumeTrue(Verilator.available());
        Verilator.lint(CORES.get("ooo-bram").circuit(), "lint-ooo");
        Verilator.lint(CORES.get("ooo-fpga").circuit(), "lint-ooo-fpga");
    }

    @AfterAll
    static void report() {
        for (var e : STATS.entrySet())
            System.out.printf("%s: CPI %.3f over riscv-tests%n", e.getKey(), (double) e.getValue()[0] / e.getValue()[1]);
    }
}
