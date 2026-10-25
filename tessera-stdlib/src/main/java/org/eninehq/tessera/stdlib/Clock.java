package org.eninehq.tessera.stdlib;

import java.time.Duration;

/**
 * A clock frequency, so designs can be written in real time units instead of cycle counts:
 * {@code Timing.blink(Clock.mhz(100), Duration.ofMillis(500))}. The same design then runs on
 * a 100 MHz board or, slowed down, in the playground.
 */
public record Clock(long hz) {
    public Clock {
        if (hz <= 0) throw new IllegalArgumentException("clock frequency must be positive");
    }

    public static Clock hz(long hz) { return new Clock(hz); }
    public static Clock khz(double khz) { return new Clock(Math.round(khz * 1e3)); }
    public static Clock mhz(double mhz) { return new Clock(Math.round(mhz * 1e6)); }

    /** Clock cycles in {@code d}, rounded to the nearest cycle (at least 1). */
    public long cycles(Duration d) {
        double c = d.toNanos() * (double) hz / 1e9;
        return Math.max(1, Math.round(c));
    }

    /** Cycles per period of {@code frequencyHz}, rounded (at least 1); fails if the error exceeds {@code tolerance}. */
    public long cyclesPer(double frequencyHz, double tolerance) {
        long c = Math.max(1, Math.round(hz / frequencyHz));
        double actual = (double) hz / c;
        double error = Math.abs(actual - frequencyHz) / frequencyHz;
        if (error > tolerance)
            throw new IllegalArgumentException(String.format("%s cannot make %.0f Hz within %.1f%% (closest: %.1f Hz)",
                    this, frequencyHz, tolerance * 100, actual));
        return c;
    }

    @Override public String toString() {
        if (hz % 1_000_000 == 0) return (hz / 1_000_000) + " MHz";
        if (hz % 1_000 == 0) return (hz / 1_000) + " kHz";
        return hz + " Hz";
    }
}
