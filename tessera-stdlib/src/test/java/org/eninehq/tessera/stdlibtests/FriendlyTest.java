package org.eninehq.tessera.stdlibtests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Map;
import org.eninehq.tessera.core.Bool;
import org.eninehq.tessera.core.Module;
import org.eninehq.tessera.core.Tessera;
import org.eninehq.tessera.core.TesseraException;
import org.eninehq.tessera.sim.Sim;
import org.eninehq.tessera.stdlib.Clock;
import org.eninehq.tessera.stdlib.Timing;
import org.eninehq.tessera.stdlib.TruthTable;
import org.junit.jupiter.api.Test;

/** Time units and truth tables: hardware without counting cycles or writing logic equations. */
class FriendlyTest {

    static class Blinky extends Module {
        Blinky(Clock clk) {
            output("led", Bool.TYPE).set(Timing.blink(clk, Duration.ofMillis(500)));
        }
    }

    @Test
    void blinkInRealTime() {
        var clk = Clock.khz(10); // 10 kHz: 5000 cycles per half period
        var e = Tessera.elaborate(() -> new Blinky(clk));
        try (var sim = Sim.create(e, Sim.Backend.COMPILED)) {
            sim.reset(1);
            int toggles = 0;
            long last = sim.peek("led");
            for (int t = 0; t < 30_000; t++) {
                sim.step();
                if (sim.peek("led") != last) {
                    toggles++;
                    last = sim.peek("led");
                }
            }
            assertEquals(6, toggles, "3 seconds of simulated time, toggling every 0.5 s");
        }
    }

    @Test
    void impossibleRatesAreRejected() {
        var ex = assertThrows(IllegalArgumentException.class, () -> Clock.khz(10).cyclesPer(3_000, 0.02));
        assertTrue(ex.getMessage().contains("cannot make 3000 Hz"), ex.getMessage());
        assertEquals(868, Clock.mhz(100).cyclesPer(115_200, 0.02));
    }

    static final String FULL_ADDER = """
            a b cin | sum cout
            0 0 0   | 0   0
            0 0 1   | 1   0
            0 1 0   | 1   0
            0 1 1   | 0   1
            1 0 0   | 1   0
            1 0 1   | 0   1
            1 1 0   | 0   1
            1 1 1   | 1   1
            """;

    static class Adder extends Module {
        Adder() {
            var tt = TruthTable.parse(FULL_ADDER);
            var out = tt.apply(Map.of("a", input("a", Bool.TYPE), "b", input("b", Bool.TYPE), "cin", input("cin", Bool.TYPE)));
            output("sum", Bool.TYPE).set(out.get("sum"));
            output("cout", Bool.TYPE).set(out.get("cout"));
        }
    }

    @Test
    void truthTableBecomesLogic() {
        var e = Tessera.elaborate(Adder::new);
        try (var sim = Sim.create(e)) {
            for (int v = 0; v < 8; v++) {
                sim.poke("a", v >> 2 & 1);
                sim.poke("b", v >> 1 & 1);
                sim.poke("cin", v & 1);
                int total = (v >> 2 & 1) + (v >> 1 & 1) + (v & 1);
                assertEquals(total & 1, sim.peek("sum"));
                assertEquals(total >> 1, sim.peek("cout"));
            }
        }
    }

    @Test
    void conflictingRowsAreReported() {
        var ex = assertThrows(TesseraException.class, () -> TruthTable.parse("""
                a b | y
                1 - | 1
                - 0 | 0
                """));
        assertTrue(ex.getMessage().contains("lines 2 and 3 match the same inputs"), ex.getMessage());
        var dontCare = TruthTable.parse("""
                sel a b | y
                0   - 1 | 1
                1   1 - | 1
                """);
        assertEquals(true, dontCare.evaluate(Map.of("sel", false, "a", false, "b", true)).get("y"));
        assertEquals(false, dontCare.evaluate(Map.of("sel", true, "a", false, "b", true)).get("y"));
    }
}
