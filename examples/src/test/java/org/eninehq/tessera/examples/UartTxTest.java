package org.eninehq.tessera.examples;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.eninehq.tessera.core.Tessera;
import org.eninehq.tessera.sim.Sim;
import org.eninehq.tessera.sim.Simulator;
import org.junit.jupiter.api.Test;

class UartTxTest {
    static final int CPB = 4;

    /** Decode one 8N1 frame by sampling the middle of each bit. */
    static int receive(Simulator sim, UartTx u) {
        int guard = 0;
        while (sim.peek(u.tx) == 1) {
            sim.step();
            if (++guard > 1000) throw new AssertionError("no start bit");
        }
        sim.step(CPB / 2);
        assertEquals(0, sim.peek(u.tx), "start bit");
        int value = 0;
        for (int i = 0; i < 8; i++) {
            sim.step(CPB);
            value |= (int) sim.peek(u.tx) << i;
        }
        sim.step(CPB);
        assertEquals(1, sim.peek(u.tx), "stop bit");
        return value;
    }

    @Test
    void sendsBytes() {
        var e = Tessera.elaborate(() -> new UartTx(CPB));
        var u = e.top();
        try (var sim = Sim.create(e)) {
            sim.reset(1);
            List<Integer> got = new ArrayList<>();
            for (int b : new int[]{0x55, 0x00, 0xFF, 0xA7}) {
                while (sim.peek(u.ready) == 0) sim.step();
                sim.poke(u.data, b);
                sim.poke(u.valid, 1);
                sim.step();
                sim.poke(u.valid, 0);
                assertTrue(sim.peek(u.ready) == 0, "busy after accepting");
                got.add(receive(sim, u));
            }
            assertEquals(List.of(0x55, 0x00, 0xFF, 0xA7), got);
        }
    }
}
