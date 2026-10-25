package org.eninehq.tessera.pipelinetests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.eninehq.tessera.core.Bool;
import org.eninehq.tessera.core.Module;
import org.eninehq.tessera.core.Tessera;
import org.eninehq.tessera.core.TesseraException;
import org.eninehq.tessera.core.UInt;
import org.eninehq.tessera.ir.Signal;
import org.eninehq.tessera.pipeline.Late;
import org.eninehq.tessera.pipeline.Latency;
import org.eninehq.tessera.pipeline.Pipeline;
import org.eninehq.tessera.sim.Sim;
import org.eninehq.tessera.test.Verilator;
import org.junit.jupiter.api.Test;

class PipelineTest {

    /** out = a*b + c, two stages, Spade's mul_add example. */
    @Latency(2)
    static class MulAdd extends Module {
        final UInt a = input("a", UInt.w(8)), b = input("b", UInt.w(8)), c = input("c", UInt.w(16));
        final UInt out = output("out", UInt.w(17));

        MulAdd() {
            var p = Pipeline.begin("mac");
            UInt prod = p.input(a).mul(p.input(b));
            UInt cc = p.input(c);
            p.stage();
            UInt sum = prod.add(cc);
            p.stage();
            out.set(sum);
            p.end();
        }
    }

    @Test
    void valuesTravelWithTheirItem() {
        var e = Tessera.elaborate(MulAdd::new);
        var m = e.top();
        var rnd = new Random(1);
        long[] expect = new long[200];
        try (var sim = Sim.create(e)) {
            for (int t = 0; t < 200; t++) {
                long a = rnd.nextInt(256), b = rnd.nextInt(256), c = rnd.nextInt(65536);
                expect[t] = a * b + c;
                sim.poke(m.a, a);
                sim.poke(m.b, b);
                sim.poke(m.c, c);
                if (t >= 2) assertEquals(expect[t - 2], sim.peek(m.out), "cycle " + t);
                sim.step();
            }
        }
        long regs = e.circuit().top().signals().stream().filter(s -> s.kind() == Signal.Kind.REG).count();
        assertTrue(regs >= 3, "pipeline registers inserted: " + regs);
    }

    /** Uses a 2-stage sub-pipeline inside a pipeline; its result is available two stages later. */
    static class UsesMulAdd extends Module {
        UsesMulAdd(int wait, int declared) {
            UInt x = input("x", UInt.w(8));
            UInt y = output("y", UInt.w(18));
            var p = Pipeline.begin("outer");
            UInt xs = p.input(x);
            MulAdd m = p.inst(declared, MulAdd::new);
            m.a.set(xs);
            m.b.set(xs);
            m.c.set(xs.zext(16));
            for (int i = 0; i < wait; i++) p.stage();
            y.set(m.out.add(xs));
            p.end();
        }
    }

    @Test
    void subPipelineLatencyIsChecked() {
        var e = Tessera.elaborate(() -> new UsesMulAdd(2, 2));
        try (var sim = Sim.create(e)) {
            for (int t = 0; t < 20; t++) {
                sim.poke("x", t);
                if (t >= 2) assertEquals((t - 2L) * (t - 2) + 2 * (t - 2), sim.peek("y"), "cycle " + t);
                sim.step();
            }
        }
        var tooEarly = assertThrows(TesseraException.class, () -> Tessera.elaborate(() -> new UsesMulAdd(1, 2)));
        assertTrue(tooEarly.getMessage().contains("result of a sub-pipeline, available in stage s2, used in earlier stage s1 (1)"), tooEarly.getMessage());
        var wrong = assertThrows(TesseraException.class, () -> Tessera.elaborate(() -> new UsesMulAdd(3, 3)));
        assertTrue(wrong.getMessage().contains("has latency 2, not 3"), wrong.getMessage());
    }

    static class Backwards extends Module {
        Backwards() {
            UInt x = input("x", UInt.w(8));
            var p = Pipeline.begin("p");
            UInt early = p.input(x);
            p.stage();
            UInt later = early.addWrap(1);
            p.end();
            output("y", UInt.w(8)).set(later);
        }
    }

    @Test
    void pipelineValuesCannotEscape() {
        var e = assertThrows(TesseraException.class, () -> Tessera.elaborate(Backwards::new));
        assertTrue(e.getMessage().contains("used outside its pipeline"), e.getMessage());
    }

    static class FutureUse extends Module {
        FutureUse() {
            UInt x = input("x", UInt.w(8));
            UInt y = output("y", UInt.w(8));
            var p = Pipeline.begin("p");
            UInt[] box = new UInt[1];
            p.stage();
            box[0] = p.input(x);
            p.end();
            var q = Pipeline.begin("q");
            y.set(box[0]);
            q.end();
        }
    }

    @Test
    void valuesFromOtherPipelinesAreRejected() {
        var e = assertThrows(TesseraException.class, () -> Tessera.elaborate(FutureUse::new));
        assertTrue(e.getMessage().contains("another pipeline"), e.getMessage());
    }

    /**
     * A 4-stage pipeline carrying an item id. Stage 1 can stall (input {@code stall1}); stage 3
     * can flush everything younger (input {@code flush3}). Stage 0 reports {@code accept} when
     * it takes an item.
     */
    static class Flow extends Module {
        Flow() {
            Bool inValid = input("inValid", Bool.TYPE);
            UInt inData = input("inData", UInt.w(8));
            Bool stall1 = input("stall1", Bool.TYPE);
            Bool flush3 = input("flush3", Bool.TYPE);
            Bool accept = output("accept", Bool.TYPE);
            Bool outValid = output("outValid", Bool.TYPE);
            UInt outData = output("outData", UInt.w(8));
            Bool flushing = output("flushing", Bool.TYPE);

            var p = Pipeline.begin("p", inValid);
            UInt d = p.input(inData);
            accept.set(inValid.and(p.held(0).not()));
            p.stage("A");
            p.stall(stall1.and(p.valid()));
            p.stage("B");
            p.stage("C");
            Bool f = flush3.and(p.valid());
            p.flush(f);
            flushing.set(f);
            outValid.set(p.valid());
            outData.set(d);
            p.end();
        }
    }

    /** A cycle-level reference model of Flow. */
    static final class FlowModel {
        final Integer[] st = new Integer[4];   // item in stage k (null = bubble); stage 0 is the input

        record Out(boolean accept, boolean outValid, int outData, boolean flushing) {}

        Out step(boolean inValid, int inData, boolean stall1, boolean flush3) {
            st[0] = inValid ? inData : null;
            boolean s1 = stall1 && st[1] != null;
            boolean f3 = flush3 && st[3] != null;
            boolean hold0 = s1, hold1 = s1;
            var out = new Out(inValid && !hold0, st[3] != null, st[3] == null ? -1 : st[3], f3);
            Integer[] n = new Integer[4];
            n[3] = st[2];
            n[2] = hold1 ? null : st[1];
            n[1] = hold1 ? st[1] : st[0];
            if (f3) {
                n[3] = null;
                n[2] = null;
                n[1] = null;
            }
            System.arraycopy(n, 1, st, 1, 3);
            return out;
        }
    }

    @Test
    void stallsAndFlushesMatchReferenceModel() {
        var e = Tessera.elaborate(Flow::new);
        var rnd = new Random(3);
        var model = new FlowModel();
        var accepted = new ArrayDeque<Integer>();
        var delivered = new ArrayList<Integer>();
        try (var sim = Sim.create(e)) {
            sim.reset(1);
            int next = 0;
            for (int t = 0; t < 5000; t++) {
                boolean v = rnd.nextInt(4) != 0, s = rnd.nextInt(3) == 0, f = rnd.nextInt(10) == 0;
                sim.poke("inValid", v ? 1 : 0);
                sim.poke("inData", next & 0xFF);
                sim.poke("stall1", s ? 1 : 0);
                sim.poke("flush3", f ? 1 : 0);
                var o = model.step(v, next & 0xFF, s, f);
                assertEquals(o.accept(), sim.peek("accept") == 1, "accept @" + t);
                assertEquals(o.outValid(), sim.peek("outValid") == 1, "outValid @" + t);
                if (o.outValid()) assertEquals(o.outData(), sim.peek("outData"), "outData @" + t);
                if (o.accept()) {
                    accepted.add(next & 0xFF);
                    next++;
                }
                if (o.outValid()) delivered.add((int) sim.peek("outData"));
                sim.step();
            }
        }
        assertTrue(delivered.size() > 1000, "items flowed: " + delivered.size());
    }

    static class Forwarding extends Module {
        Forwarding() {
            UInt x = input("x", UInt.w(8));
            UInt y = output("y", UInt.w(9));
            var p = Pipeline.begin("p");
            UInt v = p.input(x);
            Late<UInt> fromLater = p.late(UInt.w(8));
            UInt sum = v.add(fromLater.get());   // stage 0: x now + (stage-1 value now)
            p.stage();
            fromLater.set(v);                    // stage 1's v is last cycle's x
            y.set(sum);                          // delayed: stage-0 sum from the previous cycle
            p.end();
        }
    }

    @Test
    void lateValuesReadLaterStagesInTheSameCycle() {
        var e = Tessera.elaborate(Forwarding::new);
        try (var sim = Sim.create(e)) {
            long[] xs = {5, 7, 11, 13, 17};
            for (int t = 0; t < xs.length; t++) {
                sim.poke("x", xs[t]);
                if (t >= 2) assertEquals(xs[t - 1] + xs[t - 2], sim.peek("y"), "cycle " + t);
                sim.step();
            }
        }
    }

    @Test
    void pipelinesAreLintCleanAndMatchVerilator() throws Exception {
        assumeTrue(Verilator.available());
        var e = Tessera.elaborate(Flow::new);
        Verilator.lint(e.circuit(), "lint-Flow");
        var rnd = new Random(5);
        var stim = new ArrayList<long[]>();
        for (int t = 0; t < 600; t++)
            stim.add(new long[]{t < 2 ? 1 : 0, rnd.nextInt(2), rnd.nextInt(256), rnd.nextInt(3) == 0 ? 1 : 0,
                    rnd.nextInt(8) == 0 ? 1 : 0});
        Verilator.crossCheck(e, "xcheck-Flow", stim, Sim.Backend.INTERPRETER);
        Verilator.lint(Tessera.elaborate(() -> new UsesMulAdd(2, 2)).circuit(), "lint-UsesMulAdd");
    }

    @Test
    void stallingIsRejectedInsideWhen() {
        assertThrows(TesseraException.class, () -> Tessera.elaborate(() -> new Module() {
            {
                Bool c = input("c", Bool.TYPE);
                var p = Pipeline.begin("p");
                org.eninehq.tessera.core.Hw.when(c, p::stage);
                p.end();
            }
        }));
    }

    @Test
    void stageInputOrderIsDocumented() {
        // the Verilator stimulus above relies on port order: reset, inValid, inData, stall1, flush3
        var ports = Tessera.elaborate(Flow::new).circuit().top().ports().stream()
                .filter(s -> s.kind() == Signal.Kind.INPUT).map(Signal::name).toList();
        assertEquals(List.of("clock", "reset", "inValid", "inData", "stall1", "flush3"), ports);
    }
}
