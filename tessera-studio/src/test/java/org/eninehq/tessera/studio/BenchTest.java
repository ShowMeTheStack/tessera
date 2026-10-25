package org.eninehq.tessera.studio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** A JUnit-style testbench as stimulus: its tests drive the live run, which records what they do. */
class BenchTest {
    static final String BENCH = """
            import org.eninehq.tessera.sim.Simulator;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertEquals;

            class CounterBench {
                @Test
                void countsWhileEnabled(Simulator sim) {
                    long c0 = sim.peek("value");
                    sim.poke("en", 1);
                    sim.step(10);
                    assertEquals(c0 + 10, sim.peek("value"));
                }

                @Test
                void holdsWhenDisabled(Simulator sim) {
                    sim.poke("en", 0);
                    long v = sim.peek("value");
                    sim.step(5);
                    assertEquals(v + 1, sim.peek("value"), "a wrong expectation, to see a failure");
                }
            }
            """;

    @Test
    void testsDriveTheRunAndReportTheirLines() {
        var s = new Session(new Elaborator(List.of(), 1000));
        assertEquals(true, s.load(CodeGenTest.counter().doc).get("elaborated"));
        long c0 = ((Number) s.command(Map.of("cmd", "step", "n", 0)).get("cycle")).longValue();
        var r = s.bench(Map.of("source", BENCH));
        assertEquals(true, r.get("ok"), r.toString());
        @SuppressWarnings("unchecked") var res = (List<Map<String, Object>>) r.get("results");
        assertEquals(2, res.size());
        assertEquals("countsWhileEnabled", res.get(0).get("test"));
        assertEquals(true, res.get(0).get("ok"), res.toString());
        assertEquals(10L, (long) res.get(0).get("to") - (long) res.get(0).get("from"));
        assertEquals(false, res.get(1).get("ok"));
        assertTrue(String.valueOf(res.get(1).get("message")).startsWith("a wrong expectation"), res.toString());
        assertEquals(19, res.get(1).get("line"), "the failing assertEquals is on line 19 of the bench");
        // the run went on by 15 cycles, and the waves have the counter's values through them
        long c1 = ((Number) s.command(Map.of("cmd", "step", "n", 0)).get("cycle")).longValue();
        assertEquals(c0 + 15, c1);
        // a bench that does not compile says where
        var bad = s.bench(Map.of("source", BENCH.replace("sim.step(10);", "sim.stepp(10);")));
        assertEquals(false, bad.get("ok"));
        assertTrue(String.valueOf(bad.get("errors")).contains("line 10"), String.valueOf(bad.get("errors")));
    }
}
