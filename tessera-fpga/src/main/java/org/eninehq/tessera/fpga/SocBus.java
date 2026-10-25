package org.eninehq.tessera.fpga;

/** Word access to an {@link RvSoc}'s registers and memories, by offset from its base address. */
public interface SocBus extends AutoCloseable {
    void write(long offset, long value);

    long read(long offset);

    default void writeBlock(long offset, int[] words) {
        for (int i = 0; i < words.length; i++) write(offset + 4L * i, Integer.toUnsignedLong(words[i]));
    }

    default long[] readBlock(long offset, int n) {
        long[] out = new long[n];
        for (int i = 0; i < n; i++) out[i] = read(offset + 4L * i);
        return out;
    }

    /** Poll {@code offset} until {@code (value & mask) != 0}; false after {@code maxPolls} reads. */
    default boolean waitFor(long offset, long mask, int maxPolls) {
        for (int i = 0; i < maxPolls; i++) if ((read(offset) & mask) != 0) return true;
        return false;
    }

    @Override
    default void close() {}
}
