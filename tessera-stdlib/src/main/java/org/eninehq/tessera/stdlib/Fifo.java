package org.eninehq.tessera.stdlib;

import static org.eninehq.tessera.core.Hw.when;

import org.eninehq.tessera.core.Bool;
import org.eninehq.tessera.core.Data;
import org.eninehq.tessera.core.Hw;
import org.eninehq.tessera.core.HwType;
import org.eninehq.tessera.core.Mem;
import org.eninehq.tessera.core.Module;
import org.eninehq.tessera.core.UInt;

/**
 * A synchronous FIFO between two streams. With {@code fallThrough}, an item written into an
 * empty FIFO can be read in the same cycle; otherwise outputs come from registers/memory.
 * Full throughput: one item in and one out per cycle.
 */
public final class Fifo<T extends Data> extends Module {
    public final Stream<T> in;
    public final Stream<T> out;
    /** Number of items stored. */
    public final UInt count;

    public Fifo(HwType<T> type, int depth) {
        this(type, depth, false);
    }

    public Fifo(HwType<T> type, int depth, boolean fallThrough) {
        if (depth < 1) throw new IllegalArgumentException("FIFO depth must be >= 1");
        in = input("in", Stream.of(type));
        out = output("out", Stream.of(type));
        int aw = Math.max(1, 32 - Integer.numberOfLeadingZeros(depth - 1));
        int cw = 32 - Integer.numberOfLeadingZeros(depth);
        count = output("count", UInt.w(cw));
        Mem<T> mem = mem("mem", type, depth);
        UInt rd = reg("rd_ptr", UInt.w(aw), 0);
        UInt wr = reg("wr_ptr", UInt.w(aw), 0);
        UInt n = reg("n", UInt.w(cw), 0);
        Bool empty = n.eq(0).named("empty");
        Bool full = n.eq(depth).named("full");
        Bool bypass = fallThrough ? empty.and(in.valid()).named("bypass") : Bool.of(false);

        in.ready().set(full.not().or(out.ready().and(full)));
        out.valid().set(empty.not().or(bypass));
        Hw.assign(out.bits(), Hw.mux(bypass, in.bits(), mem.read(rd)));
        Bool push = in.fire().and(bypass.and(out.ready()).not()).named("push");
        Bool pop = out.fire().and(bypass.not()).named("pop");
        when(push, () -> {
            mem.write(wr, in.bits());
            wr.set(wrap(wr, depth));
        });
        when(pop, () -> rd.set(wrap(rd, depth)));
        when(push.and(pop.not()), () -> n.set(n.addWrap(1)))
                .elseWhen(pop.and(push.not()), () -> n.set(n.subWrap(1)));
        count.set(n);
    }

    static UInt wrap(UInt p, int depth) {
        if (depth == 1) return UInt.lit(0, p.width());
        if (Integer.bitCount(depth) == 1) return p.addWrap(1);
        return Hw.mux(p.eq(depth - 1), UInt.lit(0, p.width()), p.addWrap(1));
    }
}
