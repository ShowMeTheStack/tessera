package org.eninehq.tessera.examples;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.eninehq.tessera.core.HwEnum;
import org.eninehq.tessera.core.Module;
import org.eninehq.tessera.core.Tessera;
import org.eninehq.tessera.core.UInt;
import org.eninehq.tessera.sim.Sim;
import org.junit.jupiter.api.Test;

/** Run with {@code ./gradlew :examples:test --tests '*SimBenchmark*' -Ptessera.bench=true}. */
class SimBenchmarkTest {

    /** An LFSR feeding {@code n} ALUs whose results are folded into a register. */
    static class AluFarm extends Module {
        AluFarm(int n) {
            UInt lfsr = reg("lfsr", UInt.w(64), 0xACE1);
            UInt fb = lfsr.bit(63).xor(lfsr.bit(62)).xor(lfsr.bit(60)).xor(lfsr.bit(59));
            lfsr.set(lfsr.bits(62, 0).cat(fb));
            UInt acc = reg("acc", UInt.w(32), 0);
            UInt sum = acc;
            for (int i = 0; i < n; i++) {
                Alu alu = instance(() -> new Alu(32));
                alu.a.set(lfsr.bits(31, 0).xor(i));
                alu.b.set(lfsr.bits(63, 32).addWrap(sum));
                int ops = Alu.Op.values().length;
                alu.op.set(Alu.Op.values()[i % ops]);
                sum = sum.xor(alu.y);
            }
            acc.set(sum);
            output("out", UInt.w(32)).set(acc);
        }
    }

    @Test
    void measure() {
        assumeTrue(Boolean.getBoolean("tessera.bench"), "benchmark disabled");
        var e = Tessera.elaborate(() -> new AluFarm(32));
        for (var backend : Sim.Backend.values()) {
            try (var sim = Sim.create(e, backend)) {
                int insns = sim.netlist().insns().size();
                sim.reset(1);
                sim.step(200_000); // warm up the JIT
                long t0 = System.nanoTime();
                int cycles = 1_000_000;
                sim.step(cycles);
                double sec = (System.nanoTime() - t0) / 1e9;
                System.out.printf("%-12s %6d insns  %8.0f kHz  (out=%x)%n", backend, insns, cycles / sec / 1e3, sim.peek("out"));
            }
        }
    }
}
