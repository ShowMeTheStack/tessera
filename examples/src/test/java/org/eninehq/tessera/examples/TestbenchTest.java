package org.eninehq.tessera.examples;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.eninehq.tessera.core.Tessera;
import org.eninehq.tessera.sim.Sim;
import org.eninehq.tessera.test.Testbench;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TestbenchTest {
    static final int CPB = 3;

    static int receive(Testbench tb, UartTx u) {
        while (tb.peek(u.tx) == 1) tb.step();
        tb.step(CPB / 2);
        int value = 0;
        for (int i = 0; i < 8; i++) {
            tb.step(CPB);
            value |= (int) tb.peek(u.tx) << i;
        }
        tb.step(CPB);
        assertEquals(1, tb.peek(u.tx), "stop bit");
        return value;
    }

    @Test
    void driverAndMonitorRunConcurrently(@TempDir Path dir) throws Exception {
        var e = Tessera.elaborate(() -> new UartTx(CPB));
        var u = e.top();
        var bytes = List.of(0x12, 0x34, 0xFE, 0x00, 0x81);
        var received = new ArrayList<Integer>();
        Path vcd = dir.resolve("uart.vcd");
        try (var sim = Sim.create(e); var tb = new Testbench(sim)) {
            sim.trace(vcd);
            sim.reset(1);
            tb.fork("driver", () -> {
                for (int b : bytes) {
                    while (tb.peek(u.ready) == 0) tb.step();
                    tb.poke(u.data, b);
                    tb.poke(u.valid, 1);
                    tb.step();
                    tb.poke(u.valid, 0);
                }
            });
            tb.fork("monitor", () -> {
                for (int i = 0; i < bytes.size(); i++) received.add(receive(tb, u));
            });
            tb.stepUntil(() -> received.size() == bytes.size(), 10_000);
        }
        assertEquals(bytes, received);
        String text = Files.readString(vcd);
        assertTrue(text.contains("$var wire 1") && text.contains(" tx $end") && text.contains("#10"), "VCD content");
    }

    @Test
    void processFailuresFailTheTest() {
        var e = Tessera.elaborate(() -> new Counter(4));
        try (var sim = Sim.create(e); var tb = new Testbench(sim)) {
            tb.fork("checker", () -> {
                tb.step(2);
                assertEquals(99, tb.peek(e.top().count));
            });
            assertThrows(AssertionError.class, () -> tb.step(5));
        }
    }

    @Test
    void orderIsDeterministic() {
        var e = Tessera.elaborate(() -> new Counter(4));
        var log = new ArrayList<String>();
        try (var sim = Sim.create(e); var tb = new Testbench(sim)) {
            tb.fork("a", () -> { for (int i = 0; i < 3; i++) { log.add("a" + tb.cycle()); tb.step(); } });
            tb.fork("b", () -> { for (int i = 0; i < 3; i++) { log.add("b" + tb.cycle()); tb.step(); } });
            tb.step(4);
        }
        assertEquals(List.of("a0", "b0", "a1", "b1", "a2", "b2"), log);
    }
}
