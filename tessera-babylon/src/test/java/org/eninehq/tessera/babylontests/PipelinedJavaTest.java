package org.eninehq.tessera.babylontests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import jdk.incubator.code.Reflect;
import org.eninehq.tessera.babylon.Hardware;
import org.eninehq.tessera.babylon.JavaHw;
import org.eninehq.tessera.core.Module;
import org.eninehq.tessera.core.Tessera;
import org.eninehq.tessera.core.UInt;
import org.eninehq.tessera.ir.Signal;
import org.eninehq.tessera.pipeline.Pipeline;
import org.eninehq.tessera.sim.Sim;
import org.junit.jupiter.api.Test;

/** Plain Java with stage boundaries becomes a pipeline; as Java it is just a function. */
class PipelinedJavaTest {

    @Reflect
    static int macc(int a, int b, int c) {
        int prod = a * b;
        Hardware.stage();
        int sum = prod + c;
        Hardware.stage();
        return sum ^ (sum >>> 7);
    }

    static class Macc extends Module {
        Macc() {
            UInt a = input("a", UInt.w(32)), b = input("b", UInt.w(32)), c = input("c", UInt.w(32));
            var p = Pipeline.begin("macc");
            UInt y = (UInt) JavaHw.of(PipelinedJavaTest.class, "macc").apply(p.input(a), p.input(b), p.input(c));
            output("y", UInt.w(32)).set(y);
            p.end();
        }
    }

    @Test
    void twoStageBoundariesGiveLatencyTwo() {
        var e = Tessera.elaborate(Macc::new);
        long regs = e.circuit().top().signals().stream().filter(s -> s.kind() == Signal.Kind.REG).count();
        assertTrue(regs >= 3, "pipeline registers: " + regs);
        var rnd = new Random(4);
        int[][] in = new int[200][3];
        try (var sim = Sim.create(e)) {
            sim.reset(1);
            for (int t = 0; t < 200; t++) {
                in[t] = new int[]{rnd.nextInt(), rnd.nextInt(), rnd.nextInt()};
                sim.poke("a", Integer.toUnsignedLong(in[t][0]));
                sim.poke("b", Integer.toUnsignedLong(in[t][1]));
                sim.poke("c", Integer.toUnsignedLong(in[t][2]));
                if (t >= 2) assertEquals(macc(in[t - 2][0], in[t - 2][1], in[t - 2][2]), (int) sim.peek("y"), "cycle " + t);
                sim.step();
            }
        }
    }
}
