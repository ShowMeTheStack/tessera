package org.eninehq.tessera.riscv;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import org.eninehq.tessera.core.Elaborated;
import org.eninehq.tessera.core.Tessera;
import org.eninehq.tessera.emit.SvEmitter;
import org.eninehq.tessera.sim.Sim;
import org.eninehq.tessera.test.Verilator;
import org.junit.jupiter.api.Test;

/**
 * Simulation speed on real cores: the interpreter, the bytecode-compiled simulator and Verilator
 * (-O3) run the same long loop to the halt. Run with {@code -Pbench}; prints cycles per second.
 */
class SimSpeedBenchmark {
    static final int ITERATIONS = 200_000;

    record Rate(long cycles, double seconds) {
        double hz() { return cycles / seconds; }
    }

    static Rate tessera(Elaborated<? extends RvCore> e, Sim.Backend backend, int iterations) {
        try (var sim = Sim.create(e, backend)) {
            RvCore c = e.top();
            sim.reset(1);
            long start = sim.cycle(), t0 = System.nanoTime();
            while (sim.peek(c.halted) == 0) sim.step();
            double s = (System.nanoTime() - t0) / 1e9;
            long[] regs = sim.memory(c.regs);
            assertEquals(0, regs[6], "loop counter at the end");
            return new Rate(sim.cycle() - start, s);
        }
    }

    static Rate verilator(Elaborated<? extends RvCore> e, String name) throws Exception {
        var dir = Verilator.workDir("speed-" + name);
        String top = SvEmitter.write(e.circuit(), dir.resolve("core.sv"));
        Files.writeString(dir.resolve("main.cpp"), """
                #include "V%1$s.h"
                #include "verilated.h"
                #include <chrono>
                #include <cstdio>
                int main(int argc, char** argv) {
                    Verilated::commandArgs(argc, argv);
                    auto* t = new V%1$s;
                    t->clock = 0; t->reset = 1; t->eval();
                    t->clock = 1; t->eval(); t->clock = 0; t->reset = 0; t->eval();
                    long cycles = 0;
                    auto s = std::chrono::steady_clock::now();
                    while (!t->halted) { t->clock = 1; t->eval(); t->clock = 0; t->eval(); cycles++; }
                    double sec = std::chrono::duration<double>(std::chrono::steady_clock::now() - s).count();
                    std::printf("%%ld %%.6f\\n", cycles, sec);
                    delete t;
                    return 0;
                }
                """.formatted(top));
        // a cold build every time: an obj_dir left by an earlier run would make the build time a no-op
        var obj = dir.resolve("obj_dir");
        if (Files.exists(obj)) try (var w = Files.walk(obj)) {
            for (var p : w.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(p);
        }
        run(dir, "verilator", "--cc", "--exe", "--build", "-O3", "-Wno-fatal", "--top-module", top, "-CFLAGS", "-O3",
                "core.sv", "main.cpp", "-o", "sim");
        String[] out = run(dir, dir.resolve("obj_dir/sim").toString()).trim().split("\\s+");
        return new Rate(Long.parseLong(out[out.length - 2]), Double.parseDouble(out[out.length - 1]));
    }

    static String run(java.nio.file.Path dir, String... cmd) throws Exception {
        var p = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        if (p.waitFor() != 0) throw new AssertionError(String.join(" ", cmd) + "\n" + out);
        return out;
    }

    @Test
    void cyclesPerSecond() throws Exception {
        assumeTrue(Boolean.getBoolean("bench"), "run with -Pbench");
        int[] prog = Benchmarks.loop(ITERATIONS);
        int[] small = Benchmarks.loop(5_000);
        var cores = new java.util.LinkedHashMap<String, Elaborated<? extends RvCore>>();
        cores.put("single", Tessera.elaborate(() -> new SingleCycleCore(4096, prog)));
        cores.put("sched5", Tessera.elaborate(() -> new ScheduledCore(4096, prog, ScheduledCore.FIVE_STAGE)));
        cores.put("ooo", Tessera.elaborate(() -> new OooCore(4096, prog)));
        var smallCores = new java.util.LinkedHashMap<String, Elaborated<? extends RvCore>>();
        smallCores.put("single", Tessera.elaborate(() -> new SingleCycleCore(4096, small)));
        smallCores.put("sched5", Tessera.elaborate(() -> new ScheduledCore(4096, small, ScheduledCore.FIVE_STAGE)));
        smallCores.put("ooo", Tessera.elaborate(() -> new OooCore(4096, small)));
        for (var k : cores.keySet()) {
            var e = cores.get(k);
            tessera(e, Sim.Backend.COMPILED, ITERATIONS);                       // JIT warm-up
            Rate compiled = tessera(e, Sim.Backend.COMPILED, ITERATIONS);
            Rate interp = tessera(smallCores.get(k), Sim.Backend.INTERPRETER, 5_000);
            Rate v = Verilator.available() ? verilator(e, k) : null;
            if (v != null) assertEquals(compiled.cycles(), v.cycles(), "same cycle count in Verilator");
            System.out.printf("SPEED %s: %d cycles; compiled %.0f Hz, interpreter %.0f Hz, verilator %s Hz%n", k,
                    compiled.cycles(), compiled.hz(), interp.hz(), v == null ? "-" : String.format("%.0f", v.hz()));
        }
    }
}
