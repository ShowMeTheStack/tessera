package org.eninehq.tessera.riscv;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.List;
import org.eninehq.tessera.core.Tessera;
import org.junit.jupiter.api.Test;

/**
 * A longer co-simulation soak of the generated out-of-order cores than CoreMatrixTest's 100 random programs:
 * 2000 random programs (seeds 10000-11999, half RV32IM) and 2000 dual-issue stress programs ({@link
 * RandomProgram#pairs}, machine mode with traps), every retirement against the ISS with the SideEffects monitors.
 * Opt-in: -Pworkloads; cores from -Pcores (default C7g, C7g-er, C7g-4x3, C7g-er-4x3).
 */
class GeneratedOooSoak {
    @Test
    void soak() {
        assumeTrue(Boolean.getBoolean("workloads"), "opt-in: -Pworkloads");
        var only = List.of(System.getProperty("cores", "").split(",")).stream().filter(s -> !s.isBlank()).toList();
        var ids = only.isEmpty() ? List.of("C7g", "C7g-er", "C7g-4x3", "C7g-er-4x3") : only;
        for (var id : ids) {
            var e = Tessera.elaborate(() -> Cores.get(id).build(RiscvTests.MEM_WORDS, null));
            long cycles = 0, retired = 0;
            for (long seed = 10000; seed < 12000; seed++) {
                try (var sim = CoreRunner.sim(e)) {
                    var r = CoreRunner.run(e, sim, RandomProgram.generate(seed, 400, seed % 2 == 1), 200_000);
                    assertTrue(r.retired() > 100);
                    cycles += r.cycles();
                    retired += r.retired();
                }
            }
            System.out.printf("SOAK %s random 2000: %d cycles, %d retired, CPI %.4f%n", id, cycles, retired, (double) cycles / retired);
            cycles = retired = 0;
            for (long seed = 10000; seed < 12000; seed++) {
                try (var sim = CoreRunner.sim(e)) {
                    var r = CoreRunner.run(e, sim, RandomProgram.pairs(seed, 300), 200_000, true);
                    cycles += r.cycles();
                    retired += r.retired();
                }
            }
            System.out.printf("SOAK %s pairs 2000: %d cycles, %d retired, CPI %.4f%n", id, cycles, retired, (double) cycles / retired);
        }
    }
}
