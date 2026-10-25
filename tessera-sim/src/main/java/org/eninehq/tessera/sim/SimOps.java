package org.eninehq.tessera.sim;

/** Small helpers called from generated simulator code; the JIT inlines them. */
public final class SimOps {
    private SimOps() {}

    public static long dshl(long a, long amt, long mask) {
        return Long.compareUnsigned(amt, 64) >= 0 ? 0 : (a << amt) & mask;
    }

    public static long dshr(long a, long amt, long mask) {
        return Long.compareUnsigned(amt, 64) >= 0 ? 0 : (a >>> amt) & mask;
    }

    public static long dsra(long a, long amt, long mask) {
        return (Long.compareUnsigned(amt, 63) >= 0 ? a >> 63 : a >> amt) & mask;
    }

    public static long eq(long a, long b) { return a == b ? 1 : 0; }
    public static long ne(long a, long b) { return a != b ? 1 : 0; }
    public static long lt(long a, long b) { return a < b ? 1 : 0; }
    public static long le(long a, long b) { return a <= b ? 1 : 0; }
    public static long gt(long a, long b) { return a > b ? 1 : 0; }
    public static long ge(long a, long b) { return a >= b ? 1 : 0; }
    public static long ltu(long a, long b) { return Long.compareUnsigned(a, b) < 0 ? 1 : 0; }
    public static long leu(long a, long b) { return Long.compareUnsigned(a, b) <= 0 ? 1 : 0; }
    public static long gtu(long a, long b) { return Long.compareUnsigned(a, b) > 0 ? 1 : 0; }
    public static long geu(long a, long b) { return Long.compareUnsigned(a, b) >= 0 ? 1 : 0; }

    public static long mux(long c, long t, long f) { return c != 0 ? t : f; }

    public static long andr(long a, long mask) { return a == mask ? 1 : 0; }
    public static long orr(long a) { return a != 0 ? 1 : 0; }
    public static long xorr(long a) { return Long.bitCount(a) & 1; }

    public static long memRead(long[] mem, long addr) {
        return Long.compareUnsigned(addr, mem.length) < 0 ? mem[(int) addr] : 0;
    }
}
