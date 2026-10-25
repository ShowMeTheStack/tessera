package org.eninehq.tessera.riscv;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.eninehq.tessera.core.Tessera;
import org.junit.jupiter.api.Test;

/**
 * The three micro-benchmarks of {@link Benchmarks} on every validation core: where an out-of-order window or a
 * second lane pays off (independent work beside a divide) and where nothing can (a dependent chain). Writes
 * build/results/microbench.csv (or into -PresultsDir).
 */
class MicroBenchTable {
    @Test
    void everyCore() throws Exception {
        var rows = new ArrayList<String>(List.of("core,divide_overlap,multiply_overlap,dependent_chain"));
        for (var spec : Cores.ALL) {
            long[] c = new long[3];
            int[][] progs = {Benchmarks.divideOverlap(), Benchmarks.multiplyOverlap(), Benchmarks.dependentChain()};
            for (int k = 0; k < 3; k++) {
                int[] prog = progs[k];
                var e = Tessera.elaborate(() -> spec.build(RiscvTests.MEM_WORDS, null));
                try (var sim = CoreRunner.sim(e)) {
                    // CoreRunner compares every retirement with the ISS and fails if the program does not finish
                    c[k] = CoreRunner.run(e, sim, prog, 100_000).cycles();
                }
            }
            rows.add(spec.id() + "," + c[0] + "," + c[1] + "," + c[2]);
            System.out.println("MICRO " + rows.getLast());
        }
        Path out = Path.of(System.getProperty("resultsDir", Path.of(System.getProperty("user.dir")).getParent().resolve("build/results").toString()))
                .resolve("microbench.csv");
        Files.writeString(out, String.join("\n", rows) + "\n");
    }
}
