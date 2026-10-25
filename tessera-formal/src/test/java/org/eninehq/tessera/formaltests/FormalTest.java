package org.eninehq.tessera.formaltests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.eninehq.tessera.core.Bool;
import org.eninehq.tessera.core.Hw;
import org.eninehq.tessera.core.Module;
import org.eninehq.tessera.core.Tessera;
import org.eninehq.tessera.core.UInt;
import org.eninehq.tessera.formal.Formal;
import org.eninehq.tessera.formal.YosysFormal;
import org.eninehq.tessera.stdlib.Fifo;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class FormalTest {

    @BeforeAll
    static void needsSolver() {
        assumeTrue(Formal.Options.defaults().solver() != null, "no SMT solver installed");
    }

    /** A counter that should stop at 5 but (when buggy) can run past it. */
    static class Counter extends Module {
        Counter(boolean buggy) {
            Bool inc = input("inc", Bool.TYPE);
            UInt c = reg("c", UInt.w(4), 0);
            Bool canInc = buggy ? Bool.of(true) : c.lt(5);
            Hw.when(inc.and(canInc), () -> c.set(c.addWrap(1)));
            output("o", UInt.w(4)).set(c);
            Hw.assertThat(c.le(5), "at_most_five");
            Hw.cover(c.eq(5), "reaches_five");
        }
    }

    @Test
    void bmcFindsShortestCounterexampleThatReplays() throws Exception {
        var e = Tessera.elaborate(() -> new Counter(true));
        var r = Formal.bmc(e, 12);
        assertEquals(Formal.Status.FAIL, r.status(), r.toString());
        assertEquals("at_most_five", r.check());
        assertTrue(r.location().startsWith("FormalTest.java:"), r.location());
        assertEquals(7, r.failCycle(), "reset, then six increments: " + r);
        Path vcd = Path.of("build", "formal", "cex.vcd");
        var err = r.replay(vcd);
        assertNotNull(err, "the counterexample must reproduce in simulation");
        assertTrue(err.getMessage().contains("at_most_five"), err.getMessage());
        assertTrue(Files.size(vcd) > 0);
    }

    @Test
    void correctDesignPassesAndIsProven() {
        var e = Tessera.elaborate(() -> new Counter(false));
        assertEquals(Formal.Status.PASS, Formal.bmc(e, 15).status());
        assertEquals(Formal.Status.PROVEN, Formal.prove(e, 1).status());
        assertEquals(6, Formal.cover(e, 10).get("reaches_five"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"z3", "bitwuzla", "yices-smt2"})
    void everySolverAgrees(String solver) {
        var opt = Formal.Options.defaults().withSolver(solver);
        try {
            new ProcessBuilder(solver, "--version").start().waitFor();
        } catch (Exception ex) {
            assumeTrue(false, solver + " not installed");
        }
        var bad = Formal.bmc(Tessera.elaborate(() -> new Counter(true)).circuit(), 10, opt);
        assertEquals(7, bad.failCycle());
        var good = Formal.bmc(Tessera.elaborate(() -> new Counter(false)).circuit(), 10, opt);
        assertEquals(Formal.Status.PASS, good.status());
    }

    /** FIFO invariants checked formally: count never exceeds depth; a pop never happens when empty. */
    static class CheckedFifo extends Module {
        CheckedFifo() {
            var f = instance("f", () -> new Fifo<>(UInt.w(4), 3));
            Bool iv = input("iv", Bool.TYPE), or = input("or", Bool.TYPE);
            UInt ib = input("ib", UInt.w(4));
            f.in.valid().set(iv);
            f.in.bits().set(ib);
            f.out.ready().set(or);
            output("ov", Bool.TYPE).set(f.out.valid());
            output("ir", Bool.TYPE).set(f.in.ready());
            output("ob", UInt.w(4)).set(f.out.bits());
            Hw.assertThat(f.count.le(3), "count_bounded");
            Hw.assertThat(f.count.eq(0).not().or(f.out.valid().not()), "empty_means_not_valid");
            Hw.cover(f.count.eq(3), "full");
        }
    }

    @Test
    void fifoInvariants() {
        var e = Tessera.elaborate(CheckedFifo::new);
        assertEquals(Formal.Status.PASS, Formal.bmc(e, 10).status());
        assertEquals(4, Formal.cover(e, 10).get("full"), "reset, then three pushes");
    }

    @Test
    void agreesWithYosysSmtbmc() throws Exception {
        assumeTrue(YosysFormal.available(), "yosys-smtbmc not installed");
        var bad = YosysFormal.bmc(Tessera.elaborate(() -> new Counter(true)).circuit(), Path.of("build", "formal", "ys-bad"), 10, "z3");
        assertTrue(bad.failed(), bad.log());
        var good = YosysFormal.bmc(Tessera.elaborate(() -> new Counter(false)).circuit(), Path.of("build", "formal", "ys-good"), 10, "z3");
        assertTrue(!good.failed(), good.log());
    }
}
