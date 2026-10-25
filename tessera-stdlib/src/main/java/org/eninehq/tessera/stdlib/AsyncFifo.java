package org.eninehq.tessera.stdlib;

import static org.eninehq.tessera.core.Hw.when;

import org.eninehq.tessera.core.Bool;
import org.eninehq.tessera.core.ClockDomain;
import org.eninehq.tessera.core.Data;
import org.eninehq.tessera.core.Hw;
import org.eninehq.tessera.core.HwType;
import org.eninehq.tessera.core.Mem;
import org.eninehq.tessera.core.Module;
import org.eninehq.tessera.core.UInt;

/**
 * A FIFO between two clock domains with Gray-coded pointers (Cummings, SNUG 2002). The write
 * side runs in domain {@link #wr}, the read side in {@link #rd}; each pointer crosses through
 * a two-stage synchronizer, so the CDC lint accepts the design. Depth must be a power of two.
 */
public final class AsyncFifo<T extends Data> extends Module {
    public final ClockDomain wr, rd;
    public final Stream<T> in;
    public final Stream<T> out;

    public AsyncFifo(HwType<T> type, int depth) {
        if (depth < 2 || Integer.bitCount(depth) != 1) throw new IllegalArgumentException("depth must be a power of two >= 2");
        int aw = Integer.numberOfTrailingZeros(depth);
        int pw = aw + 1; // the extra bit tells full from empty
        wr = clockDomain("wr");
        rd = clockDomain("rd");
        in = input("in", Stream.of(type));
        out = output("out", Stream.of(type));
        Mem<T> mem = mem("mem", type, depth).crossesClockDomains();

        UInt wbin = withClock(wr, () -> reg("wr_bin", UInt.w(pw), 0));
        UInt wgray = withClock(wr, () -> reg("wr_gray", UInt.w(pw), 0));
        UInt rbin = withClock(rd, () -> reg("rd_bin", UInt.w(pw), 0));
        UInt rgray = withClock(rd, () -> reg("rd_gray", UInt.w(pw), 0));
        UInt rgrayInWr = withClock(wr, () -> Hw.synchronizeGray(rgray, 2));
        UInt wgrayInRd = withClock(rd, () -> Hw.synchronizeGray(wgray, 2));

        // write side: full when the write pointer is exactly one lap ahead of the read pointer
        UInt lap = UInt.lit(3L << (pw - 2), pw);
        Bool full = wgray.eq(rgrayInWr.xor(lap)).named("full");
        in.ready().set(full.not());
        Bool push = in.valid().and(full.not()).named("push");
        withClock(wr, () -> when(push, () -> mem.write(wbin.trunc(aw), in.bits())));
        UInt wnext = wbin.addWrap(push);
        wbin.set(wnext);
        wgray.set(gray(wnext));

        // read side
        Bool empty = rgray.eq(wgrayInRd).named("empty");
        out.valid().set(empty.not());
        Hw.assign(out.bits(), mem.read(rbin.trunc(aw)));
        Bool pop = out.valid().and(out.ready()).named("pop");
        UInt rnext = rbin.addWrap(pop);
        rbin.set(rnext);
        rgray.set(gray(rnext));
    }

    static UInt gray(UInt x) {
        return x.xor(x.shr(1));
    }
}
