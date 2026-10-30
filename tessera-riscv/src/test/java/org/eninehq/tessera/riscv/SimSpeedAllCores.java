package org.eninehq.tessera.riscv;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.eninehq.tessera.core.Elaborated;
import org.eninehq.tessera.core.Tessera;
import org.eninehq.tessera.sim.BatchSim;
import org.eninehq.tessera.sim.CompiledSim;
import org.eninehq.tessera.sim.Sim;
import org.eninehq.tessera.test.Verilator;
import org.junit.jupiter.api.Test;

/**
 * Simulation speed of every core in {@link Cores} on the same million-instruction loop:
 * elaboration time, the compiled simulator's time to first cycle and steady-state rate, the
 * interpreter, the bit-sliced batch simulator (64 lanes) and Verilator (-O3, with its build
 * time). Run on an idle machine with {@code -Pbench}; writes build/results/simspeed.csv.
 *
 * <p>Warm-up: {@code compiled_hz} is a run timed after a warm-up run of the same design, and since
 * simulators of one netlist share their generated classes, the warm-up warms the code that is timed.
 * {@code compiled_hz_fresh_classes} repeats the measurement with class sharing off (every
 * {@code Sim.create} defines new classes, so the warm-up warms other classes); it is reported next to
 * the shared number, never instead of it. The interpreter is likewise timed after a warm-up run
 * ({@code interpreter_hz}), and {@code interpreter_hz_no_warmup} is a single cold run.
 */
class SimSpeedAllCores {
    static final int ITERATIONS = 200_000, SMALL = 5_000, BATCH_CYCLES = 20_000;

    @Test
    void allCores() throws Exception {
        assumeTrue(Boolean.getBoolean("bench"), "run with -Pbench");
        int[] prog = Benchmarks.loop(ITERATIONS), small = Benchmarks.loop(SMALL);
        var rows = new ArrayList<String>(List.of(
                "core,cycles,elaborate_ms,compiled_first_cycle_ms,compiled_hz,interpreter_hz,batch_gates,batch_lane_hz,verilator_build_s,verilator_hz,"
                        + "compiled_hz_fresh_classes,interpreter_hz_no_warmup"));
        // -Pcores: only those (variants included); otherwise every validation core
        var specs = System.getProperty("cores", "").isBlank() ? Cores.ALL : CoreMatrixTest.ids().stream().map(Cores::get).toList();
        for (var spec : specs) {
            long t0 = System.nanoTime();
            Elaborated<? extends RvCore> e = Tessera.elaborate(() -> spec.build(4096, prog));
            double elabMs = (System.nanoTime() - t0) / 1e6;
            // time to first cycle: create the compiled simulator (generate, load, link) and take one step
            t0 = System.nanoTime();
            try (var sim = Sim.create(e, Sim.Backend.COMPILED)) {
                sim.reset(1);
                sim.step();
            }
            double firstMs = (System.nanoTime() - t0) / 1e6;
            // the old way first: fresh classes for the warm-up and for the timed run
            CompiledSim.setShareClasses(false);
            SimSpeedBenchmark.tessera(e, Sim.Backend.COMPILED, ITERATIONS);
            var compiledFresh = SimSpeedBenchmark.tessera(e, Sim.Backend.COMPILED, ITERATIONS);
            CompiledSim.setShareClasses(true);
            SimSpeedBenchmark.tessera(e, Sim.Backend.COMPILED, ITERATIONS);                  // JIT warm-up of the shared classes
            var compiled = SimSpeedBenchmark.tessera(e, Sim.Backend.COMPILED, ITERATIONS);
            assertEquals(compiledFresh.cycles(), compiled.cycles(), "same cycle count with and without class sharing");
            var smallE = Tessera.elaborate(() -> spec.build(4096, small));
            var interpCold = SimSpeedBenchmark.tessera(smallE, Sim.Backend.INTERPRETER, SMALL);
            var interp = SimSpeedBenchmark.tessera(smallE, Sim.Backend.INTERPRETER, SMALL);
            // 64 lanes of the bit-sliced simulator, a fixed number of cycles
            var batch = new BatchSim(e.circuit());
            batch.pokeAll("reset", 1);
            batch.step();
            batch.pokeAll("reset", 0);
            batch.step(1_000);
            t0 = System.nanoTime();
            batch.step(BATCH_CYCLES);
            double batchS = (System.nanoTime() - t0) / 1e9;
            double laneHz = (double) BatchSim.LANES * BATCH_CYCLES / batchS;
            String vb = "", vhz = "";
            if (Verilator.available()) {
                t0 = System.nanoTime();
                var v = SimSpeedBenchmark.verilator(e, "all-" + spec.id().toLowerCase());
                double total = (System.nanoTime() - t0) / 1e9;
                assertEquals(compiled.cycles(), v.cycles(), "same cycle count in Verilator");
                vb = String.format("%.1f", total - v.seconds());
                vhz = String.format("%.0f", v.hz());
            }
            rows.add(String.format("%s,%d,%.0f,%.0f,%.0f,%.0f,%d,%.0f,%s,%s,%.0f,%.0f", spec.id(), compiled.cycles(), elabMs, firstMs,
                    compiled.hz(), interp.hz(), batch.gateCount(), laneHz, vb, vhz, compiledFresh.hz(), interpCold.hz()));
            System.out.println("SPEED " + rows.getLast());
        }
        // -PsimspeedOut=<file> writes elsewhere than build/results
        String to = System.getProperty("simspeedOut", "");
        Path out = !to.isBlank() ? Path.of(to) : Path.of(System.getProperty("resultsDir", Path.of(System.getProperty("user.dir")).getParent().resolve("build/results").toString())).resolve("simspeed.csv");
        try (var w = new PrintWriter(Files.newBufferedWriter(out))) {
            rows.forEach(w::println);
        }
    }
}
