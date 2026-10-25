package org.eninehq.tessera.sim;

import java.math.BigInteger;

/**
 * Arbitrary-width bit vectors stored as little-endian {@code long} words, with the bits
 * above the width kept at zero. Used by the simulators for values wider than 64 bits.
 * All methods are static and allocation is limited to result arrays, so the JIT can
 * keep the common narrow cases cheap.
 */
public final class BitVec {
    private BitVec() {}

    public static int words(int width) {
        return (width + 63) >>> 6;
    }

    static long topMask(int width) {
        int r = width & 63;
        return r == 0 ? -1L : (1L << r) - 1;
    }

    /** Clear the bits above {@code width} in place. */
    static void normalize(long[] a, int width) {
        a[a.length - 1] &= topMask(width);
    }

    /** Read a value of width {@code aw} at {@code v[off..]} and extend it (by {@code signed}) to {@code w} bits. */
    static long[] load(long[] v, int off, int aw, boolean signed, int w) {
        int nw = words(w), na = words(aw);
        long[] r = new long[nw];
        int n = Math.min(na, nw);
        System.arraycopy(v, off, r, 0, n);
        if (signed && aw < w && bit(r, aw - 1)) {
            // sign-extend: set bits aw..w-1
            int top = (aw - 1) >>> 6;
            int rem = aw & 63;
            if (rem != 0) r[top] |= -1L << rem;
            for (int k = top + 1; k < nw; k++) r[k] = -1L;
        }
        normalize(r, w);
        return r;
    }

    static boolean bit(long[] a, int i) {
        return (a[i >>> 6] >>> (i & 63) & 1) != 0;
    }

    static void store(long[] v, int off, long[] r) {
        System.arraycopy(r, 0, v, off, r.length);
    }

    static long[] add(long[] a, long[] b, int w) {
        long[] r = new long[a.length];
        long carry = 0;
        for (int k = 0; k < a.length; k++) {
            long s = a[k] + b[k];
            long c1 = Long.compareUnsigned(s, a[k]) < 0 ? 1 : 0;
            long s2 = s + carry;
            long c2 = Long.compareUnsigned(s2, s) < 0 ? 1 : 0;
            r[k] = s2;
            carry = c1 | c2;
        }
        normalize(r, w);
        return r;
    }

    static long[] not(long[] a, int w) {
        long[] r = new long[a.length];
        for (int k = 0; k < a.length; k++) r[k] = ~a[k];
        normalize(r, w);
        return r;
    }

    static long[] sub(long[] a, long[] b, int w) {
        // a - b = a + ~b + 1
        long[] nb = not(b, w);
        long[] one = new long[a.length];
        one[0] = 1;
        return add(add(a, nb, w), one, w);
    }

    static long[] mul(long[] a, long[] b, int w) {
        int n = a.length;
        long[] r = new long[n];
        for (int i = 0; i < n; i++) {
            if (a[i] == 0) continue;
            long carry = 0;
            for (int j = 0; i + j < n; j++) {
                long lo = a[i] * b[j];
                long hi = Math.unsignedMultiplyHigh(a[i], b[j]);
                long s = r[i + j] + lo;
                if (Long.compareUnsigned(s, lo) < 0) hi++;
                long s2 = s + carry;
                if (Long.compareUnsigned(s2, s) < 0) hi++;
                r[i + j] = s2;
                carry = hi;
            }
        }
        normalize(r, w);
        return r;
    }

    static long[] and(long[] a, long[] b) {
        long[] r = new long[a.length];
        for (int k = 0; k < a.length; k++) r[k] = a[k] & b[k];
        return r;
    }

    static long[] or(long[] a, long[] b) {
        long[] r = new long[a.length];
        for (int k = 0; k < a.length; k++) r[k] = a[k] | b[k];
        return r;
    }

    static long[] xor(long[] a, long[] b) {
        long[] r = new long[a.length];
        for (int k = 0; k < a.length; k++) r[k] = a[k] ^ b[k];
        return r;
    }

    /** Bits {@code [lo, lo + n)} of {@code a} as a new vector of {@code n} bits. */
    static long[] extract(long[] a, int lo, int n) {
        long[] r = new long[words(n)];
        for (int k = 0; k < r.length; k++) {
            int bitPos = lo + k * 64;
            int wi = bitPos >>> 6, sh = bitPos & 63;
            long x = wi < a.length ? a[wi] >>> sh : 0;
            if (sh != 0 && wi + 1 < a.length) x |= a[wi + 1] << (64 - sh);
            r[k] = x;
        }
        normalize(r, n);
        return r;
    }

    /** OR {@code src} (of {@code n} bits) into {@code dst} at bit {@code lo}. */
    static void deposit(long[] dst, long[] src, int lo, int n) {
        for (int k = 0; k < words(n); k++) {
            long x = src[k];
            int bitPos = lo + k * 64;
            int wi = bitPos >>> 6, sh = bitPos & 63;
            if (wi < dst.length) dst[wi] |= x << sh;
            if (sh != 0 && wi + 1 < dst.length) dst[wi + 1] |= x >>> (64 - sh);
        }
    }

    static long[] shl(long[] a, int n, int w) {
        long[] r = new long[words(w)];
        long[] src = a;
        deposit(r, src, n, Math.min(w - n, a.length * 64));
        normalize(r, w);
        return r;
    }

    static long[] shr(long[] a, int aw, int n, boolean arith) {
        long[] r = n >= aw ? new long[words(aw)] : extract(a, n, aw - n);
        if (r.length < words(aw)) {
            long[] t = new long[words(aw)];
            System.arraycopy(r, 0, t, 0, r.length);
            r = t;
        }
        if (arith && bit(a, aw - 1)) {
            int from = Math.max(0, aw - n);
            for (int i = from; i < aw; i++) r[i >>> 6] |= 1L << (i & 63);
        }
        normalize(r, aw);
        return r;
    }

    static int compareUnsigned(long[] a, long[] b) {
        for (int k = a.length - 1; k >= 0; k--) {
            int c = Long.compareUnsigned(a[k], b[k]);
            if (c != 0) return c;
        }
        return 0;
    }

    /** Compare two {@code w}-bit two's-complement values. */
    static int compareSigned(long[] a, long[] b, int w) {
        boolean sa = bit(a, w - 1), sb = bit(b, w - 1);
        if (sa != sb) return sa ? -1 : 1;
        return compareUnsigned(a, b);
    }

    static boolean isZero(long[] a) {
        for (long x : a) if (x != 0) return false;
        return true;
    }

    public static BigInteger toBig(long[] v, int off, int width) {
        BigInteger r = BigInteger.ZERO;
        for (int k = words(width) - 1; k >= 0; k--)
            r = r.shiftLeft(64).or(new BigInteger(Long.toUnsignedString(v[off + k])));
        return r;
    }

    public static void fromBig(BigInteger x, long[] v, int off, int width) {
        BigInteger m = BigInteger.ONE.shiftLeft(width).subtract(BigInteger.ONE);
        x = x.and(m);
        for (int k = 0; k < words(width); k++) {
            v[off + k] = x.longValue();
            x = x.shiftRight(64);
        }
    }
}
