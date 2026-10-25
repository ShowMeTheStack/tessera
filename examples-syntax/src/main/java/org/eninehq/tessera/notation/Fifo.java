package org.eninehq.tessera.notation;

import static org.eninehq.tessera.core.Hw.when;

import org.eninehq.tessera.core.Bool;
import org.eninehq.tessera.core.Mem;
import org.eninehq.tessera.core.Module;
import org.eninehq.tessera.core.UInt;

/** Tessera-javac notation: an 8-entry, 8-bit FIFO with ready/valid on both sides. */
public class Fifo extends Module {
    public Fifo() {
        Bool inValid = input("in_valid", Bool.TYPE), outReady = input("out_ready", Bool.TYPE);
        UInt inData = input("in_data", UInt.w(8));
        Mem<UInt> mem = mem("mem", UInt.w(8), 8);
        UInt wp = reg("wp", UInt.w(3), 0), rp = reg("rp", UInt.w(3), 0);
        UInt count = reg("count", UInt.w(4), 0);
        Bool push = inValid & count.ne(8), pop = outReady & count.ne(0);
        mem.write(wp, inData, push);
        when (push) { wp.set(wp.addWrap(1)); }
        when (pop) { rp.set(rp.addWrap(1)); }
        when (push & ~pop) {
            count.set(count.addWrap(1));
        }.elseWhen (pop & ~push) {
            count.set(count.subWrap(1));
        }
        output("in_ready", Bool.TYPE).set(count.ne(8));
        output("out_valid", Bool.TYPE).set(count.ne(0));
        output("out_data", UInt.w(8)).set(mem.read(rp));
    }
}
