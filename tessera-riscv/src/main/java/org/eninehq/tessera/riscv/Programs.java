package org.eninehq.tessera.riscv;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Program images for the cores and the ISS. */
public final class Programs {
    private Programs() {}

    /** A flat little-endian binary as 32-bit words; a partial last word is zero-padded, not dropped. */
    public static int[] words(byte[] bytes) {
        byte[] padded = java.util.Arrays.copyOf(bytes, (bytes.length + 3) & ~3);
        var buf = ByteBuffer.wrap(padded).order(ByteOrder.LITTLE_ENDIAN);
        int[] w = new int[padded.length / 4];
        for (int i = 0; i < w.length; i++) w[i] = buf.getInt();
        return w;
    }
}
