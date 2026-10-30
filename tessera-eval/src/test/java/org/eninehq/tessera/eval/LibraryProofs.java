package org.eninehq.tessera.eval;

import static org.eninehq.tessera.core.Hw.assertThat;
import static org.eninehq.tessera.core.Hw.assume;
import static org.eninehq.tessera.core.Hw.mux;
import static org.eninehq.tessera.core.Hw.when;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Supplier;
import org.eninehq.tessera.core.Bool;
import org.eninehq.tessera.core.ClockDomain;
import org.eninehq.tessera.core.Elaborated;
import org.eninehq.tessera.core.Module;
import org.eninehq.tessera.core.Tessera;
import org.eninehq.tessera.core.UInt;
import org.eninehq.tessera.formal.Formal;
import org.eninehq.tessera.formal.YosysFormal;
import org.eninehq.tessera.stdlib.Arbiter;
import org.eninehq.tessera.stdlib.AsyncFifo;
import org.eninehq.tessera.stdlib.Fifo;
import org.eninehq.tessera.stdlib.LiveValueTable;
import org.eninehq.tessera.stdlib.RegFile;
import org.eninehq.tessera.stdlib.Util;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Properties of the standard library, proven with Tessera's own engine (BMC and
 * k-induction over SMT-LIB) and checked with yosys-smtbmc to the same depth for comparison.
 * Writes build/results/library-proofs.csv.
 */
class LibraryProofs {

    /** Items leave a FIFO in the order they entered, none lost or duplicated; occupancy is bounded. */
    static class FifoOrder extends Module {
        FifoOrder(int depth) {
            Bool push = input("push", Bool.TYPE), pop = input("pop", Bool.TYPE);
            Fifo<UInt> f = instance("f", () -> new Fifo<>(UInt.w(8), depth));
            UInt seqIn = reg("seq_in", UInt.w(8), 0), seqOut = reg("seq_out", UInt.w(8), 0);
            f.in.valid().set(push);
            f.in.bits().set(seqIn);
            f.out.ready().set(pop);
            when(push.and(f.in.ready()), () -> seqIn.set(seqIn.addWrap(1)));
            when(pop.and(f.out.valid()), () -> seqOut.set(seqOut.addWrap(1)));
            assertThat(f.out.valid().not().or(f.out.bits().eq(seqOut)), "fifo_order");
            assertThat(f.count.le(depth), "fifo_bound");
            assertThat(f.count.zext(8).eq(seqIn.subWrap(seqOut)), "fifo_count");
            // ready when not full, or when full and an item leaves in the same cycle
            assertThat(f.in.ready().eq(f.count.ne(depth).or(pop)), "fifo_ready_iff_room");
        }
    }

    /**
     * The asynchronous FIFO across two independent clocks: items leave in the order they entered, none lost or
     * duplicated (the read side checks sequence numbers), and the occupancy, items written minus items read, stays
     * within 0..depth (no overflow, no underflow), checked on both clocks.
     */
    static class AsyncFifoOrder extends Module {
        AsyncFifoOrder(int depth) {
            this(depth, 0);
        }

        /** {@code broken}: 1 = the writer counts a push even when the FIFO is full; 2 = the reader pops when empty (negative controls). */
        AsyncFifoOrder(int depth, int broken) {
            ClockDomain wr = clockDomain("wr"), rd = clockDomain("rd");
            AsyncFifo<UInt> f = instance("f", () -> new AsyncFifo<>(UInt.w(8), depth));
            connectClock(f.wr, wr);
            connectClock(f.rd, rd);
            Bool push = input("push", Bool.TYPE), pop = input("pop", Bool.TYPE);
            UInt seqIn = withClock(wr, () -> reg("seq_in", UInt.w(8), 0));
            UInt seqOut = withClock(rd, () -> reg("seq_out", UInt.w(8), 0));
            f.in.valid().set(push);
            f.in.bits().set(seqIn);
            f.out.ready().set(pop);
            when(broken == 1 ? push : push.and(f.in.ready()), () -> seqIn.set(seqIn.addWrap(1)));
            when(broken == 2 ? pop : pop.and(f.out.valid()), () -> seqOut.set(seqOut.addWrap(1)));
            UInt occupancy = seqIn.subWrap(seqOut);
            withClock(rd, () -> {
                assertThat(f.out.valid().not().or(f.out.bits().eq(seqOut)), "async_fifo_order");
                assertThat(occupancy.le(depth), "async_fifo_bound_rd");
            });
            withClock(wr, () -> assertThat(occupancy.le(depth), "async_fifo_bound_wr"));
        }
    }

    /** Round robin: grants only to requesters, and a waiting requester is served within n cycles. */
    static class RoundRobin extends Module {
        RoundRobin(int n) {
            Arbiter<UInt> a = instance("a", () -> new Arbiter<>(UInt.w(4), n, Arbiter.Policy.ROUND_ROBIN));
            Bool[] req = new Bool[n];
            for (int i = 0; i < n; i++) {
                req[i] = input("req" + i, Bool.TYPE);
                a.in.get(i).valid().set(req[i]);
                a.in.get(i).bits().set(UInt.lit(i, 4));
            }
            Bool ready = input("ready", Bool.TYPE);
            a.out.ready().set(ready);
            for (int i = 0; i < n; i++) {
                Bool granted = a.in.get(i).ready();
                assertThat(granted.not().or(req[i]), "grant_only_to_requester_" + i);
                assertThat(granted.not().or(a.chosen.eq(i)), "grant_matches_chosen_" + i);
            }
            assertThat(a.out.valid().not().or(a.out.bits().eq(a.chosen.zext(4))), "data_from_chosen");
            // fairness for input 0: it keeps requesting until served, the sink is always ready
            assume(ready, "sink_always_ready");
            Bool req0 = req[0], served0 = a.in.get(0).ready();
            Bool waiting = reg("waiting", Bool.TYPE, 0);
            UInt waited = reg("waited", UInt.w(4), 0);
            assume(waiting.not().or(req0), "requester_0_holds_its_request");
            waiting.set(req0.and(served0.not()));
            waited.set(mux(req0.and(served0.not()), waited.addWrap(1), UInt.lit(0, 4)));
            assertThat(waited.lt(n), "served_within_n_cycles");
        }
    }

    /** Gray code: decoding inverts encoding, and successive codes differ in exactly one bit. */
    static class Gray extends Module {
        Gray() {
            UInt x = input("x", UInt.w(8));
            assertThat(Util.fromGray(Util.toGray(x)).eq(x), "gray_inverse");
            UInt diff = Util.toGray(x).xor(Util.toGray(x.addWrap(1)));
            assertThat(Util.popCount(diff).eq(1), "gray_one_bit_step");
        }
    }

    /** The priority encoder returns the lowest set bit. */
    static class Priority extends Module {
        Priority() {
            UInt x = input("x", UInt.w(16));
            var p = Util.priorityEncoder(x);
            assertThat(p.valid().eq(x.ne(0)), "valid_iff_any_bit");
            UInt below = UInt.lit(1, 16).dshl(p.bits()).subWrap(1);
            assertThat(p.valid().not().or(x.dshr(p.bits()).bit(0).and(x.and(below).eq(0))), "lowest_set_bit");
        }
    }

    /** A register file returns the last value written to an address (tracked for one arbitrary address). */
    static class RegFileRaw extends Module {
        RegFileRaw() {
            RegFile rf = instance("rf", () -> new RegFile(8, 8, 1, 1, false, false));
            var w = rf.write.getFirst();
            var r = rf.read.getFirst();
            Bool we = input("we", Bool.TYPE);
            UInt waddr = input("waddr", UInt.w(3)), wdata = input("wdata", UInt.w(8)), raddr = input("raddr", UInt.w(3));
            w.en().set(we);
            w.addr().set(waddr);
            w.data().set(wdata);
            r.addr().set(raddr);
            // an arbitrary address, chosen in the first cycle and fixed afterwards
            UInt sel = input("sel", UInt.w(3));
            Bool chosen = reg("chosen", Bool.TYPE, 0);
            UInt at = reg("at", UInt.w(3), 0);
            when(chosen.not(), () -> {
                at.set(sel);
                chosen.set(true);
            });
            Bool known = reg("known", Bool.TYPE, 0);
            UInt shadow = reg("shadow", UInt.w(8), 0);
            when(chosen.and(we).and(waddr.eq(at)), () -> {
                known.set(true);
                shadow.set(wdata);
            });
            assertThat(known.and(raddr.eq(at)).not().or(r.data().eq(shadow)), "read_after_write");
        }
    }

    /**
     * The live-value-table register file is the register array it replaces: two write ports (the later one,
     * port 1, wins when both write one register, as in program order), {@code reads} combinational reads, with or
     * without x0. The shadow-copy pattern of {@link RegFileRaw} for one arbitrary register {@code at}, with the second
     * write port; built in this module ({@link LiveValueTable}) so that the strengthening assertions can name the
     * table and the banks. Those are asserted (so proven too), not assumed: the table entry of {@code at} names the
     * port that wrote it last, and every copy of that port's bank holds the value at {@code at}. Without them
     * k-induction cannot close (copies of one bank could differ in an unreachable state).
     * With {@code port0Wins} the reference is wrong for same-cycle writes: a negative control that must fail.
     */
    static class LvtRaw extends Module {
        LvtRaw(int count, int width, int reads, boolean zero, boolean aided, boolean port0Wins) {
            int aw = Math.max(1, 32 - Integer.numberOfLeadingZeros(count - 1));
            Bool we0 = input("we0", Bool.TYPE), we1 = input("we1", Bool.TYPE);
            UInt wa0 = input("wa0", UInt.w(aw)), wa1 = input("wa1", UInt.w(aw));
            UInt wd0 = input("wd0", UInt.w(width)), wd1 = input("wd1", UInt.w(width));
            var lvt = new LiveValueTable("rf", count, width, zero);
            when(we0, () -> lvt.write(wa0, wd0));
            when(we1, () -> lvt.write(wa1, wd1));
            UInt[] ra = new UInt[reads], rd = new UInt[reads];
            for (int r = 0; r < reads; r++) {
                ra[r] = input("ra" + r, UInt.w(aw));
                rd[r] = lvt.read(ra[r]);
            }
            lvt.close();
            // an arbitrary register, chosen in the first cycle and fixed afterwards
            UInt sel = input("sel", UInt.w(aw));
            Bool chosen = reg("chosen", Bool.TYPE, 0);
            UInt at = reg("at", UInt.w(aw), 0);
            when(chosen.not(), () -> {
                at.set(sel);
                chosen.set(true);
            });
            // the register array: the last write to `at`, port 1 after port 0 (x0 is never written)
            Bool writable = zero ? at.ne(0) : Bool.of(true);
            Bool hit0 = chosen.and(we0).and(wa0.eq(at)).and(writable), hit1 = chosen.and(we1).and(wa1.eq(at)).and(writable);
            Bool known = reg("known", Bool.TYPE, 0), last = reg("last", Bool.TYPE, 0);
            UInt shadow = reg("shadow", UInt.w(width), 0);
            Bool second = port0Wins ? hit1.and(hit0.not()) : hit1;
            when(hit0.or(hit1), () -> {
                known.set(true);
                shadow.set(mux(second, wd1, wd0));
                last.set(second);
            });
            for (int r = 0; r < reads; r++) {
                assertThat(known.and(ra[r].eq(at)).not().or(rd[r].eq(shadow)), "read_" + r + "_returns_last_write");
                if (zero) assertThat(ra[r].ne(0).or(rd[r].eq(0)), "read_" + r + "_x0_is_zero");
            }
            if (aided) {
                assertThat(known.not().or(chosen), "known_only_after_choosing");
                // x0 is never written, so it is never known (else an unreachable state with a stale shadow for x0)
                if (zero) assertThat(known.not().or(at.ne(0)), "x0_never_known");
                assertThat(known.not().or(lvt.table().read(at).eq(mux(last, UInt.lit(1, 1), UInt.lit(0, 1)))), "table_names_last_writer");
                for (int w = 0; w < 2; w++)
                    for (int r = 0; r < reads; r++) {
                        Bool mine = w == 1 ? last : last.not();
                        assertThat(known.and(mine).not().or(lvt.copy(w, r).read(at).eq(shadow)), "bank_" + w + "_copy_" + r + "_holds_it");
                    }
            }
        }
    }

    /** Debounce: the output follows the input only after it has been stable for 3 cycles. */
    static class Debounce extends Module {
        Debounce() {
            Bool in = input("in", Bool.TYPE);
            Bool out = Util.debounce(in, 3);
            Bool prevOut = reg("prev_out", Bool.TYPE, 0);
            prevOut.set(out);
            UInt stable = reg("stable_for", UInt.w(3), 0);
            Bool prevIn = reg("prev_in", Bool.TYPE, 0);
            prevIn.set(in);
            stable.set(mux(in.ne(prevIn), UInt.lit(0, 3), mux(stable.eq(7), stable, stable.addWrap(1))));
            assertThat(out.eq(prevOut).or(stable.ge(3)), "changes_only_when_stable");
            output("o", Bool.TYPE).set(out);
        }
    }

    /** The maximal-length LFSR never reaches zero. */
    static class Lfsr extends Module {
        Lfsr() {
            UInt r = Util.lfsr(16, input("step", Bool.TYPE));
            assertThat(r.ne(0), "never_zero");
            output("o", UInt.w(16)).set(r);
        }
    }

    /** {@code resetCycles}: cycles the reset is held (with an edge of every clock); 3 flushes 2-stage synchronizers. */
    record Case(String name, Supplier<? extends Module> design, int bmcDepth, int k, int resetCycles) {
        Case(String name, Supplier<? extends Module> design, int bmcDepth, int k) {
            this(name, design, bmcDepth, k, 1);
        }

        @Override public String toString() { return name; }
    }

    /** All cases, or those whose name contains the {@code proofs} system property. */
    static List<Case> cases() {
        String only = System.getProperty("proofs", "");
        return all().stream().filter(c -> c.name().contains(only)).toList();
    }

    static List<Case> all() {
        return List.of(
                new Case("fifo depth 4", () -> new FifoOrder(4), 12, 6),
                new Case("fifo depth 8", () -> new FifoOrder(8), 16, 10),
                new Case("round-robin arbiter, 4 inputs", () -> new RoundRobin(4), 16, 6),
                new Case("gray code, 8 bits", Gray::new, 1, 1),
                new Case("priority encoder, 16 bits", Priority::new, 1, 1),
                new Case("register file read-after-write", RegFileRaw::new, 12, 4),
                // 32 registers, 4 reads + the debug read, 2 writes, at 8 bits: this suite's yices closes the 32-bit
                // induction step only slowly (> 45 min); 32 x 32 is proven in LvtProofs with bitwuzla
                new Case("LVT register file = 2-write array, 32x8, 5 reads", () -> new LvtRaw(32, 8, 5, false, true, false), 12, 2),
                new Case("LVT register file = 2-write array, 32x8, 5 reads, x0", () -> new LvtRaw(32, 8, 5, true, true, false), 12, 2),
                new Case("debounce, 3 cycles", Debounce::new, 16, 6),
                new Case("LFSR, 16 bits", Lfsr::new, 16, 1),
                // two independent clocks; the synchronizers' flip-flops have no reset, so the reset is held 3 cycles
                new Case("async fifo depth 4, two clocks", () -> new AsyncFifoOrder(4), 16, 8, 3));
    }

    static final Map<String, String> ROWS = new TreeMap<>();

    /** Negative controls for the asynchronous FIFO's properties: each broken wrapper must fail, and the counterexample replays. */
    @ParameterizedTest(name = "broken={0}")
    @org.junit.jupiter.params.provider.ValueSource(ints = {1, 2})
    void asyncFifoPropertiesCatchMisuse(int broken) {
        assumeTrue(Formal.Options.defaults().solver() != null);
        var e = Tessera.elaborate(() -> new AsyncFifoOrder(4, broken));
        var r = Formal.bmc(e.circuit(), 16, Formal.Options.defaults().withResetCycles(3));
        org.junit.jupiter.api.Assertions.assertEquals(Formal.Status.FAIL, r.status(), r.toString());
        org.junit.jupiter.api.Assertions.assertNotNull(r.replay(null), "replays in simulation: " + r);
        System.out.println("async fifo broken=" + broken + ": " + r);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    @org.junit.jupiter.api.Timeout(value = 10, unit = java.util.concurrent.TimeUnit.MINUTES)
    void proves(Case c) throws Exception {
        assumeTrue(YosysFormal.available(), "needs yices and yosys-smtbmc");
        Elaborated<? extends Module> e = Tessera.elaborate(c.design());
        // yices for both engines, so the comparison with yosys-smtbmc is like for like
        var opt = Formal.Options.defaults().withSolver("yices-smt2").withResetCycles(c.resetCycles());
        long t0 = System.nanoTime();
        var bmc = Formal.bmc(e.circuit(), c.bmcDepth(), opt);
        double bmcS = (System.nanoTime() - t0) / 1e9;
        assertNotEquals(Formal.Status.FAIL, bmc.status(), bmc.toString());
        t0 = System.nanoTime();
        var ind = Formal.prove(e.circuit(), c.k(), opt);
        double indS = (System.nanoTime() - t0) / 1e9;
        String yosys = "not run";
        double yS = 0;
        if (YosysFormal.available()) {
            Path dir = Files.createTempDirectory("ysmt");
            t0 = System.nanoTime();
            var y = YosysFormal.bmc(e.circuit(), dir, c.bmcDepth(), "yices", opt);
            yS = (System.nanoTime() - t0) / 1e9;
            yosys = y.failed() ? "FAIL" : "PASS";
        }
        synchronized (ROWS) {
            ROWS.put(c.name(), String.format("\"%s\",%d,%s,%.2f,%d,%s,%.2f,%s,%.2f", c.name(), c.bmcDepth(), bmc.status(), bmcS, c.k(),
                    ind.status(), indS, yosys, yS));
        }
        System.out.printf("%s: bmc(%d) %s %.2fs, k-induction(%d) %s %.2fs, yosys-smtbmc %s %.2fs%n", c.name(), c.bmcDepth(), bmc.status(),
                bmcS, c.k(), ind.status(), indS, yosys, yS);
    }

    @AfterAll
    static void write() throws Exception {
        if (ROWS.isEmpty()) return;
        Path out = Path.of(System.getProperty("resultsDir", Path.of(System.getProperty("user.dir")).getParent().resolve("build/results").toString())).resolve("library-proofs.csv");
        if (Files.exists(out))
            for (String l : Files.readAllLines(out).subList(1, Files.readAllLines(out).size())) {
                String name = l.substring(1, l.indexOf('"', 1));
                ROWS.putIfAbsent(name, l);
            }
        try (var w = new PrintWriter(Files.newBufferedWriter(out))) {
            w.println("property,bmc_depth,tessera_bmc,tessera_bmc_s,k,tessera_k_induction,tessera_induction_s,yosys_smtbmc,yosys_s");
            ROWS.values().forEach(w::println);
        }
    }
}
