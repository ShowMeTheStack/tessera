package org.eninehq.tessera.pipelinetests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Random;
import org.eninehq.tessera.core.Bool;
import org.eninehq.tessera.core.Module;
import org.eninehq.tessera.core.Tessera;
import org.eninehq.tessera.core.TesseraException;
import org.eninehq.tessera.core.UInt;
import org.eninehq.tessera.pipeline.Latency;
import org.eninehq.tessera.pipeline.Pipeline;
import org.eninehq.tessera.sim.Sim;
import org.junit.jupiter.api.Test;

/** A parent pipeline that stalls while a fixed-latency sub-pipeline is in flight. */
class StallableSubPipelineTest {

    @Latency(2)
    static class MulAddS extends Module {
        final UInt a = input("a", UInt.w(8)), b = input("b", UInt.w(8)), c = input("c", UInt.w(16));
        final UInt out = output("out", UInt.w(17));

        MulAddS() {
            var p = Pipeline.beginStallable("mac");
            UInt prod = p.input(a).mul(p.input(b));
            UInt cc = p.input(c);
            p.stage();
            UInt sum = prod.add(cc);
            p.stage();
            out.set(sum);
            p.end();
        }
    }

    /** y = x*x + x + x, with random stalls in the middle stage and at the end. */
    static class Outer extends Module {
        Outer(boolean stallable) {
            UInt x = input("x", UInt.w(8));
            Bool stallMid = input("stall_mid", Bool.TYPE);
            Bool stallEnd = input("stall_end", Bool.TYPE);
            var p = Pipeline.begin("outer");
            UInt xs = p.input(x);
            output("accept", Bool.TYPE).set(p.advancing());
            if (stallable) {
                MulAddS m = p.inst(2, MulAddS::new);
                m.a.set(xs);
                m.b.set(xs);
                m.c.set(xs.zext(16));
                p.stage();
                p.stall(stallMid);
                p.stage();
                p.stall(stallEnd);
                output("y", UInt.w(18)).set(m.out.add(xs));
            } else {
                PipelineTest.MulAdd m = p.inst(2, PipelineTest.MulAdd::new);
                m.a.set(xs);
                m.b.set(xs);
                m.c.set(xs.zext(16));
                p.stage();
                p.stall(stallMid);
                p.stage();
                output("y", UInt.w(18)).set(m.out.add(xs));
            }
            output("valid", Bool.TYPE).set(p.valid().and(p.advancing()));
            p.end();
        }
    }

    @Test
    void resultsSurviveStalls() {
        var e = Tessera.elaborate(() -> new Outer(true));
        var rnd = new Random(11);
        var sent = new ArrayList<Long>();
        var got = new ArrayList<Long>();
        try (var sim = Sim.create(e)) {
            sim.reset(1);
            long next = 1;
            for (int t = 0; t < 400; t++) {
                sim.poke("x", next & 0xFF);
                sim.poke("stall_mid", rnd.nextInt(4) == 0 ? 1 : 0);
                sim.poke("stall_end", rnd.nextInt(3) == 0 ? 1 : 0);
                if (sim.peek("valid") == 1) got.add(sim.peek("y"));
                if (sim.peek("accept") == 1) {
                    sent.add(next & 0xFF);
                    next++;
                }
                sim.step();
            }
        }
        assertTrue(got.size() > 100, "results: " + got.size());
        for (int i = 0; i < got.size(); i++) {
            long v = sent.get(i);
            assertEquals(v * v + v + v, got.get(i), "item " + i);
        }
    }

    @Test
    void nonStallableSubPipelineStillRejected() {
        var ex = assertThrows(TesseraException.class, () -> Tessera.elaborate(() -> new Outer(false)));
        assertTrue(ex.getMessage().contains("beginStallable"), ex.getMessage());
    }
}
