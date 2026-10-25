package org.eninehq.tessera.fpga;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.eninehq.tessera.core.Tessera;
import org.eninehq.tessera.sim.Sim;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The board test suite against the simulated SoC (same driver code, AXI transactions in the simulator). */
class ZedSuiteSimTest {

    @ParameterizedTest
    @ValueSource(strings = {"bram5", "ooo", "C1a", "C5", "C7"})
    void boardSuiteInSimulation(String core) {
        var e = Tessera.elaborate(() -> new RvSoc(ZedBuild.core(core), ZedBuild.id(core)));
        int words = ZedBuild.memWords(core);
        try (var sim = Sim.create(e, Sim.Backend.COMPILED)) {
            var host = new SocHost(new SimBus(sim, e.top()));
            assertEquals(ZedBuild.id(core), host.id());
            var outcomes = ZedSuite.runAll(host, ZedSuite.cases(10, ZedBuild.zicsr(core), words, null), ZedBuild.zicsr(core), 100_000, words, o -> {
                if (!o.pass() || o.c().kind().equals("irq")) System.out.println((o.pass() ? "ok " : "FAIL ") + o.c().name() + ": " + o.detail());
            });
            System.out.print(core + "\n" + ZedSuite.summary(outcomes));
            assertTrue(outcomes.stream().allMatch(ZedSuite.Outcome::pass));
        }
    }

    /** Embench and CoreMark through the SoC (AXI loads, run, read-back), as on the board; needs scripts/workloads.sh. */
    @org.junit.jupiter.api.Test
    void workloadsThroughTheSoc() {
        var dir = java.nio.file.Path.of(System.getProperty("user.dir")).getParent().resolve("build/workloads");
        org.junit.jupiter.api.Assumptions.assumeTrue(java.nio.file.Files.isDirectory(dir), "build the workloads first");
        var e = Tessera.elaborate(() -> new RvSoc(ZedBuild.core("C5"), ZedBuild.id("C5")));
        try (var sim = Sim.create(e, Sim.Backend.COMPILED)) {
            var host = new SocHost(new SimBus(sim, e.top()));
            var cases = ZedSuite.cases(0, true, Designs.MEM_WORDS, dir).stream()
                    .filter(c -> c.name().equals("coremark") || c.name().equals("embench-crc32")).toList();
            assertEquals(2, cases.size());
            var outcomes = ZedSuite.runAll(host, cases, true, 100_000, Designs.MEM_WORDS, o -> System.out.println(
                    (o.pass() ? "ok " : "FAIL ") + o.c().name() + " " + o.cycles() + " cycles " + o.retired() + " retired " + o.detail()));
            assertTrue(outcomes.stream().allMatch(ZedSuite.Outcome::pass));
        }
    }
}
