package org.eninehq.tessera.riscv;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.eninehq.tessera.core.Tessera;
import org.eninehq.tessera.test.Verilator;
import org.eninehq.tessera.test.Yosys;
import org.junit.jupiter.api.Test;

/** The block-RAM core maps to Xilinx 7-series block RAM (the ZedBoard's Zynq-7020) and lints clean. */
class FpgaReadinessTest {

    @Test
    void blockRamCoreUsesRamb() throws Exception {
        assumeTrue(Yosys.available());
        int[] program = RiscvTests.image("rv32um-mul");
        var e = Tessera.elaborate(() -> new ScheduledCore(4096, program, ScheduledCore.FIVE_STAGE, true, true));
        var st = Yosys.synth(e.circuit(), "synth-bram-core", "synth_xilinx -family xc7 -top {top}");
        System.out.println("five-stage RV32IM+Zicsr, block RAM, xc7: " + st.byType());
        long rams = st.byType().entrySet().stream().filter(x -> x.getKey().startsWith("RAMB")).mapToLong(java.util.Map.Entry::getValue).sum();
        assertTrue(rams >= 2, "instruction and data memories in block RAM: " + st.byType());
        assumeTrue(Verilator.available());
        Verilator.lint(e.circuit(), "lint-bram-core");
    }
}
