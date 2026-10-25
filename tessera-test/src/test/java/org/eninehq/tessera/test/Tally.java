package org.eninehq.tessera.test;

import static org.eninehq.tessera.core.Hw.when;

import org.eninehq.tessera.core.Bool;
import org.eninehq.tessera.core.Module;
import org.eninehq.tessera.core.UInt;

/** A counter that starts at {@code start} and counts while {@code en} is high: the design the testbench tests run. */
public class Tally extends Module {
    public final Bool en = input("en", Bool.TYPE);
    public final UInt out = output("value", UInt.w(8));

    public Tally() {
        this(5);
    }

    public Tally(int start) {
        UInt v = reg("v", UInt.w(8), start);
        when(en, () -> v.set(v.addWrap(1)));
        out.set(v);
    }
}
