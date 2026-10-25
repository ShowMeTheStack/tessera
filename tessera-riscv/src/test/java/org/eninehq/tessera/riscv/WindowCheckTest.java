package org.eninehq.tessera.riscv;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.eninehq.tessera.core.Tessera;
import org.junit.jupiter.api.Test;

/** The out-of-order window matters where independent work can overlap a long divide. */
class WindowCheckTest {
    @Test
    void largerWindowsHideMoreOfTheDivide() {
        long[] cycles = new long[4];
        int[][] sizes = {{4, 3}, {8, 6}, {16, 8}, {32, 8}};
        for (int k = 0; k < sizes.length; k++) {
            int rob = sizes[k][0], rs = sizes[k][1];
            var e = Tessera.elaborate(() -> new OooCore(RiscvTests.MEM_WORDS, null, Cores.common(true), rob, rs));
            try (var sim = CoreRunner.sim(e)) {
                cycles[k] = CoreRunner.run(e, sim, Benchmarks.divideOverlap(), 100_000).cycles();
            }
            System.out.printf("WINDOW ROB %d RS %d: %d cycles%n", rob, rs, cycles[k]);
        }
        assertTrue(cycles[0] > cycles[1] && cycles[1] > cycles[2], "bigger windows overlap more of the divide");
    }
}
