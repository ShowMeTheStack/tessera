package org.eninehq.tessera.examples;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.eninehq.tessera.core.Bool;
import org.eninehq.tessera.core.ClockDomain;
import org.eninehq.tessera.core.Hw;
import org.eninehq.tessera.core.Module;
import org.eninehq.tessera.core.Tessera;
import org.eninehq.tessera.core.TesseraException;
import org.eninehq.tessera.core.UInt;
import org.eninehq.tessera.emit.SvEmitter;
import org.eninehq.tessera.ir.ClockDomainDef.ResetKind;
import org.eninehq.tessera.sim.MultiClock;
import org.eninehq.tessera.sim.Sim;
import org.eninehq.tessera.test.Verilator;
import org.junit.jupiter.api.Test;

/** Several clock domains, reset kinds, synchronizers and the CDC lint. */
class ClockDomainTest {

    /** Two counters on different clocks; a toggle crosses from fast to slow through a synchronizer. */
    static class TwoClocks extends Module {
        TwoClocks() {
            ClockDomain fast = clockDomain("fast");
            UInt slowCount = reg("slow_count", UInt.w(8), 0);
            slowCount.set(slowCount.addWrap(1));
            Bool toggle = withClock(fast, () -> {
                UInt c = reg("fast_count", UInt.w(8), 0);
                c.set(c.addWrap(1));
                output("fast_out", UInt.w(8)).set(c);
                return c.bit(0);
            });
            output("slow_out", UInt.w(8)).set(slowCount);
            output("synced", Bool.TYPE).set(Hw.synchronize(toggle, 2));
        }
    }

    @Test
    void domainsTickIndependently() {
        var e = Tessera.elaborate(TwoClocks::new);
        try (var sim = Sim.create(e)) {
            assertEquals(java.util.List.of("clock", "fast_clock"), sim.clocks());
            sim.poke("reset", 1);
            sim.poke("fast_reset", 1);
            sim.step();
            sim.poke("reset", 0);
            sim.poke("fast_reset", 0);
            var mc = new MultiClock(sim).clock("clock", 10).clock("fast_clock", 4);
            mc.runUntil(100);
            assertEquals(10, sim.peek("slow_out"));
            assertEquals(25, sim.peek("fast_out"));
        }
    }

    @Test
    void multiClockSvIsLintClean() throws Exception {
        var e = Tessera.elaborate(TwoClocks::new);
        String sv = SvEmitter.emit(e);
        assertTrue(sv.contains("always_ff @(posedge fast_clock)"), sv);
        assumeTrue(Verilator.available());
        Verilator.lint(e.circuit(), "lint-TwoClocks");
    }

    static class Unsynchronized extends Module {
        Unsynchronized() {
            ClockDomain fast = clockDomain("fast");
            UInt a = withClock(fast, () -> {
                UInt r = reg("a", UInt.w(4), 0);
                r.set(r.addWrap(1));
                return r;
            });
            UInt b = reg("b", UInt.w(4));
            b.set(a);
            output("o", UInt.w(4)).set(b);
        }
    }

    @Test
    void crossingWithoutSynchronizerIsAnError() {
        var ex = assertThrows(TesseraException.class, () -> Tessera.elaborate(Unsynchronized::new));
        assertTrue(ex.getMessage().contains("register 'b' (clock clock) samples logic clocked by fast_clock"), ex.getMessage());
        assertTrue(ex.getMessage().contains("ClockDomainTest.java"), ex.getMessage());
    }

    /** A child in the fast domain: the crossing is visible through the instance boundary too. */
    static class Leaf extends Module {
        final UInt out = output("o", UInt.w(4));

        Leaf() {
            UInt r = reg("r", UInt.w(4), 0);
            r.set(r.addWrap(1));
            out.set(r);
        }
    }

    static class HierCrossing extends Module {
        HierCrossing() {
            ClockDomain fast = clockDomain("fast");
            Leaf l = withClock(fast, () -> instance("leaf", Leaf::new));
            UInt b = reg("b", UInt.w(4));
            b.set(l.out);
            output("o", UInt.w(4)).set(b);
        }
    }

    @Test
    void crossingThroughInstanceIsAnError() {
        var ex = assertThrows(TesseraException.class, () -> Tessera.elaborate(HierCrossing::new));
        assertTrue(ex.getMessage().contains("samples logic clocked by fast_clock"), ex.getMessage());
    }

    static class AsyncLow extends Module {
        AsyncLow() {
            useReset(ResetKind.ASYNC_LOW);
            UInt r = reg("r", UInt.w(8), 0x5A);
            r.set(r.addWrap(1));
            output("o", UInt.w(8)).set(r);
        }
    }

    @Test
    void asyncActiveLowReset() {
        var e = Tessera.elaborate(AsyncLow::new);
        String sv = SvEmitter.emit(e);
        assertTrue(sv.contains("always_ff @(posedge clock or negedge reset)"), sv);
        assertTrue(sv.contains("if (!reset)"), sv);
        try (var sim = Sim.create(e)) {
            sim.poke("reset", 1); // inactive
            sim.step(3);
            sim.poke("reset", 0); // assert: takes effect without a clock edge
            assertEquals(0x5A, sim.peek("o"));
            sim.step(2);
            assertEquals(0x5A, sim.peek("o"));
            sim.poke("reset", 1);
            sim.step();
            assertEquals(0x5B, sim.peek("o"));
        }
    }

    static class ChildAsync extends Module {
        ChildAsync() {
            useReset(ResetKind.ASYNC_LOW);
            output("o", UInt.w(1)).set(reg("r", UInt.w(1), 0));
        }
    }

    static class PolarityMismatch extends Module {
        PolarityMismatch() {
            instance("c", ChildAsync::new);
            output("o", UInt.w(1)).set(Hw.lit(1, 1));
        }
    }

    @Test
    void resetPolarityMismatchIsAnError() {
        var ex = assertThrows(TesseraException.class, () -> Tessera.elaborate(PolarityMismatch::new));
        assertTrue(ex.getMessage().contains("active-low reset but is driven by an active-high one"), ex.getMessage());
    }
}
