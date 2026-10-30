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

/**
 * C11 ({@link OooSpecCore}) in several sizes, beside C7: riscv-tests, random programs and branch-dense random programs,
 * every committed instruction, memory write and CSR change checked against the ISS (CoreRunner, SideEffects).
 */
class OooSpecCoreTest {
    static final Map<String, Elaborated<? extends RvCore>> CORES = new LinkedHashMap<>();
    static final Map<String, long[]> STATS = new java.util.concurrent.ConcurrentHashMap<>();

    static {
        CORES.put("c11", Tessera.elaborate(() -> Cores.get("C11").build(RiscvTests.MEM_WORDS, null)));
        // stage 1: recovery alone, memory at the head
        CORES.put("c11-br", Tessera.elaborate(() -> Cores.get("C11-br").build(RiscvTests.MEM_WORDS, null)));
        // the riscv-formal windows: 4 ROB entries, 3 reservation stations, 2 checkpoints (2 loads, 2 stores)
        CORES.put("c11-small", Tessera.elaborate(() -> new OooSpecCore(RiscvTests.MEM_WORDS, null, Cores.common(true), 4, 3, 2, 2, 2)));
        CORES.put("c11-small-br", Tessera.elaborate(() -> new OooSpecCore(RiscvTests.MEM_WORDS, null, Cores.common(true), 4, 3, 2)));
        // one checkpoint, one load and one store entry: dispatch stalls often
        CORES.put("c11-1cp-1x1", Tessera.elaborate(() -> new OooSpecCore(RiscvTests.MEM_WORDS, null, Cores.common(true), 16, 8, 1, 1, 1)));
        // three checkpoints (not a power of two), 2 loads, 3 stores, LUT memories, no Zicsr
        CORES.put("c11-8x4x3-lut", Tessera.elaborate(() -> new OooSpecCore(RiscvTests.MEM_WORDS, null, RvCore.Config.DEFAULT, 8, 4, 3, 2, 3)));
        // the true dual-port data memory: the default window and the riscv-formal one
        CORES.put("c11-tdp", Tessera.elaborate(() -> Cores.get("C11-tdp").build(RiscvTests.MEM_WORDS, null)));
        CORES.put("c11-small-tdp", Tessera.elaborate(() -> new OooSpecCore(RiscvTests.MEM_WORDS, null, Cores.common(true), 4, 3, 2, 2, 2, true)));
        CORES.put("c7", Tessera.elaborate(() -> Cores.get("C7").build(RiscvTests.MEM_WORDS, null)));
    }

    static List<Arguments> riscvTests() {
        var out = new ArrayList<Arguments>();
        for (var c : CORES.keySet()) for (var t : RiscvTests.names()) out.add(Arguments.of(c, t));
        return out;
    }

    static List<Arguments> seeds() {
        var out = new ArrayList<Arguments>();
        for (var c : CORES.keySet()) for (long seed = 500; seed < 540; seed++) out.add(Arguments.of(c, seed));
        return out;
    }

    static void count(String core, String suite, CoreRunner.Result r) {
        STATS.merge(core + " " + suite, new long[]{r.cycles(), r.retired()}, (a, b) -> new long[]{a[0] + b[0], a[1] + b[1]});
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("riscvTests")
    void passesRiscvTests(String core, String test) {
        var e = CORES.get(core);
        try (var sim = CoreRunner.sim(e)) {
            var r = CoreRunner.run(e, sim, RiscvTests.image(test), 400_000);
            assertEquals("pass", RiscvTests.verdict(r.gp()), test);
            count(core, "riscv-tests", r);
        }
    }

    @ParameterizedTest(name = "{0} random {1}")
    @MethodSource("seeds")
    void matchesIssOnRandomPrograms(String core, long seed) {
        var e = CORES.get(core);
        try (var sim = CoreRunner.sim(e)) {
            var r = CoreRunner.run(e, sim, RandomProgram.generate(seed, 400, seed % 2 == 1), 200_000);
            assertTrue(r.retired() > 100);
            count(core, "random", r);
        }
    }

    @ParameterizedTest(name = "{0} branches {1}")
    @MethodSource("seeds")
    void matchesIssOnBranchPrograms(String core, long seed) {
        var e = CORES.get(core);
        try (var sim = CoreRunner.sim(e)) {
            var r = CoreRunner.run(e, sim, RandomProgram.branches(seed, 400, seed % 2 == 1), 400_000);
            assertTrue(r.retired() > 100);
            count(core, "branches", r);
        }
    }

    @ParameterizedTest(name = "{0} memory {1}")
    @MethodSource("seeds")
    void matchesIssOnMemoryPrograms(String core, long seed) {
        var e = CORES.get(core);
        try (var sim = CoreRunner.sim(e)) {
            var r = CoreRunner.run(e, sim, RandomProgram.memory(seed, 400, seed % 2 == 1), 400_000);
            assertTrue(r.retired() > 100);
            count(core, "memory", r);
        }
    }

    @ParameterizedTest(name = "{0} checkpoint reuse")
    @MethodSource("cores")
    void checkpointReuse(String core) {
        var e = CORES.get(core);
        try (var sim = CoreRunner.sim(e)) {
            var r = CoreRunner.run(e, sim, RandomProgram.checkpointReuse(), 20_000);
            assertTrue(r.retired() > 10);
            count(core, "directed", r);
        }
    }

    static List<String> cores() {
        return List.copyOf(CORES.keySet());
    }

    @Test
    void lintClean() throws Exception {
        assumeTrue(Verilator.available());
        Verilator.lint(CORES.get("c11").circuit(), "lint-c11");
        Verilator.lint(CORES.get("c11-small").circuit(), "lint-c11-small");
        Verilator.lint(CORES.get("c11-tdp").circuit(), "lint-c11-tdp");
    }

    @AfterAll
    static void report() {
        STATS.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> System.out.printf(
                "C11TEST %s: %d cycles, %d retired, CPI %.4f%n", e.getKey(), e.getValue()[0], e.getValue()[1],
                (double) e.getValue()[0] / e.getValue()[1]));
    }
}
