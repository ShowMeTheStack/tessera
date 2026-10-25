package org.eninehq.tessera.riscv;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import jdk.jfr.Recording;
import org.eninehq.tessera.core.Elaborated;
import org.eninehq.tessera.core.Tessera;
import org.eninehq.tessera.sim.HotSwap;
import org.eninehq.tessera.sim.JfrRecorder;
import org.eninehq.tessera.sim.Sim;
import org.eninehq.tessera.sim.Simulator;
import org.eninehq.tessera.sim.TimeTravel;
import org.junit.jupiter.api.Test;

/**
 * What the debugging features cost on the five-stage core (C5) running the same loop:
 * time-travel snapshots at several intervals (and the latency of jumping back), JFR events with
 * and without a recording, VCD and FST tracing, and hot swap. Run on an idle machine with
 * {@code -Pbench}; writes build/results/debug-overhead.csv.
 */
class DebugOverheadBench {
    static final int ITERATIONS = 100_000;

    interface Run { void run(Simulator sim) throws Exception; }

    static double seconds(Elaborated<? extends RvCore> e, Run setup) throws Exception {
        try (var sim = Sim.create(e, Sim.Backend.COMPILED)) {
            setup.run(sim);
            sim.reset(1);
            long t0 = System.nanoTime();
            while (sim.peek(e.top().halted) == 0) sim.step();
            return (System.nanoTime() - t0) / 1e9;
        }
    }

    @Test
    void overhead() throws Exception {
        assumeTrue(Boolean.getBoolean("bench"), "run with -Pbench");
        int[] prog = Benchmarks.loop(ITERATIONS);
        var e = Tessera.elaborate(() -> Cores.get("C5").build(4096, prog));
        var rows = new ArrayList<String>(List.of("feature,setting,seconds,slowdown,extra"));
        seconds(e, s -> {});                                   // warm-up
        double base = Math.min(seconds(e, s -> {}), seconds(e, s -> {}));
        rows.add(String.format("none,,%.3f,1.00,", base));
        // time travel: snapshot every N cycles while running; then jump back to random cycles
        for (int interval : new int[]{64, 256, 1024, 4096}) {
            try (var sim = Sim.create(e, Sim.Backend.COMPILED)) {
                sim.reset(1);
                var tt = new TimeTravel(sim, interval);
                long t0 = System.nanoTime();
                while (sim.peek(e.top().halted) == 0) tt.step(1);
                double s = (System.nanoTime() - t0) / 1e9;
                var rnd = new Random(1);
                long head = tt.head();
                t0 = System.nanoTime();
                for (int k = 0; k < 50; k++) tt.goTo(rnd.nextLong(head));
                double jumpMs = (System.nanoTime() - t0) / 1e6 / 50;
                rows.add(String.format("time travel,every %d cycles,%.3f,%.2f,%d snapshots; %.2f ms per jump", interval, s, s / base,
                        tt.snapshotCount(), jumpMs));
            }
        }
        // JFR: an event per cycle with the pc; free when no recording is active
        double jfrOff = seconds(e, s -> JfrRecorder.attach(s, List.of("rvfi_pc_rdata")));
        rows.add(String.format("JFR events,no recording,%.3f,%.2f,", jfrOff, jfrOff / base));
        try (var rec = new Recording()) {
            rec.enable("tessera.Cycle");
            rec.start();
            double jfrOn = seconds(e, s -> JfrRecorder.attach(s, List.of("rvfi_pc_rdata")));
            rec.stop();
            rows.add(String.format("JFR events,recording,%.3f,%.2f,", jfrOn, jfrOn / base));
        }
        // waveforms
        for (String ext : new String[]{"vcd", "fst"}) {
            Path f = Files.createTempFile("trace", "." + ext);
            double s = seconds(e, sim -> sim.trace(f));
            rows.add(String.format("waveform,%s,%.3f,%.2f,%d bytes", ext.toUpperCase(), s, s / base, Files.size(f)));
        }
        // hot swap: re-elaborate the design and carry the state of a running simulation over
        try (var sim = Sim.create(e, Sim.Backend.COMPILED)) {
            sim.reset(1);
            sim.step(10_000);
            long t0 = System.nanoTime();
            var edited = Tessera.elaborate(() -> Cores.get("C5").build(4096, prog));
            double elab = (System.nanoTime() - t0) / 1e6;
            t0 = System.nanoTime();
            var swapped = HotSwap.swap(sim, edited.circuit(), Sim.Backend.COMPILED);
            double swapMs = (System.nanoTime() - t0) / 1e6;
            swapped.sim().close();
            rows.add(String.format("hot swap,C5 at cycle 10000,%.3f,,elaborate %.0f ms; swap %.0f ms; %d registers and %d memories carried",
                    (elab + swapMs) / 1e3, elab, swapMs, swapped.report().registers(), swapped.report().memories()));
        }
        rows.forEach(r -> System.out.println("DEBUG " + r));
        Path out = Path.of(System.getProperty("resultsDir", Path.of(System.getProperty("user.dir")).getParent().resolve("build/results").toString())).resolve("debug-overhead.csv");
        try (var w = new PrintWriter(Files.newBufferedWriter(out))) {
            rows.forEach(w::println);
        }
    }
}
