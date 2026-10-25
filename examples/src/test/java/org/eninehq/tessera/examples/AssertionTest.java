package org.eninehq.tessera.examples;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.eninehq.tessera.core.Bool;
import org.eninehq.tessera.core.Hw;
import org.eninehq.tessera.core.Module;
import org.eninehq.tessera.core.Tessera;
import org.eninehq.tessera.core.TesseraException;
import org.eninehq.tessera.core.UInt;
import org.eninehq.tessera.emit.SvEmitter;
import org.eninehq.tessera.sim.Sim;
import org.eninehq.tessera.test.Verilator;
import org.junit.jupiter.api.Test;

/** assert/assume/cover are checked by the simulator and emitted for formal tools. */
class AssertionTest {

    static class Bounded extends Module {
        Bounded(int limit) {
            Bool inc = input("inc", Bool.TYPE);
            UInt c = reg("c", UInt.w(4), 0);
            Hw.when(inc, () -> c.set(c.addWrap(1)));
            output("o", UInt.w(4)).set(c);
            Hw.assertThat(c.le(limit), "below_limit");
            Hw.cover(c.eq(3), "reaches_three");
            Hw.when(inc, () -> Hw.assertThat(Hw.past(c, 1).le(c).or(c.eq(0)), "monotonic"));
        }
    }

    @Test
    void simulatorChecksAssertionsAndCountsCovers() {
        var e = Tessera.elaborate(() -> new Bounded(5));
        try (var sim = Sim.create(e)) {
            sim.reset(1);
            sim.poke("inc", 1);
            sim.step(5);
            assertTrue(sim.coverHits().get("reaches_three") >= 1, sim.coverHits().toString());
            var err = assertThrows(AssertionError.class, () -> sim.step(3));
            assertTrue(err.getMessage().contains("assertion 'below_limit' failed"), err.getMessage());
            assertTrue(err.getMessage().contains("AssertionTest.java"), err.getMessage());
        }
    }

    @Test
    void propertiesAreEmittedUnderFormal() throws Exception {
        var e = Tessera.elaborate(() -> new Bounded(5));
        String sv = SvEmitter.emit(e);
        assertTrue(sv.contains("`ifdef FORMAL"), sv);
        assertTrue(sv.contains("below_limit: assert ("), sv);
        assertTrue(sv.contains("reaches_three: cover ("), sv);
        assumeTrue(Verilator.available());
        Verilator.lint(e.circuit(), "lint-Bounded");
    }

    @Test
    void fnRejectsState() {
        class Impure extends Module {
            Impure() {
                UInt a = input("a", UInt.w(4));
                output("o", UInt.w(4)).set(Hw.fn(() -> regNext(a)));
            }
        }
        var ex = assertThrows(TesseraException.class, () -> Tessera.elaborate(Impure::new));
        assertTrue(ex.getMessage().contains("combinational function created"), ex.getMessage());
        class Pure extends Module {
            Pure() {
                UInt a = input("a", UInt.w(4));
                output("o", UInt.w(4)).set(Hw.fn(() -> a.xor(Hw.lit(3, 4))));
            }
        }
        try (var sim = Sim.create(Tessera.elaborate(Pure::new))) {
            sim.poke("a", 5);
            assertEquals(6, sim.peek("o"));
        }
    }
}
