package org.eninehq.tessera.examples;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.eninehq.tessera.core.Tessera;
import org.eninehq.tessera.sim.Sim;
import org.junit.jupiter.api.Test;

class CounterTest {
    @Test
    void countsWhileEnabledAndWraps() {
        var e = Tessera.elaborate(() -> new Counter(4));
        var c = e.top();
        try (var sim = Sim.create(e)) {
            sim.reset(1);
            assertEquals(0, sim.peek(c.count));
            sim.poke(c.en, 1);
            sim.step(5);
            assertEquals(5, sim.peek(c.count));
            sim.poke(c.en, 0);
            sim.step(3);
            assertEquals(5, sim.peek(c.count));
            sim.poke(c.en, 1);
            sim.step(11);
            assertEquals(0, sim.peek(c.count), "wraps at 16");
        }
    }

    @Test
    void blinkyToggles() {
        var e = Tessera.elaborate(() -> new Blinky(3));
        try (var sim = Sim.create(e)) {
            sim.reset(1);
            var seen = new StringBuilder();
            for (int i = 0; i < 16; i++) {
                seen.append(sim.peek(e.top().led));
                sim.step();
            }
            assertEquals("0000111100001111", seen.toString());
        }
    }
}
