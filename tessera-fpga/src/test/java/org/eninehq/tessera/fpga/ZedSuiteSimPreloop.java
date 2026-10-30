package org.eninehq.tessera.fpga;

import java.nio.file.Files;
import java.nio.file.Path;
import org.eninehq.tessera.core.Tessera;
import org.eninehq.tessera.sim.Sim;

/**
 * {@link ZedSuiteSim} with the board's pre-suite step reproduced. On the board, {@link ZedSuite#main} runs
 * an endless loop ({@code jal x0, 0}) for about 2 s to measure the clock before the first case; the simulated suite
 * does not. For a core whose predictor state carries across programs (gshare history and counters, RAS), that loop
 * can change later cases' cycle counts. This runs the same loop for {@code preloop} cycles first, then the suite,
 * and writes {@code build/zed/simpre-<core>-<preloop>/board-report.txt}.
 * <pre>ZedSuiteSimPreloop &lt;core&gt; &lt;random programs&gt; &lt;preloop cycles&gt;</pre>
 */
public final class ZedSuiteSimPreloop {
    private ZedSuiteSimPreloop() {}

    public static void main(String[] args) throws Exception {
        String core = args[0];
        int random = Integer.parseInt(args[1]);
        long preloop = Long.parseLong(args[2]);
        int words = ZedBuild.memWords(core);
        Path wl = Path.of(words == Designs.MEM_WORDS ? "build/workloads" : "build/workloads-" + (4 * words / 1024) + "k");
        var cases = ZedSuite.cases(random, ZedBuild.zicsr(core), words, wl);
        var e = Tessera.elaborate(() -> new RvSoc(ZedBuild.core(core), ZedBuild.id(core)));
        long t0 = System.nanoTime();
        try (var sim = Sim.create(e, Sim.Backend.COMPILED)) {
            var bus = new SimBus(sim, e.top());
            var host = new SocHost(bus);
            // as ZedSuite.main before the cases: clear the registers, run "jal x0, 0", stop it
            host.clearRegisters();
            host.load(new int[]{0x0000006F});
            bus.write(SocHost.CTRL, 0);
            bus.write(SocHost.CTRL, 1);
            sim.step((int) preloop);
            bus.write(SocHost.CTRL, 0);
            // irqDelay > 0: the interrupt case raises irq that many cycles after starting the program (on the board,
            // xsct's latency between the two CTRL writes lets the loop run thousands of times; here it is a few cycles)
            long irqDelay = args.length > 3 ? Long.parseLong(args[3]) : 0;
            var outcomes = new java.util.ArrayList<ZedSuite.Outcome>();
            for (var c : cases) {
                if (c.kind().equals("irq") && irqDelay > 0) {
                    host.clearRegisters();
                    host.load(c.image());
                    bus.write(SocHost.CTRL, 0);
                    bus.write(SocHost.CTRL, 1);
                    sim.step((int) irqDelay);
                    bus.write(SocHost.CTRL, 3);
                    boolean halted = bus.waitFor(SocHost.STATUS, 1, 100_000);
                    long cycles = bus.read(SocHost.CYCLES), retired = bus.read(SocHost.RETIRED);
                    bus.write(SocHost.CTRL, 0);
                    int[] x = host.regs();
                    outcomes.add(new ZedSuite.Outcome(c, halted && x[18] == 1, "loop ran " + x[9] + " times before the interrupt",
                            cycles, retired));
                } else {
                    outcomes.add(ZedSuite.run(host, c, ZedBuild.zicsr(core), c.kind().equals("workload") ? 100_000 * 50 : 100_000, words));
                }
            }
            var sb = new StringBuilder();
            for (var o : outcomes)
                sb.append(String.format("%s %s %s %d %d %s%n", o.pass() ? "ok" : "FAIL", o.c().kind(), o.c().name(), o.cycles(),
                        o.retired(), o.detail()));
            String s = ZedSuite.summary(outcomes);
            Path report = Path.of("build/zed/simpre-" + core + "-" + preloop + "-irq" + irqDelay + "/board-report.txt");
            Files.createDirectories(report.getParent());
            Files.writeString(report, "simulation with a " + preloop + "-cycle jal loop first\n" + sb + s);
            System.out.print(core + " preloop " + preloop + "\n" + s);
            System.out.printf("%.0f s%n", (System.nanoTime() - t0) / 1e9);
        }
    }
}
