package org.eninehq.tessera.examples;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Random;
import java.util.function.Supplier;
import org.eninehq.tessera.core.Module;
import org.eninehq.tessera.core.Tessera;
import org.eninehq.tessera.ir.Signal;
import org.eninehq.tessera.sim.BatchSim;
import org.eninehq.tessera.sim.Sim;
import org.eninehq.tessera.sim.Simulator;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** 64 lanes of the bit-sliced simulator agree with 64 separate interpreter runs, signal by signal. */
class BatchSimTest {

    static List<Arguments> designs() {
        return List.of(
                Arguments.of("OpZoo", (Supplier<Module>) OpZoo::new),
                Arguments.of("Alu", (Supplier<Module>) () -> new Alu(32)),
                Arguments.of("UartTx", (Supplier<Module>) () -> new UartTx(3)),
                Arguments.of("Counter", (Supplier<Module>) () -> new Counter(8)),
                Arguments.of("Chain", (Supplier<Module>) AggregatesTest.Chain::new),
                Arguments.of("VecFile", (Supplier<Module>) AggregatesTest.VecFile::new));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("designs")
    void lanesMatchIndependentSimulations(String name, Supplier<Module> design) {
        var e = Tessera.elaborate(design);
        var batch = new BatchSim(e.circuit());
        var inputs = e.circuit().top().ports().stream()
                .filter(p -> p.kind() == Signal.Kind.INPUT && !p.name().equals("clock")).toList();
        int lanes = BatchSim.LANES;
        Simulator[] ref = new Simulator[lanes];
        for (int l = 0; l < lanes; l++) ref[l] = Sim.create(e, Sim.Backend.INTERPRETER);
        var paths = ref[0].netlist().signalsByPath().entrySet().stream()
                .filter(x -> !x.getKey().equals("clock") && ref[0].netlist().slotOf(x.getValue()) >= 0 && x.getValue().width() <= 64)
                .map(java.util.Map.Entry::getKey).toList();
        var rnd = new Random(name.hashCode());
        try {
            for (int cyc = 0; cyc < 150; cyc++) {
                for (int l = 0; l < lanes; l++)
                    for (var p : inputs) {
                        long v = p.name().equals("reset") ? (cyc < 2 ? 1 : (rnd.nextInt(50) == 0 ? 1 : 0)) : rnd.nextLong();
                        if (p.name().equals("op")) v = Math.floorMod(v, Alu.Op.values().length);
                        v &= p.width() >= 64 ? -1L : (1L << p.width()) - 1;
                        batch.poke(p.name(), l, v);
                        ref[l].poke(p.name(), v);
                    }
                for (int l = 0; l < lanes; l++)
                    for (var p : paths) assertEquals(ref[l].peek(p), batch.peek(p, l), "cycle " + cyc + " lane " + l + " " + p);
                batch.step();
                for (var s : ref) s.step();
            }
        } finally {
            for (var s : ref) s.close();
        }
    }
}
