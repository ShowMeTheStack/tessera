package org.eninehq.tessera.fpga;

import java.nio.file.Files;
import java.nio.file.Path;
import org.eninehq.tessera.core.Tessera;
import org.eninehq.tessera.sim.Sim;

/**
 * The whole board suite of one core against the simulated SoC, with the same cases as
 * {@link ZedSuite} (same random seeds and workloads), written as {@code build/zed/sim-<core>/board-report.txt}
 * in the board report's format, so the cycle counts can be compared case by case.
 * <pre>ZedSuiteSim &lt;core&gt; [random programs]</pre>
 */
public final class ZedSuiteSim {
    private ZedSuiteSim() {}

    public static void main(String[] args) throws Exception {
        String core = args[0];
        int random = args.length > 1 ? Integer.parseInt(args[1]) : 100;
        int words = ZedBuild.memWords(core);
        Path wl = Path.of(words == Designs.MEM_WORDS ? "build/workloads" : "build/workloads-" + (4 * words / 1024) + "k");
        var cases = ZedSuite.cases(random, ZedBuild.zicsr(core), words, wl);
        var e = Tessera.elaborate(() -> new RvSoc(ZedBuild.core(core), ZedBuild.id(core)));
        long t0 = System.nanoTime();
        try (var sim = Sim.create(e, Sim.Backend.COMPILED)) {
            var host = new SocHost(new SimBus(sim, e.top()));
            var outcomes = ZedSuite.runAll(host, cases, ZedBuild.zicsr(core), 100_000, words, o -> {});
            var sb = new StringBuilder();
            for (var o : outcomes)
                sb.append(String.format("%s %s %s %d %d %s%n", o.pass() ? "ok" : "FAIL", o.c().kind(), o.c().name(), o.cycles(),
                        o.retired(), o.detail()));
            String s = ZedSuite.summary(outcomes);
            Path report = Path.of("build/zed/sim-" + core + "/board-report.txt");
            Files.createDirectories(report.getParent());
            Files.writeString(report, "simulation\n" + sb + s);
            System.out.print(core + "\n" + s);
            System.out.printf("%.0f s%n", (System.nanoTime() - t0) / 1e9);
        }
    }
}
