package org.eninehq.tessera.stdlib;

import static org.eninehq.tessera.core.Hw.when;

import org.eninehq.tessera.core.Bool;
import org.eninehq.tessera.core.Data;
import org.eninehq.tessera.core.Hw;
import org.eninehq.tessera.core.UInt;

/** Small reusable circuits. Combinational helpers create no state; the others create registers in the current domain. */
public final class Util {
    private Util() {}

    /** ceil(log2(n)), at least 1: the bits needed to index {@code n} items. */
    public static int clog2(int n) {
        return Math.max(1, 32 - Integer.numberOfLeadingZeros(n - 1));
    }

    /** Index of the lowest set bit, valid when any bit is set. */
    public static Valid<UInt> priorityEncoder(UInt x) {
        int w = clog2(x.width());
        UInt idx = UInt.lit(0, w);
        for (int i = x.width() - 1; i >= 0; i--) idx = Hw.mux(x.bit(i), UInt.lit(i, w), idx);
        return new Valid<>(x.orR(), idx);
    }

    /** One-hot encoding of {@code idx} over {@code n} outputs. */
    public static UInt oneHot(UInt idx, int n) {
        UInt v = idx.eq(n - 1);
        for (int i = n - 2; i >= 0; i--) v = v.cat(idx.eq(i));
        return v;
    }

    /** Number of set bits. */
    public static UInt popCount(UInt x) {
        int w = clog2(x.width() + 1);
        UInt acc = UInt.lit(0, w);
        for (int i = 0; i < x.width(); i++) acc = acc.addWrap(x.bit(i).zext(w));
        return acc;
    }

    /** Bits in reverse order. */
    public static UInt reverse(UInt x) {
        UInt v = x.bit(0);
        for (int i = 1; i < x.width(); i++) v = v.cat(x.bit(i));
        return v;
    }

    public static UInt toGray(UInt x) {
        return x.xor(x.shr(1));
    }

    public static UInt fromGray(UInt g) {
        UInt b = g.bit(g.width() - 1);
        UInt prev = b;
        for (int i = g.width() - 2; i >= 0; i--) {
            Bool bit = prev.bit(0).xor(g.bit(i));
            b = b.cat(bit);
            prev = bit;
        }
        return b;
    }

    // ---------------------------------------------------------------- stateful helpers

    /** {@code d} delayed by {@code n} cycles (registers without reset). */
    public static <T extends Data> T delay(T d, int n) {
        return Hw.past(d, n);
    }

    /** True for one cycle when {@code x} goes from 0 to 1. */
    public static Bool risingEdge(Bool x) {
        return x.and(Hw.past(x, 1).not());
    }

    /** True for one cycle when {@code x} goes from 1 to 0. */
    public static Bool fallingEdge(Bool x) {
        return x.not().and(Hw.past(x, 1));
    }

    /** A counter from 0 to {@code max} (inclusive) that advances when {@code inc}; returns {value, wrap}. */
    public record Count(UInt value, Bool wrap) {}

    public static Count counter(int max, Bool inc) {
        int w = clog2(max + 1);
        UInt c = Hw.register("count", UInt.w(w), 0L);
        Bool atMax = c.eq(max);
        when(inc, () -> c.set(Hw.mux(atMax, UInt.lit(0, w), c.addWrap(1))));
        return new Count(c, inc.and(atMax));
    }

    /** A Fibonacci LFSR with a maximal-length polynomial for widths 2..32; never reaches zero. */
    public static UInt lfsr(int width, Bool step) {
        int[][] taps = {{}, {}, {2, 1}, {3, 2}, {4, 3}, {5, 3}, {6, 5}, {7, 6}, {8, 6, 5, 4}, {9, 5}, {10, 7}, {11, 9},
                {12, 6, 4, 1}, {13, 4, 3, 1}, {14, 5, 3, 1}, {15, 14}, {16, 15, 13, 4}, {17, 14}, {18, 11}, {19, 6, 2, 1},
                {20, 17}, {21, 19}, {22, 21}, {23, 18}, {24, 23, 22, 17}, {25, 22}, {26, 6, 2, 1}, {27, 5, 2, 1},
                {28, 25}, {29, 27}, {30, 6, 4, 1}, {31, 28}, {32, 22, 2, 1}};
        if (width < 2 || width > 32) throw new IllegalArgumentException("LFSR width must be 2..32");
        UInt r = Hw.register("lfsr", UInt.w(width), 1L);
        Bool fb = Bool.of(false);
        for (int t : taps[width]) fb = fb.xor(r.bit(t - 1));
        Bool feedback = fb;
        when(step, () -> r.set(r.bits(width - 2, 0).cat(feedback)));
        return r;
    }

    /**
     * Debounce a noisy input: the output changes only after the input has been stable for
     * {@code cycles} cycles. Synchronize asynchronous inputs first.
     */
    public static Bool debounce(Bool in, int cycles) {
        Bool out = Hw.register("debounced", Bool.TYPE, 0L);
        Bool last = Hw.register("last", Bool.TYPE, 0L);
        last.set(in);
        UInt cnt = Hw.register("stable", UInt.w(clog2(cycles + 1)), 0L);
        when(in.ne(last), () -> cnt.set(0))
                .elseWhen(cnt.ne(cycles), () -> cnt.set(cnt.addWrap(1)))
                .otherwise(() -> out.set(in));
        return out;
    }
}
