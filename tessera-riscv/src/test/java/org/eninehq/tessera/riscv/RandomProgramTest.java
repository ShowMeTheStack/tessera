package org.eninehq.tessera.riscv;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.stream.LongStream;
import org.eninehq.tessera.core.Elaborated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** Differential testing: random programs on each core, every retired instruction checked against the ISS. */
class RandomProgramTest {
    static final Elaborated<SingleCycleCore> SINGLE = CoreTest.SINGLE;
    static final Elaborated<FiveStageCore> FIVE = CoreTest.FIVE;

    static LongStream seeds() {
        return LongStream.range(1, 41);
    }

    private static void check(Elaborated<? extends RvCore> e, long seed) {
        int[] prog = RandomProgram.generate(seed, 400);
        var iss = new Rv32iIss(RiscvTests.MEM_WORDS, prog);
        assertTrue(iss.run(10_000), "program halts on the ISS");
        try (var sim = CoreRunner.sim(e)) {
            var r = CoreRunner.run(e, sim, prog, 20_000);
            assertTrue(r.retired() > 100, "retired " + r.retired());
        }
    }

    @ParameterizedTest(name = "seed {0}")
    @MethodSource("seeds")
    void singleCycle(long seed) {
        check(SINGLE, seed);
    }

    @ParameterizedTest(name = "seed {0}")
    @MethodSource("seeds")
    void fiveStage(long seed) {
        check(FIVE, seed);
    }
}
