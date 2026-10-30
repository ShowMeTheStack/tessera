package org.eninehq.tessera.riscv;

import static org.eninehq.tessera.core.Hw.assertThat;
import static org.eninehq.tessera.core.Hw.assume;
import static org.eninehq.tessera.core.Hw.mux;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import org.eninehq.tessera.core.Bool;
import org.eninehq.tessera.core.Hw;
import org.eninehq.tessera.core.Module;
import org.eninehq.tessera.core.Tessera;
import org.eninehq.tessera.core.UInt;
import org.eninehq.tessera.formal.Formal;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

/**
 * Proofs of the sequential divider, which riscv-formal does not see (ALTOPS replaces division):
 * <ul>
 *   <li><b>step</b>: one {@link Rv32i#divSteps} step keeps the restoring-division invariant: from a remainder below the
 *       divisor it gives {@code rem' + take*divisor = 2*rem + msb(dividend)} with {@code rem' < divisor} and
 *       {@code quo' = 2*quo + take}. With S = quo*divisor + rem this is S' = 2*S + msb, so after 32 steps from 0,
 *       S = dividend and rem < divisor: quo and rem are the unsigned quotient and remainder.</li>
 *   <li><b>protocol</b> (k-induction, unbounded): under the pipeline's protocol (an instruction is held with its operands
 *       until it advances, and does not advance while the unit stalls it), on any schedule, the in-order unit
 *       {@link RvCore#sequentialDivide} delivers a result only after exactly 17 stalled cycles (the load, then 16
 *       cycles of 2 steps) holding the operands it delivers for, and is idle in between.</li>
 *   <li><b>canonical</b> (BMC): on the schedule where a divide arrives at cycle 0 and leaves when it may, the
 *       delivered value equals the combinational 32-step divide. With the protocol lemma, every delivery is this
 *       schedule up to idle cycles before the load, which the load overwrites (count, remainder, quotient and dividend).</li>
 *   <li><b>fixup</b>: given the unsigned quotient and remainder of |a| and |b|, {@link Rv32i#divFinish} gives the RISC-V
 *       results of DIV, DIVU, REM and REMU: truncating division, the sign of the remainder that of the dividend,
 *       division by zero (quotient all ones, remainder the dividend) and overflow (-2^31 / -1).</li>
 * </ul>
 * Writes build/results/divider-proofs.csv (or into -PresultsDir).
 */
class DividerProofs {
    static final Map<String, String> ROWS = new TreeMap<>();

    /** One restoring step, from any state with rem below a nonzero divisor. */
    static class Step extends Module {
        Step() {
            this(false);
        }

        /** broken: drop the divisor-bound assumption, so the invariant need not hold (a negative control). */
        Step(boolean broken) {
            var d = HwDom.INSTANCE;
            UInt rem = input("rem", UInt.w(32)), quo = input("quo", UInt.w(32)), dvd = input("dvd", UInt.w(32)), div = input("div", UInt.w(32));
            assume(broken ? div.ne(0) : div.ne(0).and(rem.lt(div)), "rem_below_divisor");
            Object[] st = Rv32i.divSteps(d, rem, quo, dvd, div, 1);
            UInt rem1 = (UInt) st[0], quo1 = (UInt) st[1], dvd1 = (UInt) st[2];
            Bool take = quo1.bit(0);
            assertThat(rem1.lt(div), "step_rem_below_divisor");
            UInt lhs = rem1.zext(34).addWrap(mux(take, div.zext(34), UInt.lit(0, 34)));
            UInt rhs = rem.zext(34).shl(1).trunc(34).addWrap(dvd.bit(31).zext(34));
            assertThat(lhs.eq(rhs), "step_identity");
            assertThat(quo1.bits(31, 1).eq(quo.bits(30, 0)), "step_quotient_shift");
            assertThat(dvd1.eq(dvd.shl(1).trunc(32)), "step_dividend_shift");
        }
    }

    /**
     * The in-order sequential unit, driven like a pipeline stage drives it: an instruction is held with its operands
     * until it advances, and does not advance while the unit stalls it. With {@code canonical} one divide arrives at
     * cycle 0 and moves on as soon as it may, and the result must equal the combinational 32-step divide. Otherwise the
     * schedule is free and only the protocol is checked, from what the unit shows: a result is delivered only after
     * exactly 17 stalled cycles (the load and 16 cycles of 2 steps) on the operands it is delivered for.
     */
    static class Control extends RvCore {
        Control(boolean canonical) {
            this(canonical, false);
        }

        /** broken: a reference one step short, or a protocol of 16 stalls (negative controls). */
        Control(boolean canonical, boolean broken) {
            this(canonical, broken, false);
        }

        /** flush: a held instruction may also be dropped (cancelled) instead of staying held. */
        Control(boolean canonical, boolean broken, boolean flush) {
            super(2, null);
            var d = HwDom.INSTANCE;
            UInt a = input("a", UInt.w(32)), b = input("b", UInt.w(32)), f3 = input("f3", UInt.w(3));
            Bool want = input("want", Bool.TYPE), advancing = input("advancing", Bool.TYPE);
            // an M instruction (funct7 = 1, opcode OP) with the free funct3
            UInt inst = UInt.lit(1, 7).cat(UInt.lit(0, 10)).cat(f3).cat(UInt.lit(0, 5)).cat(UInt.lit(0x33, 7));
            var dec = Rv32i.decode(d, inst, false);
            var sd = sequentialDivide(want, advancing, Rv32i.mulDivStart(d, dec, a, b, 0));
            Bool held = reg("p_held", Bool.TYPE, 0);
            UInt pa = reg("p_a", UInt.w(32), 0), pb = reg("p_b", UInt.w(32), 0), pf3 = reg("p_f3", UInt.w(3), 0);
            held.set(want.and(advancing.not()));
            pa.set(a);
            pb.set(b);
            pf3.set(f3);
            Bool flushed = flush ? input("flush", Bool.TYPE) : Bool.of(false);
            assume(held.not().or(want.and(a.eq(pa)).and(b.eq(pb)).and(f3.eq(pf3))).or(flushed.and(want.not())), "held_operands");
            assume(advancing.and(sd.stall()).not(), "no_advance_while_stalled");
            Bool delivered = want.and(sd.stall().not());
            if (canonical) {
                assume(want.and(advancing.eq(sd.stall().not())), "canonical_schedule");
                UInt ref = Rv32i.mulDivFinish(d, Rv32i.mulDivStart(d, dec, a, b, 16), broken ? 15 : 16);
                assertThat(delivered.not().or(sd.value().eq(ref)), "result_matches_32_step_divide");
            } else {
                // stalled cycles of the instruction now held (0 when none is), and its operands when it first stalled
                UInt n = reg("g_stalls", UInt.w(5), 0);
                UInt la = reg("g_a", UInt.w(32), 0), lb = reg("g_b", UInt.w(32), 0), lf3 = reg("g_f3", UInt.w(3), 0);
                Hw.when(sd.stall().and(n.eq(0)), () -> {
                    la.set(a);
                    lb.set(b);
                    lf3.set(f3);
                });
                n.set(mux(sd.stall(), mux(n.eq(31), n, n.addWrap(1)), mux(advancing.or(want.not()), UInt.lit(0, 5), n)));
                Bool same = a.eq(la).and(b.eq(lb)).and(f3.eq(lf3));
                assertThat(delivered.not().or(n.eq(broken ? 16 : 17).and(same)), "delivered_after_17_stalls_same_operands");
                // the inductive strengthening: what the unit's state says about the instruction it serves
                if (!flush) {
                    Bool busy = sd.busy(), done = sd.done();
                    UInt count = sd.count();
                    assertThat(busy.and(done).not(), "inv_busy_or_done");
                    assertThat(busy.or(done).not().or(held.and(same)), "inv_serving_held_instruction");
                    assertThat(busy.not().or(count.bit(0).not().and(count.le(30)).and(n.zext(6).eq(count.shr(1).zext(6).addWrap(1)))),
                            "inv_busy_count");
                    assertThat(done.not().or(n.eq(17)), "inv_done_after_17");
                    assertThat(busy.or(done).or(n.eq(0)), "inv_idle_no_stalls");
                }
            }
            halted.set(false);
            retire(new RetireInfo(Bool.of(false), inst, UInt.lit(0, 32), dec, Rv32i.execute(d, dec, UInt.lit(0, 32), a, b), a, b,
                    UInt.lit(0, 32), UInt.lit(0, 32)));
        }
    }

    /**
     * The out-of-order core's unit {@link RvCore#mulDivUnit}, driven as {@link OooCore} drives it: an operation issues only
     * while the unit is free, and a flush clears it. The operation (multiply or divide, any funct3), its tag and operands
     * are free. Protocol (free issue and flush): whatever the unit puts on its bus is an operation issued and not
     * flushed since, with the operands and tag it was issued with, a multiply the cycle after it issues and a divide 17
     * cycles after. Canonical (one operation at cycle 0, no flush): the value on the bus equals the combinational
     * 32-step divide or the product.
     */
    static class OooUnit extends RvCore {
        OooUnit(boolean canonical, boolean broken) {
            super(2, null);
            var d = HwDom.INSTANCE;
            UInt a = input("a", UInt.w(32)), b = input("b", UInt.w(32)), f3 = input("f3", UInt.w(3)), tag = input("tag", UInt.w(4));
            Bool issue = input("issue", Bool.TYPE), flush = canonical ? Bool.of(false) : input("flush", Bool.TYPE);
            UInt inst = UInt.lit(1, 7).cat(UInt.lit(0, 10)).cat(f3).cat(UInt.lit(0, 5)).cat(UInt.lit(0x33, 7));
            var dec = Rv32i.decode(d, inst, false);
            Bool busy = reg("md_busy", Bool.TYPE, 0);
            assume(issue.and(busy).not(), "issue_only_when_free");
            var md = mulDivUnit(busy, issue, tag, inst, a, b, false);
            Hw.when(flush, () -> busy.set(false));
            // the operation issued last and not flushed or delivered since, and the cycles since it issued
            Bool live = reg("g_live", Bool.TYPE, 0);
            UInt n = reg("g_cycles", UInt.w(5), 0);
            UInt ga = reg("g_a", UInt.w(32), 0), gb = reg("g_b", UInt.w(32), 0), gf3 = reg("g_f3", UInt.w(3), 0), gtag = reg("g_tag", UInt.w(4), 0);
            Hw.when(md.bus(), () -> live.set(false));
            n.set(mux(n.eq(31), n, n.addWrap(1)));
            Hw.when(issue, () -> {
                live.set(true);
                n.set(0);
                ga.set(a);
                gb.set(b);
                gf3.set(f3);
                gtag.set(tag);
            });
            Hw.when(flush, () -> live.set(false));
            if (canonical) {
                Bool started = reg("g_started", Bool.TYPE, 0);
                started.set(true);
                assume(issue.eq(started.not()), "one_operation_at_cycle_0");
                UInt gInst = UInt.lit(1, 7).cat(UInt.lit(0, 10)).cat(gf3).cat(UInt.lit(0, 5)).cat(UInt.lit(0x33, 7));
                UInt ref = Rv32i.mulDivFinish(d, Rv32i.mulDivStart(d, Rv32i.decode(d, gInst, false), ga, gb, 16), broken ? 15 : 16);
                assertThat(md.bus().not().or(md.value().eq(ref)), "bus_value_matches_combinational");
            } else {
                Bool div = gf3.bit(2);
                assertThat(md.bus().not().or(live.and(md.tag().eq(gtag)).and(md.a().eq(ga)).and(md.b().eq(gb))
                        .and(mux(div, n.eq(broken ? 15 : 16), n.eq(0)))), "bus_is_live_operation_on_time");
                // the inductive strengthening
                assertThat(busy.eq(live), "inv_busy_iff_live");
                assertThat(busy.not().or(md.isDiv().eq(div).and(md.tag().eq(gtag)).and(md.a().eq(ga)).and(md.b().eq(gb))),
                        "inv_captured");
                assertThat(busy.and(div).not().or(md.count().bit(0).not().and(md.count().le(32))
                        .and(n.zext(6).eq(md.count().shr(1)))), "inv_div_count");
                assertThat(busy.and(div.not()).not().or(n.eq(0)), "inv_mul_at_once");
            }
            halted.set(false);
            retire(new RetireInfo(Bool.of(false), inst, UInt.lit(0, 32), dec, Rv32i.execute(d, dec, UInt.lit(0, 32), a, b), a, b,
                    UInt.lit(0, 32), UInt.lit(0, 32)));
        }
    }

    /** How {@link OooSquashUnit} cancels: as C11 does, or two broken ways (negative controls). */
    enum Cancel { BY_MASK, ON_EVERY_SQUASH, NEVER }

    /**
     * The out-of-order unit with C11's branch masks ({@link RvCore#mulDivSquash}), driven as {@link OooSpecCore} drives it:
     * an operation issues only while the unit is free, with the mask of the unresolved branches older than it (free, 2
     * checkpoints); each cycle at most one branch resolves, either squashing (a mispredict) or clearing its bit, and a
     * commit-time flush may clear everything. The ghost model drops the operation when a squash names a branch older
     * than it (its ghost mask bit), also in the cycle it issues. Protocol (k-induction): whatever the unit puts on its
     * bus is the live operation with its tag and operands, on time; and a live operation is delivered on time (so an
     * operation older than a squashing branch is never cancelled).
     */
    static class OooSquashUnit extends RvCore {
        OooSquashUnit(Cancel how) {
            super(2, null);
            var d = HwDom.INSTANCE;
            int n = 2;
            UInt a = input("a", UInt.w(32)), b = input("b", UInt.w(32)), f3 = input("f3", UInt.w(3)), tag = input("tag", UInt.w(4));
            Bool issue = input("issue", Bool.TYPE), flush = input("flush", Bool.TYPE);
            Bool squash = input("squash", Bool.TYPE), clear = input("clear", Bool.TYPE);
            UInt which = input("branch", UInt.w(1));
            Bool[] issueMask = {input("mask0", Bool.TYPE), input("mask1", Bool.TYPE)};
            Bool[] branch = {which.eq(0), which.eq(1)};
            assume(squash.and(clear).not(), "one_resolution_per_cycle");
            UInt inst = UInt.lit(1, 7).cat(UInt.lit(0, 10)).cat(f3).cat(UInt.lit(0, 5)).cat(UInt.lit(0x33, 7));
            var dec = Rv32i.decode(d, inst, false);
            Bool busy = reg("md_busy", Bool.TYPE, 0);
            assume(issue.and(busy).not(), "issue_only_when_free");
            var md = mulDivUnit(busy, issue, tag, inst, a, b, false);
            Bool[] mask;
            switch (how) {
                case BY_MASK -> mask = mulDivSquash(busy, issue, issueMask, squash, clear, branch).mask();
                case ON_EVERY_SQUASH -> {
                    mask = mulDivSquash(busy, issue, issueMask, Bool.of(false), clear, branch).mask();
                    Hw.when(squash, () -> busy.set(false));
                }
                default -> mask = mulDivSquash(busy, issue, issueMask, Bool.of(false), clear, branch).mask();
            }
            Hw.when(flush, () -> busy.set(false));
            // the ghost: the live operation, its mask, the cycles since it issued
            Bool live = reg("g_live", Bool.TYPE, 0);
            UInt cyc = reg("g_cycles", UInt.w(5), 0);
            UInt ga = reg("g_a", UInt.w(32), 0), gb = reg("g_b", UInt.w(32), 0), gf3 = reg("g_f3", UInt.w(3), 0), gtag = reg("g_tag", UInt.w(4), 0);
            Bool[] gm = {reg("g_m0", Bool.TYPE, 0), reg("g_m1", Bool.TYPE, 0)};
            Hw.when(md.bus(), () -> live.set(false));
            cyc.set(mux(cyc.eq(31), cyc, cyc.addWrap(1)));
            Hw.when(issue, () -> {
                live.set(true);
                cyc.set(0);
                ga.set(a);
                gb.set(b);
                gf3.set(f3);
                gtag.set(tag);
                for (int k = 0; k < n; k++) gm[k].set(issueMask[k]);
            });
            Bool gYounger = Bool.of(false);
            for (int k = 0; k < n; k++) {
                final int kk = k;
                gYounger = gYounger.or(branch[k].and(mux(issue, issueMask[k], gm[k])));
                Hw.when(clear.and(branch[k]), () -> gm[kk].set(false));
            }
            Hw.when(squash.and(gYounger), () -> live.set(false));
            Hw.when(flush, () -> live.set(false));
            Bool div = gf3.bit(2);
            Bool due = mux(div, cyc.eq(16), cyc.eq(0));
            assertThat(md.bus().not().or(live.and(md.tag().eq(gtag)).and(md.a().eq(ga)).and(md.b().eq(gb)).and(due)),
                    "bus_is_live_operation_on_time");
            assertThat(live.and(due).not().or(md.bus()), "live_operation_delivered_on_time");
            // the inductive strengthening
            assertThat(busy.eq(live), "inv_busy_iff_live");
            assertThat(busy.not().or(md.isDiv().eq(div).and(md.tag().eq(gtag)).and(md.a().eq(ga)).and(md.b().eq(gb))), "inv_captured");
            assertThat(busy.not().or(mask[0].eq(gm[0]).and(mask[1].eq(gm[1]))), "inv_mask");
            assertThat(busy.and(div).not().or(md.count().bit(0).not().and(md.count().le(32))
                    .and(cyc.zext(6).eq(md.count().shr(1)))), "inv_div_count");
            assertThat(busy.and(div.not()).not().or(cyc.eq(0)), "inv_mul_at_once");
            halted.set(false);
            retire(new RetireInfo(Bool.of(false), inst, UInt.lit(0, 32), dec, Rv32i.execute(d, dec, UInt.lit(0, 32), a, b), a, b,
                    UInt.lit(0, 32), UInt.lit(0, 32)));
        }
    }

    /** The sign, zero and overflow fix-ups, from the unsigned quotient and remainder of the magnitudes. */
    static class Fixup extends Module {
        Fixup() {
            this(false);
        }

        /** broken: the specification says -2^31 / -1 = 0 (a negative control). */
        Fixup(boolean broken) {
            var d = HwDom.INSTANCE;
            UInt a = input("a", UInt.w(32)), b = input("b", UInt.w(32)), op = input("op", UInt.w(2));
            UInt quo = input("quo", UInt.w(32)), rem = input("rem", UInt.w(32));
            UInt f3 = UInt.lit(1, 1).cat(op);    // 4 DIV, 5 DIVU, 6 REM, 7 REMU
            UInt inst = UInt.lit(1, 7).cat(UInt.lit(0, 10)).cat(f3).cat(UInt.lit(0, 5)).cat(UInt.lit(0x33, 7));
            var h = Rv32i.mulDivStart(d, Rv32i.decode(d, inst, false), a, b, 0);
            UInt ma = h.dividend(), mb = h.divisor();   // |a|, |b| for DIV/REM; a, b for DIVU/REMU
            // quo and rem are the unsigned quotient and remainder of ma and mb
            assume(mb.eq(0).or(quo.zext(64).mul(mb.zext(64)).trunc(64).addWrap(rem.zext(64)).eq(ma.zext(64)).and(rem.lt(mb))), "unsigned_division");
            var fin = new Rv32i.MulDivHalf<>(h.a(), h.b(), h.funct3(), h.p00(), h.p01(), h.p10(), h.p11(), rem, quo, h.dividend(),
                    h.divisor(), h.negQ(), h.negR(), h.byZero());
            UInt r = Rv32i.divFinish(d, fin, 0);
            // the RISC-V specification (unprivileged ISA, "Division Operations")
            Bool zero = b.eq(0), negA = a.bit(31), negB = b.bit(31);
            Bool overflow = a.eq(0x8000_0000L).and(b.eq(0xFFFF_FFFFL));
            UInt neg = UInt.lit(0, 32).subWrap(quo), negRem = UInt.lit(0, 32).subWrap(rem);
            UInt div = mux(zero, UInt.lit(0xFFFF_FFFFL, 32), mux(overflow, UInt.lit(broken ? 0 : 0x8000_0000L, 32), mux(negA.xor(negB), neg, quo)));
            UInt divu = mux(zero, UInt.lit(0xFFFF_FFFFL, 32), quo);
            UInt remS = mux(zero, a, mux(overflow, UInt.lit(0, 32), mux(negA, negRem, rem)));
            UInt remu = mux(zero, a, rem);
            UInt spec = mux(op.eq(0), div, mux(op.eq(1), divu, mux(op.eq(2), remS, remu)));
            assertThat(r.eq(spec), "fixup_matches_spec");
        }
    }

    record Run(String name, String engine, int depth, Formal.Result result, double seconds) {}

    static Run run(String name, java.util.function.Supplier<? extends Module> design, int depth, String solver, boolean induction) {
        var e = Tessera.elaborate(design);
        var opt = Formal.Options.defaults().withSolver(solver);
        long t0 = System.nanoTime();
        var r = induction ? Formal.prove(e.circuit(), depth, opt) : Formal.bmc(e.circuit(), depth, opt);
        double s = (System.nanoTime() - t0) / 1e9;
        String kind = induction ? "k-induction" : "BMC";
        System.out.printf("DIVIDER %s %s %s depth %d: %s (%.1f s)%n", name, kind, solver, depth, r, s);
        synchronized (ROWS) {
            ROWS.put(name + "/" + kind + "/" + solver, String.format("%s,%s,%s,%d,%s,%.2f", name, kind, solver, depth, r.status(), s));
        }
        return new Run(name, solver, depth, r, s);
    }

    @Test
    void stepLemma() {
        assertEquals(Formal.Status.PASS, run("step", Step::new, 1, "bitwuzla", false).result().status());
    }

    @Test
    void canonicalScheduleMatchesCombinational() {
        assertEquals(Formal.Status.PASS, run("canonical", () -> new Control(true), 20, "bitwuzla", false).result().status());
    }

    @Test
    void protocolInduction() {
        assertEquals(Formal.Status.PROVEN, run("protocol", () -> new Control(false), 20, "bitwuzla", true).result().status());
    }

    @Test
    void fixupMatchesSpecification() {
        assertEquals(Formal.Status.PASS, run("fixup", Fixup::new, 1, "bitwuzla", false).result().status());
    }

    @Test
    void negativeControls() {
        assertEquals(Formal.Status.FAIL, run("step-broken", () -> new Step(true), 1, "bitwuzla", false).result().status());
        assertEquals(Formal.Status.FAIL, run("canonical-broken", () -> new Control(true, true), 20, "bitwuzla", false).result().status());
        assertEquals(Formal.Status.FAIL, run("protocol-broken", () -> new Control(false, true), 20, "bitwuzla", true).result().status());
        assertEquals(Formal.Status.FAIL, run("fixup-broken", () -> new Fixup(true), 1, "bitwuzla", false).result().status());
    }

    /**
     * The unit is not safe against a cancelled divide: if a held divide is dropped while the unit runs, it finishes
     * anyway and a later divide can take its result. No core can do this now (the divider is in "memory", every flush
     * is in decode or execute, and a flush cancels only younger stages), so the protocol assumption holds there; a
     * core that can cancel the divider's stage must not reuse the unit as it is. This FAIL is expected.
     */
    @Test
    void cancelledDivideIsNotHandled() {
        assertEquals(Formal.Status.FAIL, run("protocol-flush", () -> new Control(false, false, true), 40, "bitwuzla", false).result().status());
    }

    @Test
    void oooProtocolInduction() {
        assertEquals(Formal.Status.PROVEN, run("ooo-protocol", () -> new OooUnit(false, false), 20, "bitwuzla", true).result().status());
    }

    @Test
    void oooCanonicalMatchesCombinational() {
        assertEquals(Formal.Status.PASS, run("ooo-canonical", () -> new OooUnit(true, false), 20, "bitwuzla", false).result().status());
    }

    @Test
    void oooNegativeControls() {
        assertEquals(Formal.Status.FAIL, run("ooo-protocol-broken", () -> new OooUnit(false, true), 20, "bitwuzla", true).result().status());
        assertEquals(Formal.Status.FAIL, run("ooo-canonical-broken", () -> new OooUnit(true, true), 20, "bitwuzla", false).result().status());
    }

    /** C11's cancel by branch mask: proved unbounded; cancelling on every squash, or never, fails (negative controls). */
    @Test
    void oooSquashInduction() {
        assertEquals(Formal.Status.PROVEN, run("ooo-squash", () -> new OooSquashUnit(Cancel.BY_MASK), 20, "bitwuzla", true).result().status());
        assertEquals(Formal.Status.PROVEN, run("ooo-squash", () -> new OooSquashUnit(Cancel.BY_MASK), 20, "yices-smt2", true).result().status());
    }

    @Test
    void oooSquashNegativeControls() {
        assertEquals(Formal.Status.FAIL, run("ooo-squash-every", () -> new OooSquashUnit(Cancel.ON_EVERY_SQUASH), 20, "bitwuzla", true).result().status());
        assertEquals(Formal.Status.FAIL, run("ooo-squash-never", () -> new OooSquashUnit(Cancel.NEVER), 20, "bitwuzla", true).result().status());
    }

    /**
     * The step, fix-up and protocol lemmas with a second solver. The canonical lemmas are not cross-checked: yices had
     * not finished the in-order one after 10 minutes (bitwuzla closes it in under a second, by word-level rewriting of
     * the two identical 32-step chains), so the out-of-order one was not attempted.
     */
    @Test
    void crossCheckYices() {
        assertEquals(Formal.Status.PASS, run("step", Step::new, 1, "yices-smt2", false).result().status());
        assertEquals(Formal.Status.PASS, run("fixup", Fixup::new, 1, "yices-smt2", false).result().status());
        assertEquals(Formal.Status.PROVEN, run("protocol", () -> new Control(false), 20, "yices-smt2", true).result().status());
        assertEquals(Formal.Status.PROVEN, run("ooo-protocol", () -> new OooUnit(false, false), 20, "yices-smt2", true).result().status());
    }

    @AfterAll
    static void write() throws Exception {
        Path dir = Path.of(System.getProperty("resultsDir", Path.of(System.getProperty("user.dir")).getParent().resolve("build/results").toString()));
        Files.createDirectories(dir);
        var lines = new java.util.ArrayList<String>(java.util.List.of("lemma,engine,solver,depth,result,seconds"));
        lines.addAll(ROWS.values());
        Files.write(dir.resolve("divider-proofs.csv"), lines);
    }
}
