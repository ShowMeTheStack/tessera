package org.eninehq.tessera.riscv;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.util.Arrays;
import org.eninehq.tessera.core.Tessera;
import org.junit.jupiter.api.Test;

/**
 * Where the generated C7 (C7g) spends cycles that the hand-written C7 does not. Counts, over whole
 * workload runs, the cycles with a commit-time flush (C7: "flush"; C7g: "core_flush"). Opt-in: -Pworkloads with
 * -PworkloadNames=...; needs build/workloads.
 */
class GeneratedOooAnalysis {
    @Test
    void flushes() throws Exception {
        assumeTrue(Boolean.getBoolean("workloads"), "opt-in: -Pworkloads");
        var names = java.util.List.of(System.getProperty("workloadNames", "").split(",")).stream().filter(s -> !s.isBlank()).toList();
        assumeTrue(!names.isEmpty() && Files.isDirectory(WorkloadTest.DIR), "needs -PworkloadNames and " + WorkloadTest.DIR);
        for (var id : new String[]{"C7", "C7g"}) {
            var e = Tessera.elaborate(() -> Cores.get(id).build(WorkloadTest.MEM, null));
            String flushPath = id.equals("C7") ? "flush" : "core_flush";
            for (var w : names) {
                int[] img = WorkloadTest.image(w);
                RvCore core = e.top();
                try (var sim = CoreRunner.sim(e)) {
                    long[] imem = sim.memory(core.imem), dmem = sim.memory(core.dmem), regs = sim.memory(core.regs);
                    Arrays.fill(imem, 0);
                    Arrays.fill(dmem, 0);
                    Arrays.fill(regs, 0);
                    for (int i = 0; i < img.length; i++) {
                        imem[i] = Integer.toUnsignedLong(img[i]);
                        dmem[i] = Integer.toUnsignedLong(img[i]);
                    }
                    sim.reset(1);
                    long start = sim.cycle(), retired = 0, flushes = 0;
                    while (sim.peek(core.halted) == 0) {
                        if (sim.peek(core.rvfiValid) == 1) retired++;
                        if (sim.peek(flushPath) == 1) flushes++;
                        sim.step();
                    }
                    System.out.printf("FLUSHES %s %s cycles %d retired %d flush_cycles %d%n", id, w, sim.cycle() - start, retired, flushes);
                }
            }
        }
    }

    /** Loads out of order: per workload, the loads issued early, those forwarded from a store, the cycles a ready load waited. */
    @Test
    void loadUnit() throws Exception {
        assumeTrue(Boolean.getBoolean("workloads"), "opt-in: -Pworkloads");
        var names = java.util.List.of(System.getProperty("workloadNames", "").split(",")).stream().filter(s -> !s.isBlank()).toList();
        assumeTrue(!names.isEmpty() && Files.isDirectory(WorkloadTest.DIR), "needs -PworkloadNames and " + WorkloadTest.DIR);
        for (var id : new String[]{"C7g-lsq", "C7g-er-lsq"}) {
            var e = Tessera.elaborate(() -> Cores.get(id).build(WorkloadTest.MEM, null));
            for (var w : names) {
                int[] img = WorkloadTest.image(w);
                RvCore core = e.top();
                try (var sim = CoreRunner.sim(e)) {
                    long[] imem = sim.memory(core.imem), dmem = sim.memory(core.dmem), regs = sim.memory(core.regs);
                    Arrays.fill(imem, 0);
                    Arrays.fill(dmem, 0);
                    Arrays.fill(regs, 0);
                    for (int i = 0; i < img.length; i++) {
                        imem[i] = Integer.toUnsignedLong(img[i]);
                        dmem[i] = Integer.toUnsignedLong(img[i]);
                    }
                    sim.reset(1);
                    long start = sim.cycle(), issues = 0, forwards = 0, waits = 0;
                    while (sim.peek(core.halted) == 0) {
                        boolean issue = sim.peek("core_lsu_issue") == 1;
                        if (issue) issues++;
                        if (issue && sim.peek("core_lsu_forward_now") == 1) forwards++;
                        if (sim.peek("core_lsu_load_ready") == 1 && sim.peek("core_lsu_order_ok_now") == 0) waits++;
                        sim.step();
                    }
                    System.out.printf("LSU %s %s cycles %d load_issues %d forwarded %d ready_but_waiting_cycles %d%n", id, w,
                            sim.cycle() - start, issues, forwards, waits);
                }
            }
        }
    }

    /** CoreMatrixTest's random programs (seeds 1000-1099): cycles of C7 and C7g, and the divides/multiplies retired. */
    @Test
    void randomPrograms() {
        assumeTrue(Boolean.getBoolean("workloads"), "opt-in: -Pworkloads");
        var c7 = Tessera.elaborate(() -> Cores.get("C7").build(RiscvTests.MEM_WORDS, null));
        var c7g = Tessera.elaborate(() -> Cores.get("C7g").build(RiscvTests.MEM_WORDS, null));
        long sumC7 = 0, sumG = 0, sumDiv = 0, sumMul = 0;
        for (long seed = 1000; seed < 1100; seed++) {
            int[] img = RandomProgram.generate(seed, 400, seed % 2 == 1);
            long a, b;
            try (var sim = CoreRunner.sim(c7)) { a = CoreRunner.run(c7, sim, img, 200_000).cycles(); }
            try (var sim = CoreRunner.sim(c7g)) { b = CoreRunner.run(c7g, sim, img, 200_000).cycles(); }
            // divides and multiplies retired, from the ISS
            var iss = new Rv32iIss(RiscvTests.MEM_WORDS, img, false);
            long div = 0, mul = 0;
            while (!iss.halted) {
                int insn = iss.step().insn();
                if ((insn & 0x7F) == 0x33 && (insn >>> 25) == 1) { if ((insn & 0x4000) != 0) div++; else mul++; }
            }
            sumC7 += a;
            sumG += b;
            sumDiv += div;
            sumMul += mul;
            if (a != b) System.out.printf("RANDOM seed %d C7 %d C7g %d diff %d divides %d multiplies %d%n", seed, a, b, b - a, div, mul);
        }
        System.out.printf("RANDOM total C7 %d C7g %d diff %d divides %d multiplies %d%n", sumC7, sumG, sumG - sumC7, sumDiv, sumMul);
    }
}
