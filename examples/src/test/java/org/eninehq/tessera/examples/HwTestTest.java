package org.eninehq.tessera.examples;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.eninehq.tessera.test.Design;
import org.eninehq.tessera.test.HwSim;
import org.eninehq.tessera.test.HwTest;

/** The {@code @HwTest} JUnit extension: injected simulators, waveforms and Verilator replay. */
class HwTestTest {

    @Design
    Counter counter() {
        return new Counter(8);
    }

    @Design
    Alu alu() {
        return new Alu(32);
    }

    @HwTest(trace = true)
    void countsAfterReset(HwSim<Counter> sim) {
        sim.reset(2);
        sim.poke(sim.dut().en, 1);
        sim.step(5);
        assertEquals(5, sim.peek(sim.dut().count));
        assertTrue(Files.exists(Path.of("build", "tessera-waves", "HwTestTest.countsAfterReset.vcd")));
    }

    @HwTest(crossCheck = true)
    void replayedOnVerilator(HwSim<Alu> sim) {
        for (int i = 0; i < 50; i++) {
            sim.poke(sim.dut().a, i * 7919L);
            sim.poke(sim.dut().b, i * 104729L);
            sim.poke(sim.dut().op, i % Alu.Op.values().length);
            sim.step();
        }
    }

}
