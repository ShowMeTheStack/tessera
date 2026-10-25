package org.eninehq.tessera.riscv;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.eninehq.tessera.core.Elaborated;
import org.eninehq.tessera.core.Tessera;
import org.eninehq.tessera.test.Verilator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The same treatment for every core in {@link Cores}: riscv-tests, riscv-arch-test, the Zicsr
 * programs, 100 random programs (half RV32IM) and a Verilator lint, each instruction checked
 * against the ISS as it retires. Prints CPI per core and suite.
 */
class CoreMatrixTest {
    static final Map<String, Elaborated<? extends RvCore>> BUILT = new ConcurrentHashMap<>();
    static final Map<String, long[]> STATS = new ConcurrentHashMap<>();

    static Elaborated<? extends RvCore> core(String id, int memWords) {
        return BUILT.computeIfAbsent(id + "/" + memWords, k -> Tessera.elaborate(() -> Cores.get(id).build(memWords, null)));
    }

    /** All cores, or those named in the {@code cores} system property (comma separated; variants only when named). */
    static List<String> ids() {
        var only = List.of(System.getProperty("cores", "").split(",")).stream().filter(s -> !s.isBlank()).toList();
        if (only.isEmpty()) return Cores.ALL.stream().map(Cores.Spec::id).toList();
        return only.stream().map(id -> Cores.get(id).id()).toList();
    }

    static List<Arguments> riscvTests() {
        var out = new ArrayList<Arguments>();
        for (var c : ids()) for (var t : RiscvTests.names()) out.add(Arguments.of(c, t));
        return out;
    }

    static List<Arguments> archTests() {
        var out = new ArrayList<Arguments>();
        for (var c : ids()) for (var t : ArchTest.tests()) out.add(Arguments.of(c, t));
        return out;
    }

    static List<Arguments> randomPrograms() {
        var out = new ArrayList<Arguments>();
        for (var c : ids()) for (long seed = 1000; seed < 1100; seed++) out.add(Arguments.of(c, seed));
        return out;
    }

    static void count(String id, String suite, CoreRunner.Result r) {
        STATS.merge(id + " " + suite, new long[]{r.cycles(), r.retired()}, (a, b) -> new long[]{a[0] + b[0], a[1] + b[1]});
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("riscvTests")
    void riscvTests(String id, String test) {
        var e = core(id, RiscvTests.MEM_WORDS);
        try (var sim = CoreRunner.sim(e)) {
            var r = CoreRunner.run(e, sim, RiscvTests.image(test), 400_000);
            assertEquals("pass", RiscvTests.verdict(r.gp()), test);
            count(id, "riscv-tests", r);
        }
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("archTests")
    void archTests(String id, String name) {
        var t = ArchTest.load(name);
        var e = core(id, t.memWords());
        try (var sim = CoreRunner.sim(e)) {
            var r = CoreRunner.run(e, sim, t.image(), 40_000_000);
            long[] dmem = sim.memory(e.top().dmem);
            int[] words = new int[dmem.length];
            for (int i = 0; i < words.length; i++) words[i] = (int) dmem[i];
            assertEquals(t.reference(), ArchTest.signature(words, t.begin(), t.end()), id + " " + name);
            count(id, "arch-test", r);
        }
    }

    @ParameterizedTest(name = "{0} seed {1}")
    @MethodSource("randomPrograms")
    void randomPrograms(String id, long seed) {
        var e = core(id, RiscvTests.MEM_WORDS);
        try (var sim = CoreRunner.sim(e)) {
            var r = CoreRunner.run(e, sim, RandomProgram.generate(seed, 400, seed % 2 == 1), 200_000);
            assertTrue(r.retired() > 100);
            count(id, "random", r);
        }
    }

    @ParameterizedTest(name = "{0} zicsr")
    @MethodSource("ids")
    void zicsrPrograms(String id) {
        ZicsrTest.checkCsr(ZicsrTest.cosim(() -> Cores.get(id).build(ZicsrTest.MEM, null), "csr", Long.MAX_VALUE));
        ZicsrTest.checkTraps(ZicsrTest.cosim(() -> Cores.get(id).build(ZicsrTest.MEM, null), "traps", Long.MAX_VALUE));
        ZicsrTest.checkIrq(() -> Cores.get(id).build(ZicsrTest.MEM, null));
    }

    @ParameterizedTest(name = "{0} lint")
    @MethodSource("ids")
    void lintClean(String id) throws Exception {
        assumeTrue(Verilator.available());
        Verilator.lint(core(id, RiscvTests.MEM_WORDS).circuit(), "lint-" + id.toLowerCase());
    }

    @AfterAll
    static void report() throws java.io.IOException {
        STATS.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> System.out.printf(
                "MATRIX %s: %d cycles, %d retired, CPI %.3f%n", e.getKey(), e.getValue()[0], e.getValue()[1],
                (double) e.getValue()[0] / e.getValue()[1]));
        // the full matrix only (a filtered run would overwrite it with fewer cores)
        if (!System.getProperty("cores", "").isBlank() || STATS.isEmpty()) return;
        var out = java.nio.file.Path.of(System.getProperty("resultsDir", java.nio.file.Path.of(System.getProperty("user.dir")).getParent().resolve("build/results").toString())).resolve("matrix.csv");
        var lines = new java.util.ArrayList<String>(List.of("core,suite,cycles,retired,cpi"));
        STATS.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> {
            String[] k = e.getKey().split(" ", 2);
            lines.add(String.format("%s,%s,%d,%d,%.4f", k[0], k[1], e.getValue()[0], e.getValue()[1], (double) e.getValue()[0] / e.getValue()[1]));
        });
        java.nio.file.Files.write(out, lines);
    }
}
