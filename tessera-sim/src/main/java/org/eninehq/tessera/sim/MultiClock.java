package org.eninehq.tessera.sim;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Drives a simulator with several free-running clocks of different periods. Edges that
 * fall on the same time step happen together.
 *
 * <pre>{@code
 * var mc = new MultiClock(sim).clock("clock", 10).clock("fast_clock", 4);
 * mc.runUntil(1_000);
 * }</pre>
 */
public final class MultiClock {
    private final Simulator sim;
    private final Map<String, long[]> clocks = new LinkedHashMap<>(); // name -> {period, next edge}
    private long now;

    public MultiClock(Simulator sim) {
        this.sim = sim;
    }

    /** Add a clock with the given period (in arbitrary time units); its first edge is at {@code period}. */
    public MultiClock clock(String path, long period) {
        return clock(path, period, period);
    }

    public MultiClock clock(String path, long period, long firstEdge) {
        if (!sim.clocks().contains(path)) throw new IllegalArgumentException("no clock " + path + "; clocks are " + sim.clocks());
        if (period <= 0) throw new IllegalArgumentException("period must be positive");
        clocks.put(path, new long[]{period, firstEdge});
        return this;
    }

    public long now() { return now; }

    /** Advance to the next edge (or set of simultaneous edges); returns the clocks that ticked. */
    public List<String> next() {
        long t = Long.MAX_VALUE;
        for (var c : clocks.values()) t = Math.min(t, c[1]);
        var ticked = new ArrayList<String>();
        for (var e : clocks.entrySet())
            if (e.getValue()[1] == t) {
                ticked.add(e.getKey());
                e.getValue()[1] += e.getValue()[0];
            }
        now = t;
        sim.stepClocks(ticked);
        return ticked;
    }

    /** Run all edges up to and including time {@code t}. */
    public void runUntil(long t) {
        while (true) {
            long nextT = Long.MAX_VALUE;
            for (var c : clocks.values()) nextT = Math.min(nextT, c[1]);
            if (nextT > t) break;
            next();
        }
        now = t;
    }
}
