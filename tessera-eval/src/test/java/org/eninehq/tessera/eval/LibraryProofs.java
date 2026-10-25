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
import org.eninehq.tessera.core.Elaborated;
import org.eninehq.tessera.core.Module;
import org.eninehq.tessera.core.Tessera;
import org.eninehq.tessera.core.UInt;
import org.eninehq.tessera.formal.Formal;
import org.eninehq.tessera.formal.YosysFormal;
import org.eninehq.tessera.stdlib.Arbiter;
import org.eninehq.tessera.stdlib.Fifo;
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

    record Case(String name, Supplier<? extends Module> design, int bmcDepth, int k) {
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
                new Case("debounce, 3 cycles", Debounce::new, 16, 6),
                new Case("LFSR, 16 bits", Lfsr::new, 16, 1));
    }

    static final Map<String, String> ROWS = new TreeMap<>();

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    @org.junit.jupiter.api.Timeout(value = 10, unit = java.util.concurrent.TimeUnit.MINUTES)
    void proves(Case c) throws Exception {
        assumeTrue(YosysFormal.available(), "needs yices and yosys-smtbmc");
        Elaborated<? extends Module> e = Tessera.elaborate(c.design());
        // yices for both engines, so the comparison with yosys-smtbmc is like for like
        var opt = Formal.Options.defaults().withSolver("yices-smt2");
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
            var y = YosysFormal.bmc(e.circuit(), dir, c.bmcDepth(), "yices");
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
