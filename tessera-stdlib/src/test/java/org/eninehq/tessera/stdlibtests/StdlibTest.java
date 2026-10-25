package org.eninehq.tessera.stdlibtests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.eninehq.tessera.core.Bool;
import org.eninehq.tessera.core.Hw;
import org.eninehq.tessera.core.Module;
import org.eninehq.tessera.core.Tessera;
import org.eninehq.tessera.core.UInt;
import org.eninehq.tessera.sim.MultiClock;
import org.eninehq.tessera.sim.Sim;
import org.eninehq.tessera.sim.Simulator;
import org.eninehq.tessera.stdlib.Arbiter;
import org.eninehq.tessera.stdlib.AsyncFifo;
import org.eninehq.tessera.stdlib.Fifo;
import org.eninehq.tessera.stdlib.Ram;
import org.eninehq.tessera.stdlib.RegFile;
import org.eninehq.tessera.stdlib.Util;
import org.eninehq.tessera.test.Verilator;
import org.eninehq.tessera.test.Yosys;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class StdlibTest {

    /** Random producer/consumer through a FIFO: order and contents are preserved, nothing is lost. */
    @ParameterizedTest(name = "depth={0} fallThrough={1}")
    @CsvSource({"1,false", "2,false", "3,false", "4,true", "5,true", "16,false"})
    void fifoPreservesOrder(int depth, boolean ft) {
        var e = Tessera.elaborate(() -> new Fifo<>(UInt.w(8), depth, ft));
        var rnd = new Random(depth * 31L + (ft ? 1 : 0));
        var model = new ArrayDeque<Long>();
        var sent = new ArrayList<Long>();
        var got = new ArrayList<Long>();
        try (var sim = Sim.create(e)) {
            sim.reset(1);
            long next = 0;
            for (int t = 0; t < 2000; t++) {
                boolean v = rnd.nextInt(3) != 0, r = rnd.nextInt(3) != 0;
                sim.poke("in_valid", v ? 1 : 0);
                sim.poke("in_bits", next & 0xFF);
                sim.poke("out_ready", r ? 1 : 0);
                boolean inFire = v && sim.peek("in_ready") == 1;
                boolean outFire = r && sim.peek("out_valid") == 1;
                if (outFire) got.add(sim.peek("out_bits"));
                if (inFire) {
                    sent.add(next & 0xFF);
                    next++;
                }
                assertTrue(sim.peek("count") <= depth);
                sim.step();
            }
        }
        assertTrue(got.size() > 500, "throughput: " + got.size());
        for (int i = 0; i < got.size(); i++) assertEquals(sent.get(i), got.get(i), "item " + i);
        assertTrue(sent.size() - got.size() <= depth);
    }

    @Test
    void fifoFullThroughput() {
        var e = Tessera.elaborate(() -> new Fifo<>(UInt.w(8), 2));
        try (var sim = Sim.create(e)) {
            sim.reset(1);
            sim.poke("in_valid", 1);
            sim.poke("out_ready", 1);
            int fires = 0;
            for (int t = 0; t < 100; t++) {
                if (sim.peek("out_valid") == 1) fires++;
                assertEquals(1, sim.peek("in_ready"), "cycle " + t);
                sim.step();
            }
            assertTrue(fires >= 99, "one item per cycle: " + fires);
        }
    }

    @Test
    void asyncFifoAcrossUnrelatedClocks() {
        var e = Tessera.elaborate(() -> new AsyncFifo<>(UInt.w(8), 8));
        var rnd = new Random(4);
        var sent = new ArrayList<Long>();
        var got = new ArrayList<Long>();
        try (Simulator sim = Sim.create(e)) {
            sim.poke("wr_reset", 1);
            sim.poke("rd_reset", 1);
            sim.stepClocks(sim.clocks());
            sim.stepClocks(sim.clocks());
            sim.poke("wr_reset", 0);
            sim.poke("rd_reset", 0);
            var mc = new MultiClock(sim).clock("wr_clock", 7).clock("rd_clock", 11);
            long[] next = {0};
            for (int step = 0; step < 3000; step++) {
                sim.poke("in_valid", rnd.nextInt(4) != 0 ? 1 : 0);
                sim.poke("in_bits", next[0] & 0xFF);
                sim.poke("out_ready", rnd.nextInt(4) != 0 ? 1 : 0);
                boolean inFire = sim.peek("in_valid") == 1 && sim.peek("in_ready") == 1;
                boolean outFire = sim.peek("out_valid") == 1 && sim.peek("out_ready") == 1;
                long outBits = sim.peek("out_bits");
                var ticked = mc.next();
                if (ticked.contains("wr_clock") && inFire) {
                    sent.add(next[0] & 0xFF);
                    next[0]++;
                }
                if (ticked.contains("rd_clock") && outFire) got.add(outBits);
            }
        }
        assertTrue(got.size() > 300, "transferred " + got.size());
        for (int i = 0; i < got.size(); i++) assertEquals(sent.get(i), got.get(i), "item " + i);
    }

    @Test
    void asyncFifoPassesCdcLintAndVerilator() throws Exception {
        var e = Tessera.elaborate(() -> new AsyncFifo<>(UInt.w(8), 4));
        assumeTrue(Verilator.available());
        Verilator.lint(e.circuit(), "lint-AsyncFifo");
    }

    @Test
    void roundRobinIsFair() {
        var e = Tessera.elaborate(() -> new Arbiter<>(UInt.w(4), 3, Arbiter.Policy.ROUND_ROBIN));
        try (var sim = Sim.create(e)) {
            sim.reset(1);
            int[] grants = new int[3];
            for (int i = 0; i < 3; i++) {
                sim.poke("in_" + i + "_valid", 1);
                sim.poke("in_" + i + "_bits", i + 5);
            }
            sim.poke("out_ready", 1);
            for (int t = 0; t < 300; t++) {
                int c = (int) sim.peek("chosen");
                assertEquals(c + 5, sim.peek("out_bits"));
                assertEquals(1, sim.peek("in_" + c + "_ready"));
                grants[c]++;
                sim.step();
            }
            for (int g : grants) assertEquals(100, g);
        }
    }

    @Test
    void priorityPrefersLowestIndex() {
        var e = Tessera.elaborate(() -> new Arbiter<>(UInt.w(4), 3, Arbiter.Policy.PRIORITY));
        try (var sim = Sim.create(e)) {
            sim.poke("in_1_valid", 1);
            sim.poke("in_2_valid", 1);
            sim.poke("out_ready", 1);
            assertEquals(1, sim.peek("chosen"));
            sim.poke("in_0_valid", 1);
            assertEquals(0, sim.peek("chosen"));
            assertEquals(0, sim.peek("in_1_ready"));
        }
    }

    @Test
    void regFileWithZeroRegisterAndBypass() {
        var e = Tessera.elaborate(() -> new RegFile(32, 32, 2, 1, true, true));
        try (var sim = Sim.create(e)) {
            sim.poke("w0_en", 1);
            sim.poke("w0_addr", 5);
            sim.poke("w0_data", 1234);
            sim.poke("r0_addr", 5);
            assertEquals(1234, sim.peek("r0_data"), "bypass");
            sim.step();
            sim.poke("w0_addr", 0);
            sim.poke("w0_data", 99);
            sim.poke("r1_addr", 0);
            assertEquals(0, sim.peek("r1_data"));
            sim.step();
            sim.poke("w0_en", 0);
            assertEquals(0, sim.peek("r1_data"));
            assertEquals(1234, sim.peek("r0_data"));
        }
    }

    @Test
    void ramReadsOneCycleLaterAndMapsToBram() throws Exception {
        var e = Tessera.elaborate(() -> Ram.simpleDualPortBytes(2048, 32, new long[]{0x11223344L}));
        try (var sim = Sim.create(e)) {
            sim.poke("re", 1);
            sim.poke("raddr", 0);
            sim.step();
            assertEquals(0x11223344L, sim.peek("rdata"));
            sim.poke("we", 1);
            sim.poke("waddr", 0);
            sim.poke("wdata", 0xAABBCCDDL);
            sim.poke("wstrb", 0b0101);
            sim.step();
            sim.poke("we", 0);
            sim.step();
            assertEquals(0x11BB33DDL, sim.peek("rdata"));
        }
        assumeTrue(Yosys.available());
        var stats = Yosys.synth(e.circuit(), "synth-ram", "synth_xilinx -family xc7 -top {top}");
        assertTrue(stats.byType().keySet().stream().anyMatch(k -> k.startsWith("RAMB")), "expected block RAM: " + stats.byType());
    }

    static class Utils extends Module {
        Utils() {
            UInt x = input("x", UInt.w(8));
            var pe = Util.priorityEncoder(x);
            output("pe", UInt.w(3)).set(pe.bits());
            output("pe_valid", Bool.TYPE).set(pe.valid());
            output("pop", UInt.w(4)).set(Util.popCount(x));
            output("rev", UInt.w(8)).set(Util.reverse(x));
            output("gray_rt", UInt.w(8)).set(Util.fromGray(Util.toGray(x)));
            output("onehot", UInt.w(8)).set(Util.oneHot(x.bits(2, 0), 8));
            var c = Util.counter(4, Hw.TRUE);
            output("count", UInt.w(3)).set(c.value());
            output("wrap", Bool.TYPE).set(c.wrap());
            output("lfsr", UInt.w(5)).set(Util.lfsr(5, Hw.TRUE));
            output("rise", Bool.TYPE).set(Util.risingEdge(x.bit(0)));
        }
    }

    @Test
    void utilities() {
        var e = Tessera.elaborate(Utils::new);
        try (var sim = Sim.create(e)) {
            sim.reset(1);
            for (int x = 0; x < 256; x++) {
                sim.poke("x", x);
                assertEquals(x == 0 ? 0 : Integer.numberOfTrailingZeros(x), sim.peek("pe"));
                assertEquals(x != 0 ? 1 : 0, sim.peek("pe_valid"));
                assertEquals(Integer.bitCount(x), sim.peek("pop"));
                assertEquals(Integer.reverse(x) >>> 24, sim.peek("rev"));
                assertEquals(x, sim.peek("gray_rt"));
                assertEquals(1L << (x & 7), sim.peek("onehot"));
            }
            var seen = new java.util.HashSet<Long>();
            List<Long> counts = new ArrayList<>();
            for (int t = 0; t < 31; t++) {
                seen.add(sim.peek("lfsr"));
                counts.add(sim.peek("count"));
                sim.step();
            }
            assertEquals(31, seen.size(), "maximal-length LFSR");
            assertEquals(List.of(0L, 1L, 2L, 3L, 4L, 0L, 1L), counts.subList(0, 7));
        }
    }
}
