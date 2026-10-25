package org.eninehq.tessera.syntax;

import static org.eninehq.tessera.core.Hw.when;

import org.eninehq.tessera.core.Bool;
import org.eninehq.tessera.core.Module;
import org.eninehq.tessera.core.UInt;

/** Euclid's GCD written with operators, slices, concatenation and trailing blocks. */
public class Gcd extends Module {
    public Gcd() {
        UInt a = input("a", UInt.w(16)), b = input("b", UInt.w(16));
        Bool start = input("start", Bool.TYPE);
        UInt x = reg(UInt.w(16)), y = reg(UInt.w(16));
        Bool busy = reg(Bool.TYPE, 0);
        when (start) {
            x.set(a);
            y.set(b);
            busy.set(true);
        }.elseWhen (busy & y.ne(0)) {
            when (x > y) {
                x.set(x.subWrap(y));
            }.otherwise {
                y.set(y.subWrap(x));
            }
        }.otherwise {
            busy.set(false);
        }
        UInt result = x | y;
        output("result", UInt.w(16)).set(result);
        output("done", Bool.TYPE).set(~busy & ~start);
        output("hi", UInt.w(8)).set(result[15:8]);
        output("swapped", UInt.w(16)).set({result[7:0], result[15:8]});
        output("odd", Bool.TYPE).set(result[0]);
    }
}
