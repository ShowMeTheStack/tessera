package org.eninehq.tessera.stdlib;

import static org.eninehq.tessera.core.Hw.when;

import java.time.Duration;
import org.eninehq.tessera.core.Bool;
import org.eninehq.tessera.core.Hw;
import org.eninehq.tessera.core.UInt;

/** Time-based building blocks: the cycle arithmetic happens in Java, at elaboration time. */
public final class Timing {
    private Timing() {}

    /** True for one cycle every {@code period}. */
    public static Bool every(Clock clk, Duration period) {
        long n = clk.cycles(period);
        if (n == 1) return Bool.of(true);
        int w = 64 - Long.numberOfLeadingZeros(n - 1);
        UInt c = Hw.register("every", UInt.w(w), 0L);
        Bool wrap = c.eq(n - 1).named("tick");
        c.set(Hw.mux(wrap, UInt.lit(0, w), c.addWrap(1)));
        return wrap;
    }

    /** A signal that toggles every {@code halfPeriod}: an LED blinking with period {@code 2 * halfPeriod}. */
    public static Bool blink(Clock clk, Duration halfPeriod) {
        Bool tick = every(clk, halfPeriod);
        Bool led = Hw.register("blink", Bool.TYPE, 0L);
        when(tick, () -> led.set(led.not()));
        return led;
    }

    /** True for one cycle every period of {@code frequencyHz} (for example a UART bit clock). */
    public static Bool rate(Clock clk, double frequencyHz) {
        long n = clk.cyclesPer(frequencyHz, 0.02);
        if (n == 1) return Bool.of(true);
        int w = 64 - Long.numberOfLeadingZeros(n - 1);
        UInt c = Hw.register("rate", UInt.w(w), 0L);
        Bool wrap = c.eq(n - 1);
        c.set(Hw.mux(wrap, UInt.lit(0, w), c.addWrap(1)));
        return wrap;
    }

    /** {@code in} delayed by about {@code d} (a shift register of registers, for short delays). */
    public static Bool after(Clock clk, Duration d, Bool in) {
        return Hw.past(in, (int) Math.min(clk.cycles(d), 4096));
    }

    /** Debounce a button: the output changes only after the input was stable for {@code d}. */
    public static Bool debounce(Clock clk, Duration d, Bool in) {
        return Util.debounce(Hw.synchronize(in, 2), (int) Math.min(clk.cycles(d), Integer.MAX_VALUE - 1));
    }
}
