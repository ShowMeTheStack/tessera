package org.eninehq.tessera.examples;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import jdk.jfr.consumer.RecordingFile;
import org.eninehq.tessera.core.Module;
import org.eninehq.tessera.core.Tessera;
import org.eninehq.tessera.core.UInt;
import org.eninehq.tessera.sim.HotSwap;
import org.eninehq.tessera.sim.JfrRecorder;
import org.eninehq.tessera.sim.Sim;
import org.eninehq.tessera.sim.TimeTravel;
import org.junit.jupiter.api.Test;

/** Time-travel debugging, JFR events and hot swapping a running design. */
class DebuggingTest {

    /** An accumulator whose inputs change every cycle. */
    static class Acc extends Module {
        Acc(int step) {
            UInt in = input("in", UInt.w(16));
            UInt acc = reg("acc", UInt.w(16), 0);
            UInt n = reg("n", UInt.w(8), 0);
            acc.set(acc.addWrap(in).addWrap(step));
            n.set(n.addWrap(1));
            output("out", UInt.w(16)).set(acc);
        }
    }

    @Test
    void goBackInTimeAndReplay() {
        var e = Tessera.elaborate(() -> new Acc(0));
        try (var sim = Sim.create(e)) {
            sim.reset(1);
            var tt = new TimeTravel(sim, 16);
            var rnd = new Random(5);
            long[] seen = new long[300];
            for (int c = 0; c < 300; c++) {
                tt.poke("in", rnd.nextInt(1000));
                seen[c] = sim.peek("out");
                tt.step();
            }
            long start = 1; // the recording started after one reset cycle
            for (int c : new int[]{5, 100, 137, 250, 17, 299}) {
                tt.goTo(start + c);
                assertEquals(seen[c], sim.peek("out"), "cycle " + c);
            }
            var h = tt.history("out", start + 40, start + 60);
            for (int i = 0; i < 20; i++) assertEquals(BigInteger.valueOf(seen[40 + i]), h.get(i));
            assertTrue(tt.snapshotCount() <= 300 / 16 + 2);
        }
    }

    @Test
    void hotSwapKeepsState() {
        var v1 = Tessera.elaborate(() -> new Acc(0));
        var sim = Sim.create(v1);
        sim.reset(1);
        sim.poke("in", 10);
        sim.step(50);
        assertEquals(500, sim.peek("out"));
        var swapped = HotSwap.swap(sim, Tessera.elaborate(() -> new Acc(1)).circuit(), Sim.Backend.COMPILED);
        try (var s2 = swapped.sim()) {
            assertEquals(2, swapped.report().registers());
            assertEquals(500, s2.peek("out"), "state carried over, no reset");
            s2.poke("in", 10);
            s2.step(10);
            assertEquals(610, s2.peek("out"), "new behaviour: +11 per cycle");
        }
    }

    @Test
    void cyclesShowUpInFlightRecordings() throws Exception {
        var e = Tessera.elaborate(() -> new Acc(0));
        Path file = Files.createTempFile("tessera", ".jfr");
        try (var rec = new jdk.jfr.Recording()) {
            rec.enable("tessera.Cycle");
            rec.start();
            try (var sim = Sim.create(e)) {
                JfrRecorder.attach(sim, List.of("acc", "n"));
                sim.reset(1);
                sim.poke("in", 3);
                sim.step(20);
            }
            rec.stop();
            rec.dump(file);
        }
        var events = RecordingFile.readAllEvents(file).stream().filter(ev -> ev.getEventType().getName().equals("tessera.Cycle")).toList();
        assertEquals(21, events.size());
        assertTrue(events.getLast().getString("values").contains("acc=0x39"), events.getLast().getString("values"));
    }

}
