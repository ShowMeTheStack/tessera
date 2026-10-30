package org.eninehq.tessera.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Random;
import org.junit.jupiter.api.Test;

/** The 64x64 bit transpose used by BatchSim's memory ports against the obvious bit-by-bit version. */
class BatchTransposeTest {
    @Test
    void matchesBitByBit() {
        var rnd = new Random(7);
        for (int t = 0; t < 200; t++) {
            long[] a = new long[64];
            for (int i = 0; i < 64; i++) a[i] = t % 3 == 0 ? rnd.nextLong() & rnd.nextLong() : rnd.nextLong();
            long[] want = new long[64];
            for (int i = 0; i < 64; i++) for (int j = 0; j < 64; j++) want[j] |= (a[i] >>> j & 1) << i;
            long[] got = a.clone();
            BatchSim.transpose64(got);
            for (int j = 0; j < 64; j++) assertEquals(want[j], got[j], "row " + j);
        }
    }
}
