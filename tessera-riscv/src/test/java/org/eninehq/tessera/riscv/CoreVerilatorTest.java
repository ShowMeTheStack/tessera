package org.eninehq.tessera.riscv;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.ArrayList;
import org.eninehq.tessera.core.Tessera;
import org.eninehq.tessera.sim.Sim;
import org.eninehq.tessera.test.Verilator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The generated SystemVerilog of both cores is lint-clean and behaves like Tessera's simulator. */
@Tag("verilator")
class CoreVerilatorTest {

    @BeforeAll
    static void needsVerilator() {
        assumeTrue(Verilator.available(), "verilator not installed");
    }

    @Test
    void lintClean() throws Exception {
        int[] image = RiscvTests.image("rv32ui-simple");
        Verilator.lint(Tessera.elaborate(() -> new SingleCycleCore(1024, image)).circuit(), "lint-SingleCycleCore");
        Verilator.lint(Tessera.elaborate(() -> new FiveStageCore(1024, image)).circuit(), "lint-FiveStageCore");
    }

    private static void crossCheck(String test, boolean fiveStage) throws Exception {
        int[] image = RiscvTests.image(test);
        var e = fiveStage
                ? Tessera.elaborate(() -> new FiveStageCore(2048, image))
                : Tessera.elaborate(() -> new SingleCycleCore(2048, image));
        var stim = new ArrayList<long[]>();
        for (int cyc = 0; cyc < 1500; cyc++) stim.add(new long[]{cyc < 2 ? 1 : 0});
        Verilator.crossCheck(e, "xcheck-" + (fiveStage ? "five-" : "single-") + test, stim, Sim.Backend.COMPILED);
    }

    @Test
    void fiveStageMatchesVerilatorOnLoadsAndStores() throws Exception {
        crossCheck("rv32ui-sw", true);
        crossCheck("rv32ui-lbu", true);
    }

    @Test
    void fiveStageMatchesVerilatorOnBranches() throws Exception {
        crossCheck("rv32ui-bltu", true);
    }

    @Test
    void singleCycleMatchesVerilator() throws Exception {
        crossCheck("rv32ui-sra", false);
    }
}
