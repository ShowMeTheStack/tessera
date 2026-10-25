package org.eninehq.tessera.studio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import org.eninehq.tessera.studio.CodeGenTest.D;
import org.junit.jupiter.api.Test;

/** Drawings with several clocks: domain parts, withClock in the Java, the run's ticks, and the CDC check. */
class MultiClockTest {
    /**
     * Two counters: count on the design's clock (every 3 ticks), fcount in the domain fast (every tick).
     * With cross, a register on the design's clock samples fcount's low bit, through a synchroniser or not.
     */
    static D twoCounters(boolean cross, boolean sync) {
        var d = new D("TwoClocks")
                .part("c", "domain", "name", "clock", "period", 3, "phase", 0)
                .part("f", "domain", "name", "fast", "period", 1, "phase", 0)
                .part("rs", "reg", "name", "count", "width", 8, "init", "0")
                .part("ks", "const", "value", "1", "width", 8)
                .part("as", "add", "mode", "wrap")
                .part("rf", "reg", "name", "fcount", "width", 8, "init", "0", "domain", "fast")
                .part("kf", "const", "value", "1", "width", 8)
                .part("af", "add", "mode", "wrap")
                .part("os", "out", "name", "slow_out", "width", 8)
                .part("of", "out", "name", "fast_out", "width", 8)
                .net("n1", "rs.q", "as.a", "os.a").net("n2", "ks.y", "as.b").net("n3", "as.y", "rs.d")
                .net(cross ? "m0" : "m1", cross ? new String[]{"rf.q", "af.a", "of.a", "sl.a"} : new String[]{"rf.q", "af.a", "of.a"})
                .net("m2", "kf.y", "af.b").net("m3", "af.y", "rf.d");
        if (cross) {
            d.part("sl", "slice", "hi", 0, "lo", 0).part("rr", "reg", "name", "sampled", "width", 1, "init", "0").part("o4", "out", "name", "sampled_out", "width", 1)
                    .net("x3", "rr.q", "o4.a");
            if (sync) d.part("sy", "sync", "stages", 2).net("y1", "sl.y", "sy.a").net("y2", "sy.y", "rr.d");
            else d.net("x2", "sl.y", "rr.d");
        }
        return d;
    }

    static long last(Session s, String path) {
        @SuppressWarnings("unchecked")
        var w = (Map<String, Object>) ((Map<String, Object>) s.waves(Map.of("paths", List.of(path), "from", 0, "to", 1_000_000, "columns", 1_000_000)).get("waves")).get(path);
        var ch = (List<?>) w.get("changes");
        return new BigInteger(String.valueOf(((List<?>) ch.getLast()).get(1)), 16).longValue();
    }

    @Test
    void domainsGenerateWithClockAndTickAtTheirOwnPeriods() {
        var d = twoCounters(false, false);
        var g = CodeGen.generate(d.doc);
        assertTrue(g.ok(), g.problems() + "\n" + g.java());
        assertTrue(g.java().contains("ClockDomain fast = clockDomain(\"fast\");"), g.java());
        assertTrue(g.java().contains("UInt fcount = withClock(fast, () -> reg(\"fcount\", UInt.w(8), 0));"), g.java());
        var s = new Session(new Elaborator(List.of(), 1000));
        var r = s.load(d.doc);
        assertEquals(true, r.get("elaborated"), r.toString());
        var clocks = String.valueOf(r.get("clocks"));
        assertTrue(clocks.contains("port=clock") && clocks.contains("period=3") && clocks.contains("port=fast_clock") && clocks.contains("period=1"), clocks);
        long f0 = last(s, "fast_out"), s0 = last(s, "slow_out");
        s.command(Map.of("cmd", "step", "n", 30));
        // 30 ticks: the fast clock has 30 edges, the design's clock 10
        assertEquals(30, last(s, "fast_out") - f0);
        assertEquals(10, last(s, "slow_out") - s0);
        // to the next edge of the design's clock: at most 3 ticks, and the slow counter moves by exactly one
        long c0 = ((Number) s.command(Map.of("cmd", "step", "n", 0)).get("cycle")).longValue(), sl = last(s, "slow_out");
        var after = s.command(Map.of("cmd", "step", "edge", "clock"));
        long c1 = ((Number) after.get("cycle")).longValue();
        assertTrue(c1 - c0 >= 1 && c1 - c0 <= 3, c0 + " → " + c1);
        assertEquals(sl + 1, last(s, "slow_out"));
        // going back replays the same edges
        long fNow = last(s, "fast_out");
        s.command(Map.of("cmd", "goto", "cycle", c0));
        s.command(Map.of("cmd", "goto", "cycle", c1));
        assertEquals(fNow, last(s, "fast_out"));
    }

    @Test
    void aCrossingNeedsASynchroniser() {
        var r = new Session(new Elaborator(List.of(), 1000)).load(twoCounters(true, false).doc);
        assertEquals(false, r.get("elaborated"), "an unsynchronised crossing must not elaborate: " + r);
        assertTrue(String.valueOf(r.get("problems")).contains("samples logic clocked by fast_clock"), String.valueOf(r.get("problems")));
        var fixed = twoCounters(true, true);
        var g = CodeGen.generate(fixed.doc);
        assertTrue(g.java().contains("Bool synced = Hw.synchronize("), g.java());
        var r2 = new Session(new Elaborator(List.of(), 1000)).load(fixed.doc);
        assertEquals(true, r2.get("elaborated"), r2 + "\n" + g.java());
    }
}
