package org.eninehq.tessera.examples;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.Supplier;
import org.eninehq.tessera.core.Module;
import org.eninehq.tessera.core.Tessera;
import org.eninehq.tessera.ir.Signal;
import org.eninehq.tessera.sim.Sim;
import org.eninehq.tessera.test.Verilator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@Tag("verilator")
class VerilatorCheckTest {

    @BeforeAll
    static void needsVerilator() {
        assumeTrue(Verilator.available(), "verilator not installed");
    }

    static List<Arguments> designs() {
        return List.of(
                Arguments.of("Counter", (Supplier<Module>) () -> new Counter(8)),
                Arguments.of("Blinky", (Supplier<Module>) () -> new Blinky(4)),
                Arguments.of("Alu", (Supplier<Module>) () -> new Alu(32)),
                Arguments.of("UartTx", (Supplier<Module>) () -> new UartTx(3)),
                Arguments.of("OpZoo", (Supplier<Module>) OpZoo::new),
                Arguments.of("AluFarm", (Supplier<Module>) () -> new SimBenchmarkTest.AluFarm(3)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("designs")
    void lintClean(String name, Supplier<Module> design) throws Exception {
        Verilator.lint(Tessera.elaborate(design).circuit(), "lint-" + name);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("designs")
    void matchesVerilator(String name, Supplier<Module> design) throws Exception {
        var e = Tessera.elaborate(design);
        var rnd = new Random(42);
        var inputs = e.circuit().top().ports().stream()
                .filter(p -> p.kind() == Signal.Kind.INPUT && !p.name().equals("clock")).toList();
        var stim = new ArrayList<long[]>();
        for (int cyc = 0; cyc < 400; cyc++) {
            long[] row = new long[inputs.size()];
            for (int i = 0; i < row.length; i++) {
                var p = inputs.get(i);
                row[i] = p.name().equals("reset") ? (cyc < 2 ? 1 : 0) : rnd.nextLong();
                if (p.name().equals("op")) row[i] = Math.floorMod(row[i], Alu.Op.values().length);
            }
            stim.add(row);
        }
        Verilator.crossCheck(e, "xcheck-" + name, stim, Sim.Backend.INTERPRETER);
    }
}
