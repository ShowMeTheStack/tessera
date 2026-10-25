package org.eninehq.tessera.examples;

import org.eninehq.tessera.core.Bool;
import org.eninehq.tessera.core.Module;
import org.eninehq.tessera.core.UInt;

/** Blinks an LED: the top bit of a free-running counter, so it toggles every 2^(bits-1) cycles. */
public class Blinky extends Module {
    public final Bool led = output("led", Bool.TYPE);

    public Blinky(int bits) {
        UInt ticks = reg("ticks", UInt.w(bits), 0);
        ticks.set(ticks.addWrap(1));
        led.set(ticks.bit(bits - 1));
    }
}
