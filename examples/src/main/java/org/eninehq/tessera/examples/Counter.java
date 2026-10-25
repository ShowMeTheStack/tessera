package org.eninehq.tessera.examples;

import static org.eninehq.tessera.core.Hw.when;

import org.eninehq.tessera.core.Bool;
import org.eninehq.tessera.core.Module;
import org.eninehq.tessera.core.UInt;

/** A counter that increments while {@code en} is high and wraps around. */
public class Counter extends Module {
    public final Bool en = input("en", Bool.TYPE);
    public final UInt count;

    public Counter(int width) {
        count = output("count", UInt.w(width));
        UInt value = reg("value", UInt.w(width), 0);
        when(en, () -> value.set(value.addWrap(1)));
        count.set(value);
    }
}
