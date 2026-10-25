package org.eninehq.tessera.riscv;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.eninehq.tessera.core.Elaborated;
import org.eninehq.tessera.core.Tessera;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** R3/R4: both cores pass riscv-tests and retire exactly what the golden ISS retires. */
class CoreTest {
    static final int MEM = RiscvTests.MEM_WORDS;
    static final Elaborated<SingleCycleCore> SINGLE = Tessera.elaborate(() -> new SingleCycleCore(MEM, null));
    static final Elaborated<FiveStageCore> FIVE = Tessera.elaborate(() -> new FiveStageCore(MEM, null));
    static long fiveCycles, fiveRetired;

    static List<String> tests() {
        return RiscvTests.names();
    }

    @ParameterizedTest(name = "single-cycle {0}")
    @MethodSource("tests")
    void singleCycle(String name) {
        try (var sim = CoreRunner.sim(SINGLE)) {
            var r = CoreRunner.run(SINGLE, sim, RiscvTests.image(name), 100_000);
            assertEquals("pass", RiscvTests.verdict(r.gp()), name);
            assertEquals(r.retired(), r.cycles(), "single-cycle: one instruction per cycle");
        }
    }

    @ParameterizedTest(name = "five-stage {0}")
    @MethodSource("tests")
    void fiveStage(String name) {
        try (var sim = CoreRunner.sim(FIVE)) {
            var r = CoreRunner.run(FIVE, sim, RiscvTests.image(name), 100_000);
            assertEquals("pass", RiscvTests.verdict(r.gp()), name);
            synchronized (CoreTest.class) {
                fiveCycles += r.cycles();
                fiveRetired += r.retired();
            }
        }
    }

    @AfterAll
    static void report() {
        if (fiveRetired > 0)
            System.out.printf("five-stage core: %d instructions in %d cycles, CPI %.2f%n", fiveRetired, fiveCycles,
                    (double) fiveCycles / fiveRetired);
    }
}
