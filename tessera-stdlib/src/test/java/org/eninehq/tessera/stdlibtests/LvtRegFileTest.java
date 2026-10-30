package org.eninehq.tessera.stdlibtests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.Random;
import org.eninehq.tessera.core.Tessera;
import org.eninehq.tessera.sim.Sim;
import org.eninehq.tessera.stdlib.LvtRegFile;
import org.eninehq.tessera.stdlib.RegFile;
import org.eninehq.tessera.test.Verilator;
import org.eninehq.tessera.test.Yosys;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** The live-value-table register file against the register array it replaces ({@link RegFile} with two writes). */
class LvtRegFileTest {

    @ParameterizedTest(name = "{0} zeroReg={1}")
    @CsvSource({"INTERPRETER,false", "INTERPRETER,true", "COMPILED,false", "COMPILED,true"})
    void matchesTheRegisterArray(Sim.Backend backend, boolean zero) {
        int reads = 5, writes = 2;
        var lvt = Tessera.elaborate(() -> new LvtRegFile(32, 32, reads, writes, zero));
        var ref = Tessera.elaborate(() -> new RegFile(32, 32, reads, writes, zero, false));
        var rnd = new Random(zero ? 11 : 12);
        int sameCycleSameReg = 0;
        try (var a = Sim.create(lvt, backend); var b = Sim.create(ref, backend)) {
            a.reset(1);
            b.reset(1);
            // both start from zero: write every register first through port 0
            for (int k = 0; k < 32; k++)
                for (var s : new org.eninehq.tessera.sim.Simulator[]{a, b}) {
                    s.poke("w0_en", 1);
                    s.poke("w0_addr", k);
                    s.poke("w0_data", 0);
                    s.poke("w1_en", 0);
                    s.step();
                }
            for (int t = 0; t < 20_000; t++) {
                int a0 = rnd.nextInt(8), a1 = rnd.nextInt(8);   // few registers: many same-register writes
                boolean e0 = rnd.nextBoolean(), e1 = rnd.nextBoolean();
                if (e0 && e1 && a0 == a1) sameCycleSameReg++;
                long d0 = rnd.nextInt() & 0xFFFFFFFFL, d1 = rnd.nextInt() & 0xFFFFFFFFL;
                int[] ra = new int[reads];
                for (int r = 0; r < reads; r++) ra[r] = rnd.nextInt(8);
                for (var s : new org.eninehq.tessera.sim.Simulator[]{a, b}) {
                    s.poke("w0_en", e0 ? 1 : 0);
                    s.poke("w0_addr", a0);
                    s.poke("w0_data", d0);
                    s.poke("w1_en", e1 ? 1 : 0);
                    s.poke("w1_addr", a1);
                    s.poke("w1_data", d1);
                    for (int r = 0; r < reads; r++) s.poke("r" + r + "_addr", ra[r]);
                }
                for (int r = 0; r < reads; r++) assertEquals(b.peek("r" + r + "_data"), a.peek("r" + r + "_data"), "cycle " + t + " port " + r);
                a.step();
                b.step();
            }
        }
        assertTrue(sameCycleSameReg > 400, "same-cycle writes of one register: " + sameCycleSameReg);
    }

    @Test
    void lintAndYosys() throws Exception {
        var e = Tessera.elaborate(() -> new LvtRegFile(32, 32, 5, 2, false));
        if (Verilator.available()) Verilator.lint(e.circuit(), "lint-lvt");
        assumeTrue(Yosys.available(), "yosys not installed");
        var st = Yosys.synth(e.circuit(), "lvt_regfile", "synth_xilinx -top {top} -family xc7");
        System.out.println("LVT yosys synth_xilinx: " + st.byType());
    }
}
