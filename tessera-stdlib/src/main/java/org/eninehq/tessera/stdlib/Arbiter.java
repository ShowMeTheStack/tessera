package org.eninehq.tessera.stdlib;

import static org.eninehq.tessera.core.Hw.when;

import java.util.ArrayList;
import java.util.List;
import org.eninehq.tessera.core.Bool;
import org.eninehq.tessera.core.Data;
import org.eninehq.tessera.core.Hw;
import org.eninehq.tessera.core.HwType;
import org.eninehq.tessera.core.Module;
import org.eninehq.tessera.core.UInt;
import org.eninehq.tessera.core.Vec;

/**
 * Merges {@code n} streams into one. {@link Policy#PRIORITY} always prefers the lowest
 * index; {@link Policy#ROUND_ROBIN} starts searching after the last granted input, so every
 * requester is eventually served. {@link #chosen} tells which input the output came from.
 */
public final class Arbiter<T extends Data> extends Module {
    public enum Policy { PRIORITY, ROUND_ROBIN }

    public final Vec<Stream<T>> in;
    public final Stream<T> out;
    public final UInt chosen;

    public Arbiter(HwType<T> type, int n, Policy policy) {
        if (n < 1) throw new IllegalArgumentException("need at least one input");
        int iw = Math.max(1, 32 - Integer.numberOfLeadingZeros(n - 1));
        in = input("in", Vec.of(n, Stream.of(type)));
        out = output("out", Stream.of(type));
        chosen = output("chosen", UInt.w(iw));
        UInt last = policy == Policy.ROUND_ROBIN ? reg("last", UInt.w(iw), n - 1) : null;

        // grant[i]: input i wins. Round robin: rotate priority so that last+1 comes first.
        List<Bool> grant = new ArrayList<>();
        for (int i = 0; i < n; i++) grant.add(null);
        if (policy == Policy.PRIORITY) {
            Bool taken = Bool.of(false);
            for (int i = 0; i < n; i++) {
                grant.set(i, in.get(i).valid().and(taken.not()));
                taken = taken.or(in.get(i).valid());
            }
        } else {
            for (int i = 0; i < n; i++) grant.set(i, Bool.of(false));
            for (int start = 0; start < n; start++) {
                // when last == start - 1 (mod n), search order is start, start+1, ...
                Bool active = last.eq(UInt.lit((start + n - 1) % n, iw));
                Bool taken = Bool.of(false);
                for (int k = 0; k < n; k++) {
                    int i = (start + k) % n;
                    Bool g = active.and(in.get(i).valid()).and(taken.not());
                    grant.set(i, grant.get(i).or(g));
                    taken = taken.or(in.get(i).valid());
                }
            }
        }
        Bool any = Bool.of(false);
        T bits = in.get(n - 1).bits();
        UInt idx = UInt.lit(n - 1, iw);
        for (int i = n - 1; i >= 0; i--) {
            bits = Hw.mux(grant.get(i), in.get(i).bits(), bits);
            idx = Hw.mux(grant.get(i), UInt.lit(i, iw), idx);
            any = any.or(grant.get(i));
        }
        out.valid().set(any);
        Hw.assign(out.bits(), bits);
        chosen.set(idx);
        for (int i = 0; i < n; i++) in.get(i).ready().set(grant.get(i).and(out.ready()));
        if (last != null) {
            UInt chosenIdx = idx;
            when(out.fire(), () -> last.set(chosenIdx));
        }
    }
}
