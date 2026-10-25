package org.eninehq.tessera.notation;

import static org.eninehq.tessera.core.Hw.when;

import org.eninehq.tessera.core.Bool;
import org.eninehq.tessera.core.Module;
import org.eninehq.tessera.core.UInt;

/** Tessera-javac notation: Euclid's GCD by repeated subtraction. */
public class Gcd extends Module {
    public Gcd() {
        UInt a = input("a", UInt.w(16)), b = input("b", UInt.w(16));
        Bool start = input("start", Bool.TYPE);
        UInt x = reg("x", UInt.w(16), 0), y = reg("y", UInt.w(16), 0);
        Bool busy = reg("busy", Bool.TYPE, 0);
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
        output("result", UInt.w(16)).set(x | y);
        output("done", Bool.TYPE).set(~busy & ~start);
    }
}
