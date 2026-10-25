package org.eninehq.tessera.examples;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Random;
import java.util.function.Supplier;
import org.eninehq.tessera.core.Module;
import org.eninehq.tessera.core.Tessera;
import org.eninehq.tessera.ir.Signal;
import org.eninehq.tessera.sim.Sim;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** The compiled simulator must agree with the interpreter on every named signal, every cycle. */
class BackendEquivalenceTest {

    static List<Arguments> designs() {
        return List.of(
                Arguments.of("OpZoo", (Supplier<Module>) OpZoo::new),
                Arguments.of("Alu", (Supplier<Module>) () -> new Alu(32)),
                Arguments.of("UartTx", (Supplier<Module>) () -> new UartTx(3)),
                Arguments.of("Counter", (Supplier<Module>) () -> new Counter(8)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("designs")
    void compiledMatchesInterpreter(String name, Supplier<Module> design) {
        var e = Tessera.elaborate(design);
        var inputs = e.circuit().top().ports().stream()
                .filter(p -> p.kind() == Signal.Kind.INPUT && !p.name().equals("clock")).toList();
        var rnd = new Random(7);
        try (var a = Sim.create(e, Sim.Backend.INTERPRETER); var b = Sim.create(e, Sim.Backend.COMPILED)) {
            var paths = a.netlist().signalsByPath().keySet().stream().filter(p -> !p.equals("clock")).toList();
            for (int cyc = 0; cyc < 2000; cyc++) {
                for (var p : inputs) {
                    long v = p.name().equals("reset") ? (cyc < 2 ? 1 : 0) : rnd.nextLong();
                    if (p.name().equals("op")) v = Math.floorMod(v, Alu.Op.values().length);
                    a.poke(p.name(), v);
                    b.poke(p.name(), v);
                }
                for (var path : paths) assertEquals(a.peek(path), b.peek(path), "cycle " + cyc + " signal " + path);
                a.step();
                b.step();
            }
        }
    }
}
