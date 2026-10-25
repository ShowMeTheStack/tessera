package org.eninehq.tessera.examples;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;
import org.eninehq.tessera.core.Module;
import org.eninehq.tessera.core.Tessera;
import org.eninehq.tessera.emit.SvEmitter;
import org.eninehq.tessera.test.Yosys;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Golden SystemVerilog snapshots (stable naming keeps diffs reviewable) and a Yosys synthesis
 * smoke test per example. Regenerate snapshots with {@code ./gradlew test -Ptessera.updateGolden=true}.
 */
class GoldenAndSynthTest {

    static List<Arguments> designs() {
        return List.of(
                Arguments.of("Counter", (Supplier<Module>) () -> new Counter(8)),
                Arguments.of("Blinky", (Supplier<Module>) () -> new Blinky(4)),
                Arguments.of("Alu", (Supplier<Module>) () -> new Alu(32)),
                Arguments.of("UartTx", (Supplier<Module>) () -> new UartTx(3)),
                Arguments.of("OpZoo", (Supplier<Module>) OpZoo::new),
                Arguments.of("Chain", (Supplier<Module>) AggregatesTest.Chain::new),
                Arguments.of("TwoClocks", (Supplier<Module>) ClockDomainTest.TwoClocks::new),
                Arguments.of("WideZoo", (Supplier<Module>) WideZoo::new));
    }

    static final Path GOLDEN = Path.of("src/test/resources/golden");

    @ParameterizedTest(name = "{0}")
    @MethodSource("designs")
    void matchesGoldenSv(String name, Supplier<Module> design) throws Exception {
        String sv = SvEmitter.emit(Tessera.elaborate(design));
        Path file = GOLDEN.resolve(name + ".sv");
        if (Boolean.getBoolean("tessera.updateGolden") || !Files.exists(file)) {
            Files.createDirectories(GOLDEN);
            Files.writeString(file, sv);
        }
        assertEquals(Files.readString(file), sv, "SystemVerilog for " + name + " changed; review the diff and "
                + "regenerate with -Ptessera.updateGolden=true if intended");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("designs")
    void synthesizesWithYosys(String name, Supplier<Module> design) throws Exception {
        assumeTrue(Yosys.available(), "yosys not installed");
        // WideZoo's 100x130-bit multiplier takes ABC many minutes; it is covered by Verilator instead
        assumeTrue(!name.equals("WideZoo"));
        var stats = Yosys.synth(Tessera.elaborate(design).circuit(), "synth-" + name);
        assertTrue(stats.cells() > 0, name + ": " + stats);
    }
}
