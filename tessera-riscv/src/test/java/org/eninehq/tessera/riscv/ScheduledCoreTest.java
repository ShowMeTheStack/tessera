package org.eninehq.tessera.riscv;

import static org.eninehq.tessera.pipeline.InOrderModel.READ_REGS;
import static org.eninehq.tessera.pipeline.InOrderModel.WRITE_REGS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.eninehq.tessera.core.Elaborated;
import org.eninehq.tessera.core.Tessera;
import org.eninehq.tessera.core.TesseraException;
import org.eninehq.tessera.pipeline.Schedule;
import org.eninehq.tessera.test.Verilator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** R5: one RV32I model, several schedules; every one passes riscv-tests and random programs. */
class ScheduledCoreTest {
    static final Map<String, Schedule> SCHEDULES = new LinkedHashMap<>();
    static final Map<String, Elaborated<ScheduledCore>> CORES = new LinkedHashMap<>();
    static final Map<String, long[]> STATS = new java.util.concurrent.ConcurrentHashMap<>();

    static {
        SCHEDULES.put("3-stage", ScheduledCore.THREE_STAGE);
        SCHEDULES.put("4-stage", ScheduledCore.FOUR_STAGE);
        SCHEDULES.put("5-stage", ScheduledCore.FIVE_STAGE);
        SCHEDULES.put("6-stage", ScheduledCore.SIX_STAGE);
        for (var e : SCHEDULES.entrySet())
            CORES.put(e.getKey(), Tessera.elaborate(() -> new ScheduledCore(RiscvTests.MEM_WORDS, null, e.getValue())));
        // block-RAM memories (synchronous reads), as on an FPGA
        CORES.put("5-stage-bram", Tessera.elaborate(() -> new ScheduledCore(RiscvTests.MEM_WORDS, null, ScheduledCore.FIVE_STAGE, false, true)));
        CORES.put("6-stage-bram", Tessera.elaborate(() -> new ScheduledCore(RiscvTests.MEM_WORDS, null, ScheduledCore.SIX_STAGE, false, true)));
        // multiplies finished later: in write-back (not forwarded) or in a stage of their own
        CORES.put("5-stage-mul-wb", Tessera.elaborate(() -> new ScheduledCore(RiscvTests.MEM_WORDS, null, ScheduledCore.FIVE_STAGE_MUL_WB)));
        CORES.put("5-stage-mul-mf", Tessera.elaborate(() -> new ScheduledCore(RiscvTests.MEM_WORDS, null, ScheduledCore.FIVE_STAGE_MUL_MF)));
        CORES.put("6-stage-mul-wb", Tessera.elaborate(() -> new ScheduledCore(RiscvTests.MEM_WORDS, null, ScheduledCore.SIX_STAGE_MUL_WB)));
        CORES.put("5-stage-mul-wb-bram", Tessera.elaborate(() -> new ScheduledCore(RiscvTests.MEM_WORDS, null, ScheduledCore.FIVE_STAGE_MUL_WB, false, true)));
        // the ZedBoard build: block RAM, Zicsr, debug port (idle), sequential divider
        CORES.put("5-stage-fpga", Tessera.elaborate(() -> new ScheduledCore(RiscvTests.MEM_WORDS, null, ScheduledCore.FIVE_STAGE, RvCore.Config.fpga())));
    }

    static List<Arguments> riscvTests() {
        var out = new ArrayList<Arguments>();
        for (var s : CORES.keySet()) for (var t : RiscvTests.names()) out.add(Arguments.of(s, t));
        return out;
    }

    static List<Arguments> randomPrograms() {
        var out = new ArrayList<Arguments>();
        for (var s : CORES.keySet()) for (long seed = 100; seed < 120; seed++) out.add(Arguments.of(s, seed));
        return out;
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("riscvTests")
    void passesRiscvTests(String schedule, String test) {
        var e = CORES.get(schedule);
        try (var sim = CoreRunner.sim(e)) {
            var r = CoreRunner.run(e, sim, RiscvTests.image(test), 200_000);
            assertEquals("pass", RiscvTests.verdict(r.gp()), test);
            STATS.merge(schedule, new long[]{r.cycles(), r.retired()}, (a, b) -> new long[]{a[0] + b[0], a[1] + b[1]});
        }
    }

    @ParameterizedTest(name = "{0} seed {1}")
    @MethodSource("randomPrograms")
    void matchesIssOnRandomPrograms(String schedule, long seed) {
        var e = CORES.get(schedule);
        try (var sim = CoreRunner.sim(e)) {
            var r = CoreRunner.run(e, sim, RandomProgram.generate(seed, 400), 40_000);
            assertTrue(r.retired() > 100);
        }
    }

    @ParameterizedTest(name = "{0} seed {1}")
    @MethodSource("randomPrograms")
    void matchesIssOnRandomMulDivPrograms(String schedule, long seed) {
        var e = CORES.get(schedule);
        try (var sim = CoreRunner.sim(e)) {
            var r = CoreRunner.run(e, sim, RandomProgram.generate(seed, 400, true), 60_000);
            assertTrue(r.retired() > 100);
        }
    }

    @Test
    void depthFollowsTheSchedule() {
        assertEquals(2, CORES.get("3-stage").top().pipeline.depth());
        assertEquals(4, CORES.get("5-stage").top().pipeline.depth());
        assertEquals(5, CORES.get("6-stage").top().pipeline.depth());
    }

    @Test
    void outOfOrderSchedulesAreRejected() {
        var e = assertThrows(TesseraException.class, () -> Tessera.elaborate(() -> new ScheduledCore(64, null,
                Schedule.stages("A", "B").place("A", "fetch", "execute").place("B", "decode", READ_REGS, "memory",
                        WRITE_REGS, "retire"))));
        assertTrue(e.getMessage().contains("step 'decode' is scheduled in B")
                || e.getMessage().contains("before step"), e.getMessage());
    }

    @Test
    void generatedCoresAreLintClean() throws Exception {
        assumeTrue(Verilator.available());
        int[] image = RiscvTests.image("rv32ui-simple");
        for (var s : SCHEDULES.entrySet())
            Verilator.lint(Tessera.elaborate(() -> new ScheduledCore(1024, image, s.getValue())).circuit(),
                    "lint-scheduled-" + s.getKey());
    }

    @AfterAll
    static void report() {
        for (var s : SCHEDULES.keySet()) {
            long[] st = STATS.get(s);
            if (st != null) System.out.printf("%s: CPI %.3f over riscv-tests%n", s, (double) st[0] / st[1]);
            System.out.println("  " + CORES.get(s).top().pipeline.report().replace("\n", "\n  "));
        }
    }

    @Test
    void blockRamNeedsMemoryAfterExecute() {
        var ex = assertThrows(TesseraException.class,
                () -> Tessera.elaborate(() -> new ScheduledCore(64, null, ScheduledCore.FOUR_STAGE, false, true)));
        assertTrue(ex.getMessage().contains("\"memory\" must be in the stage after \"execute\""), ex.getMessage());
    }
}
