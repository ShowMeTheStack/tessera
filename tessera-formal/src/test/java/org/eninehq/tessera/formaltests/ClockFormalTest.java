package org.eninehq.tessera.formaltests;

import static org.eninehq.tessera.core.Hw.assertThat;
import static org.eninehq.tessera.core.Hw.when;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.Supplier;
import org.eninehq.tessera.core.Bool;
import org.eninehq.tessera.core.ClockDomain;
import org.eninehq.tessera.core.Hw;
import org.eninehq.tessera.core.Mem;
import org.eninehq.tessera.core.Module;
import org.eninehq.tessera.core.Tessera;
import org.eninehq.tessera.core.UInt;
import org.eninehq.tessera.formal.Btor2Emitter;
import org.eninehq.tessera.formal.Formal;
import org.eninehq.tessera.formal.YosysFormal;
import org.eninehq.tessera.ir.Circuit;
import org.eninehq.tessera.ir.ClockDomainDef.ResetKind;
import org.eninehq.tessera.sim.Netlist;
import org.eninehq.tessera.sim.Sim;
import org.eninehq.tessera.sim.Simulator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Separate clock domains and asynchronous resets in the formal back end. Every design has a
 * positive check and a negative control (a property that must fail); failures replay in the simulator
 * with the same clock edges, passes are backed by random multi-clock simulation, and the verdicts are
 * compared with yosys-smtbmc ({@code clk2fflogic}, no {@code async2sync}).
 */
class ClockFormalTest {

    @BeforeAll
    static void needsSolver() {
        assumeTrue(Formal.Options.defaults().solver() != null, "no SMT solver installed");
    }

    static Formal.Options opt() {
        return Formal.Options.defaults();
    }

    /**
     * For designs with synchronizers: their flip-flops have no reset, so the reset is held for 3 cycles of every
     * clock, which flushes a 2-stage synchronizer (see {@code synchronizersStartArbitrary}).
     */
    static Formal.Options hs() {
        return opt().withResetCycles(3);
    }

    // ------------------------------------------------------------------ designs

    /** Two free-running counters on different clocks; claims they are always equal (true only if the clocks tick together). */
    static class LockStep extends Module {
        LockStep() {
            ClockDomain b = clockDomain("b");
            UInt ca = reg("ca", UInt.w(4), 0);
            ca.set(ca.addWrap(1));
            UInt cb = withClock(b, () -> {
                UInt r = reg("cb", UInt.w(4), 0);
                r.set(r.addWrap(1));
                return r;
            });
            output("oa", UInt.w(4)).set(ca);
            output("ob", UInt.w(4)).set(cb);
            assertThat(ca.eq(cb), "counters_equal");
        }
    }

    /**
     * A two-phase handshake between a sender (clock {@code tx}) and a receiver (the implicit clock): the sender writes a
     * sequence number into a one-word buffer and toggles {@code req}; the receiver, seeing {@code req} change through a
     * synchronizer, reads the buffer, checks it got the next number, and toggles {@code ack} back. With
     * {@code waitForAck} false the sender does not wait for the acknowledge (an unsynchronised handshake): it may
     * overwrite the buffer before the receiver has read it. The CDC lint accepts both (the buffer is declared as crossing).
     */
    static class Handshake extends Module {
        Handshake(boolean waitForAck) {
            ClockDomain tx = clockDomain("tx");
            Bool send = input("send", Bool.TYPE);
            Mem<UInt> buf = mem("buf", UInt.w(8), 2).crossesClockDomains();
            Bool ack = reg("ack", Bool.TYPE, 0);
            UInt last = reg("last", UInt.w(8), 0);
            Bool req = withClock(tx, () -> {
                UInt seq = reg("seq", UInt.w(8), 0);
                Bool r = reg("req", Bool.TYPE, 0);
                Bool ackTx = Hw.synchronize(ack, 2);
                Bool idle = waitForAck ? ackTx.eq(r) : Bool.of(true);
                Bool go = send.and(idle).named("go");
                UInt nseq = seq.addWrap(1);
                when(go, () -> {
                    buf.write(UInt.lit(0, 1), nseq);
                    seq.set(nseq);
                    r.set(r.not());
                });
                return r;
            });
            Bool reqRx = Hw.synchronize(req, 2);
            Bool fresh = reqRx.ne(ack).named("fresh");
            UInt v = buf.read(UInt.lit(0, 1));
            assertThat(fresh.not().or(v.eq(last.addWrap(1))), "next_in_sequence");
            when(fresh, () -> {
                last.set(v);
                ack.set(ack.not());
            });
            output("got", UInt.w(8)).set(last);
        }
    }

    /**
     * A counter with an asynchronous (or, as a control, synchronous) active-high reset, saturating at 15, and a copy of
     * it taken on every edge by registers without reset.
     */
    static class ResetCounter extends Module {
        ResetCounter(ResetKind kind) {
            useReset(kind);
            Bool inc = input("inc", Bool.TYPE);
            UInt c = reg("c", UInt.w(4), 0);
            when(inc.and(c.ne(15)), () -> c.set(c.addWrap(1)));
            output("o", UInt.w(4)).set(c);
            UInt seen = Hw.past(c, 1);
            Bool wasReset = Hw.past(reset(), 1);
            // reset at the last edge: the counter was already 0 then (an asynchronous reset needs no edge)
            assertThat(wasReset.not().or(seen.eq(0)), "zero_while_reset");
            // never decreases over two edges: false once the reset may strike at any time. (Properties are off while
            // the reset is active, and the reset shows 0 at once, so the drop is only visible two edges apart. The
            // copies have no reset, so this needs two reset cycles.)
            assertThat(c.ge(Hw.past(c, 2)), "monotone");
        }
    }

    /** Clock {@code b} must have ticked by the time clock {@code a} (the implicit one) has ticked 4 times after reset. */
    static class Watchdog extends Module {
        Watchdog() {
            ClockDomain b = clockDomain("b");
            UInt na = reg("na", UInt.w(4), 0);
            when(na.ne(15), () -> na.set(na.addWrap(1)));
            UInt nb = withClock(b, () -> {
                UInt r = reg("nb", UInt.w(4), 0);
                when(r.ne(15), () -> r.set(r.addWrap(1)));
                return r;
            });
            output("o", UInt.w(4)).set(nb);
            assertThat(na.lt(4).or(nb.ne(0)), "b_ticked");
        }
    }

    // ------------------------------------------------------------------ helpers

    static Circuit circuit(Supplier<? extends Module> m) {
        return Tessera.elaborate(m).circuit();
    }

    /** A failing result replays in the simulator with the same clock edges and fails on the same check. */
    static void replays(Formal.Result r) {
        assertTrue(r.failed(), r.toString());
        var err = r.replay(null);
        assertNotNull(err, "the counterexample must reproduce in simulation: " + r + " ticks " + r.ticks());
        assertTrue(err.getMessage().contains("'" + r.check() + "'"), err.getMessage());
    }

    /**
     * Random simulation: {@code cycles} steps, each with a random subset of clocks having an edge and random inputs
     * (resets active in cycle 0 only, unless {@code randomAsyncReset}); returns the first assertion failure, or null.
     */
    static AssertionError randomSim(Circuit c, long seed, int cycles, boolean randomAsyncReset) {
        var net = Netlist.build(c);
        var rnd = new Random(seed);
        var resets = new java.util.LinkedHashMap<String, Long>();
        for (var r : net.regs())
            if (r.hasReset())
                for (var e : net.inputSlots().entrySet()) if (e.getValue() == r.resetSlot()) resets.put(e.getKey(), r.resetActive());
        try (Simulator sim = Sim.create(c, Sim.defaultBackend())) {
            for (int t = 0; t < cycles; t++) {
                for (var e : net.inputSlots().entrySet()) {
                    String p = e.getKey();
                    if (resets.containsKey(p)) {
                        boolean active = t == 0 || (randomAsyncReset && rnd.nextInt(16) == 0);
                        sim.poke(p, active ? resets.get(p) : 1 - resets.get(p));
                    } else {
                        sim.poke(p, rnd.nextLong());
                    }
                }
                var ticking = new ArrayList<String>();
                for (var clk : sim.clocks()) if (t == 0 || rnd.nextBoolean()) ticking.add(clk);
                try {
                    sim.stepClocks(ticking);
                } catch (AssertionError err) {
                    return err;
                }
            }
        }
        return null;
    }

    static boolean yosys() {
        return YosysFormal.available();
    }

    static YosysFormal.Result ys(Circuit c, String name, int depth, Formal.Options o) throws Exception {
        return YosysFormal.bmc(c, Path.of("build", "formal", "clk2ff", name), depth, "z3", o);
    }

    // ------------------------------------------------------------------ multi-clock

    @Test
    void independentClocksBreakLockStep() throws Exception {
        var c = circuit(LockStep::new);
        var r = Formal.bmc(c, 6, opt());
        assertEquals(Formal.Status.FAIL, r.status(), r.toString());
        assertEquals("counters_equal", r.check());
        assertEquals(2, r.failCycle(), "reset, one edge of one clock, then the check: " + r + " " + r.ticks());
        assertNotNull(r.ticks());
        replays(r);
        // in lock step (the old single-clock model, and the simulator's step()) the property holds
        try (var sim = Sim.create(c, Sim.defaultBackend())) {
            sim.poke("reset", 1);
            sim.poke("b_reset", 1);
            sim.step();
            sim.poke("reset", 0);
            sim.poke("b_reset", 0);
            for (int i = 0; i < 40; i++) sim.step();
        }
        if (yosys()) assertTrue(ys(c, "lockstep", 6, opt()).failed());
    }

    @Test
    void handshakeWithAcknowledgePasses() throws Exception {
        var c = circuit(() -> new Handshake(true));
        var r = Formal.bmc(c, 16, hs());
        assertEquals(Formal.Status.PASS, r.status(), r.toString());
        for (long seed = 1; seed <= 20; seed++) assertNull(randomSim(c, seed, 2000, false), "seed " + seed);
        if (yosys()) assertFalse(ys(c, "handshake-good", 16, hs()).failed());
    }

    @Test
    void handshakeWithoutAcknowledgeFails() throws Exception {
        var c = circuit(() -> new Handshake(false));
        var r = Formal.bmc(c, 16, hs());
        assertEquals(Formal.Status.FAIL, r.status(), r.toString());
        assertEquals("next_in_sequence", r.check());
        replays(r);
        boolean simFound = false;
        for (long seed = 1; seed <= 20 && !simFound; seed++) simFound = randomSim(c, seed, 2000, false) != null;
        assertTrue(simFound, "random multi-clock simulation also finds the lost message");
        if (yosys()) assertTrue(ys(c, "handshake-bad", 16, hs()).failed());
    }

    @Test
    void synchronizersStartArbitrary() throws Exception {
        // synchronizer flip-flops have no reset: with a single reset cycle they can start out claiming a request
        var c = circuit(() -> new Handshake(true));
        var r = Formal.bmc(c, 16, opt());
        assertEquals(Formal.Status.FAIL, r.status(), r.toString());
        assertEquals("next_in_sequence", r.check());
        replays(r);
        if (yosys()) assertTrue(ys(c, "handshake-1reset", 16, opt()).failed());
    }

    @Test
    void boundedTickGapIsAnAssumption() throws Exception {
        var c = circuit(Watchdog::new);
        // independent clocks: b may never tick
        var free = Formal.bmc(c, 10, opt());
        assertEquals(Formal.Status.FAIL, free.status(), free.toString());
        replays(free);
        // some clock ticks at every step: a may be the only one
        var one = Formal.bmc(c, 10, opt().withAtLeastOneTick(true));
        assertEquals(Formal.Status.FAIL, one.status(), one.toString());
        for (var row : one.ticks()) assertFalse(row.isEmpty(), "a clock ticks in every cycle: " + one.ticks());
        replays(one);
        // every clock ticks within every 4 steps: b has ticked once a has ticked 4 times
        assertEquals(Formal.Status.PASS, Formal.bmc(c, 12, opt().withMaxTickGap(4)).status());
        // within every 5 steps is not enough: steps 1..4 can be a's alone (control)
        var five = Formal.bmc(c, 12, opt().withMaxTickGap(5));
        assertEquals(Formal.Status.FAIL, five.status(), five.toString());
        replays(five);
    }

    @Test
    void aTickGapOfOneIsEveryClockAtEveryStep() throws Exception {
        // a window of one step has one tick per clock: the assumption is still well-formed SMT (it was "(or x)")
        var c = circuit(Watchdog::new);
        var r = Formal.bmc(c, 12, opt().withMaxTickGap(1));
        assertEquals(Formal.Status.PASS, r.status(), r.toString());
    }

    // ------------------------------------------------------------------ asynchronous reset

    @Test
    void asynchronousResetActsWithoutAnEdge() throws Exception {
        var c = circuit(() -> new ResetCounter(ResetKind.ASYNC_HIGH));
        var held = opt().withResetCycles(2).withFreeAsyncResets(false);
        // reset held inactive after the start: both properties hold, and are proven
        assertEquals(Formal.Status.PASS, Formal.bmc(c, 20, held).status());
        assertEquals(Formal.Status.PROVEN, Formal.prove(c, 3, held).status());
        // the reset may strike at any time: the value is 0 at once, but the counter is no longer monotone
        var r = Formal.bmc(c, 20, opt().withResetCycles(2));
        assertEquals(Formal.Status.FAIL, r.status(), r.toString());
        assertEquals("monotone", r.check());
        replays(r);
        // the simulator agrees: random runs with the reset held pass, with random resets the counter drops
        for (long seed = 1; seed <= 10; seed++) assertNull(randomSim(c, seed, 2000, false), "seed " + seed);
        boolean simFound = false;
        for (long seed = 1; seed <= 10 && !simFound; seed++) {
            var err = randomSim(c, seed, 2000, true);
            simFound = err != null && err.getMessage().contains("'monotone'");
        }
        assertTrue(simFound, "random resets in simulation also break monotone");
        if (yosys()) {
            assertFalse(ys(c, "async-held", 20, held).failed());
            assertTrue(ys(c, "async-free", 20, opt().withResetCycles(2)).failed());
        }
    }

    @Test
    void asynchronousResetZeroWhileResetProven() throws Exception {
        // with a free reset, only the 'zero_while_reset' half: a design without the monotone check
        var c = circuit(() -> new Module() {
            {
                useReset(ResetKind.ASYNC_HIGH);
                Bool inc = input("inc", Bool.TYPE);
                UInt cnt = reg("c", UInt.w(4), 0);
                when(inc, () -> cnt.set(cnt.addWrap(1)));
                output("o", UInt.w(4)).set(cnt);
                assertThat(Hw.past(reset(), 1).not().or(Hw.past(cnt, 1).eq(0)), "zero_while_reset");
            }
        });
        assertEquals(Formal.Status.PASS, Formal.bmc(c, 20, opt()).status());
        assertEquals(Formal.Status.PROVEN, Formal.prove(c, 2, opt()).status());
        for (long seed = 1; seed <= 10; seed++) assertNull(randomSim(c, seed, 2000, true), "seed " + seed);
        if (yosys()) assertFalse(ys(c, "async-zero", 20, opt()).failed());
    }

    @Test
    void synchronousResetNeedsAnEdge() throws Exception {
        // the same design with a synchronous reset: the reset value appears only after the edge (negative control)
        var c = circuit(() -> new ResetCounter(ResetKind.SYNC_HIGH));
        var r = Formal.bmc(c, 20, opt());
        assertEquals(Formal.Status.FAIL, r.status(), r.toString());
        assertEquals("zero_while_reset", r.check());
        assertEquals(1, r.failCycle(), "the copy taken in the reset cycle is the arbitrary initial value: " + r);
        replays(r);
        if (yosys()) assertTrue(ys(c, "sync", 20, opt()).failed());
    }

    @ParameterizedTest
    @ValueSource(strings = {"z3", "bitwuzla", "yices-smt2"})
    void everySolverAgrees(String solver) {
        try {
            new ProcessBuilder(solver, "--version").start().waitFor();
        } catch (Exception ex) {
            assumeTrue(false, solver + " not installed");
        }
        var o = opt().withSolver(solver);
        assertEquals(2, Formal.bmc(circuit(LockStep::new), 6, o).failCycle());
        assertEquals(Formal.Status.PASS, Formal.bmc(circuit(() -> new Handshake(true)), 12, o.withResetCycles(3)).status());
        assertEquals(Formal.Status.FAIL, Formal.bmc(circuit(() -> new Handshake(false)), 12, o.withResetCycles(3)).status());
        assertEquals("monotone", Formal.bmc(circuit(() -> new ResetCounter(ResetKind.ASYNC_HIGH)), 12, o.withResetCycles(2)).check());
    }

    // ------------------------------------------------------------------ BTOR2

    @Test
    void btor2CounterexamplesAreWitnesses() throws Exception {
        String sim = Btor2Test.btorsim();
        assumeTrue(sim != null);
        Path dir = Path.of("build", "formal", "btor-clocks").toAbsolutePath();
        Files.createDirectories(dir);
        // (one reset cycle, as the BTOR2 model has; the asynchronous reset may also be active in the second)
        record Bad(String name, Supplier<? extends Module> design, int depth, Formal.Options o) {}
        for (var b : List.of(new Bad("lockstep", LockStep::new, 6, opt()), new Bad("handshake", () -> new Handshake(false), 16, opt()),
                new Bad("async", () -> new ResetCounter(ResetKind.ASYNC_HIGH), 12, opt().withResetCycles(2)))) {
            var c = circuit(b.design());
            var model = Btor2Emitter.emit(Netlist.build(c));
            var r = Formal.bmc(c, b.depth(), b.o());
            assertTrue(r.failed(), r.toString());
            replays(r);
            int bad = 0;
            for (var ch : Netlist.build(c).checks()) {
                if (ch.kind() != org.eninehq.tessera.ir.Assertion.Kind.ASSERT) continue;
                if (ch.name().equals(r.check())) break;
                bad++;
            }
            Files.writeString(dir.resolve(b.name() + ".btor"), model.text());
            Files.writeString(dir.resolve(b.name() + ".wit"), Btor2Emitter.witness(model, r, bad));
            String out = Btor2Test.run(dir, sim, "-c", b.name() + ".btor", b.name() + ".wit");
            assertTrue(out.startsWith("exit=0"), b.name() + ": " + out);
        }
        // the good handshake never reaches a bad state in btorsim's random simulation
        var good = Btor2Emitter.emit(Netlist.build(circuit(() -> new Handshake(true))));
        Files.writeString(dir.resolve("good.btor"), good.text());
        String out = Btor2Test.run(dir, sim, "-r", "500", "-s", "7", "good.btor");
        assertTrue(out.startsWith("exit=0") && !out.contains("\nsat"), out);
    }
}
