package org.eninehq.tessera.examples;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.eninehq.tessera.core.Tessera;
import org.eninehq.tessera.sim.FstNative;
import org.eninehq.tessera.sim.Sim;
import org.junit.jupiter.api.Test;

/** FST output read back with the reference reader, and the Java type sidecar. */
class WaveformTest {

    @Test
    void fstRoundTripsThroughReferenceReader() throws Exception {
        assumeTrue(FstNative.available(), "FST library unavailable");
        var e = Tessera.elaborate(() -> new Alu(32));
        Path file = Path.of("build", "tessera-waves", "alu.fst");
        var rnd = new Random(2);
        var paths = new ArrayList<String>();
        var expected = new ArrayList<long[]>();
        try (var sim = Sim.create(e)) {
            for (var p : sim.netlist().signalsByPath().keySet()) if (!p.equals("clock") && sim.netlist().slotOf(sim.netlist().signalsByPath().get(p)) >= 0) paths.add(p);
            sim.trace(file);
            for (int t = 0; t < 30; t++) {
                sim.poke("a", rnd.nextInt());
                sim.poke("b", rnd.nextInt());
                sim.poke("op", t % Alu.Op.values().length);
                long[] row = new long[paths.size()];
                for (int i = 0; i < row.length; i++) row[i] = sim.peek(paths.get(i));
                expected.add(row);
                sim.step();
            }
        }
        assertTrue(Files.size(file) > 0);
        try (var r = new FstNative.Reader(file)) {
            assertEquals(paths.size() + 1, r.varCount());
            for (int t = 0; t < 30; t++)
                for (int i = 0; i < paths.size(); i++) {
                    String bits = r.valueAt(t * 10L, i + 2);
                    assertEquals(expected.get(t)[i], Long.parseUnsignedLong(bits, 2), "t=" + t + " " + paths.get(i));
                }
        }
        String types = Files.readString(Path.of("build", "tessera-waves", "alu.fst.types.json"));
        assertTrue(types.contains("\"op\": {\"width\": 4"), types);
        assertTrue(types.contains("\"literals\": [\"ADD\", \"SUB\", \"AND\", \"OR\", \"XOR\""), types);
        assertTrue(types.contains("Alu.java:"), types);
    }

    @Test
    void vcdStillDefault() throws Exception {
        var e = Tessera.elaborate(() -> new Counter(4));
        Path file = Path.of("build", "tessera-waves", "counter.vcd");
        try (var sim = Sim.create(e)) {
            sim.trace(file);
            sim.reset(1);
            sim.poke("en", 1);
            sim.step(4);
        }
        List<String> lines = Files.readAllLines(file);
        assertTrue(lines.getFirst().startsWith("$timescale"));
    }
}
