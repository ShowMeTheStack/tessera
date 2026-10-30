package org.eninehq.tessera.stdlibtests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.Random;
import org.eninehq.tessera.core.Tessera;
import org.eninehq.tessera.emit.SvEmitter;
import org.eninehq.tessera.sim.BatchSim;
import org.eninehq.tessera.sim.Sim;
import org.eninehq.tessera.stdlib.TrueDualPortRam;
import org.eninehq.tessera.test.Yosys;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** The true dual-port RAM ({@code Mem.port}): read-first semantics in every simulator, collisions stop them, the SV maps to block RAM. */
class TrueDualPortRamTest {
    static final int DEPTH = 64, WIDTH = 32;

    /** One cycle of random stimulus; never both ports writing one address. */
    record Op(int addrA, boolean enA, boolean weA, long dA, int addrB, boolean enB, boolean weB, long dB) {
        static Op random(Random r) {
            int a = r.nextInt(8), b = r.nextInt(8); // few addresses: many same-address reads and read/write pairs
            boolean enA = r.nextInt(4) != 0, weA = r.nextBoolean(), enB = r.nextInt(4) != 0, weB = r.nextBoolean();
            if (a == b && enA && weA && enB && weB) weB = false;
            return new Op(a, enA, weA, r.nextInt() & 0xFFFFFFFFL, b, enB, weB, r.nextInt() & 0xFFFFFFFFL);
        }
    }

    /** The reference: both ports read the word before this edge's writes. */
    static final class Model {
        final long[] mem = new long[DEPTH];
        long qa, qb;

        void step(Op o) {
            long oldA = mem[o.addrA()], oldB = mem[o.addrB()];
            if (o.enA()) qa = oldA;
            if (o.enB()) qb = oldB;
            if (o.enA() && o.weA()) mem[o.addrA()] = o.dA();
            if (o.enB() && o.weB()) mem[o.addrB()] = o.dB();
        }
    }

    @ParameterizedTest
    @EnumSource(Sim.Backend.class)
    void readFirstOnBothPorts(Sim.Backend backend) {
        var e = Tessera.elaborate(() -> new TrueDualPortRam(DEPTH, WIDTH));
        var r = new Random(7);
        var model = new Model();
        int sameAddrReadWrite = 0;
        try (var sim = Sim.create(e, backend)) {
            sim.reset(1);
            for (int t = 0; t < 5000; t++) {
                var o = Op.random(r);
                poke(sim, o);
                if (o.addrA() == o.addrB() && o.enA() && o.enB() && (o.weA() != o.weB())) sameAddrReadWrite++;
                sim.step();
                model.step(o);
                assertEquals(model.qa, sim.peek("rdata_a"), "port a at cycle " + t);
                assertEquals(model.qb, sim.peek("rdata_b"), "port b at cycle " + t);
            }
        }
        assertTrue(sameAddrReadWrite > 100, "the stimulus reads a word on one port while the other writes it: " + sameAddrReadWrite);
    }

    static void poke(org.eninehq.tessera.sim.Simulator sim, Op o) {
        sim.poke("addr_a", o.addrA());
        sim.poke("en_a", o.enA() ? 1 : 0);
        sim.poke("we_a", o.weA() ? 1 : 0);
        sim.poke("wdata_a", o.dA());
        sim.poke("addr_b", o.addrB());
        sim.poke("en_b", o.enB() ? 1 : 0);
        sim.poke("we_b", o.weB() ? 1 : 0);
        sim.poke("wdata_b", o.dB());
    }

    @Test
    void batchSimMatchesTheModelInEveryLane() {
        var e = Tessera.elaborate(() -> new TrueDualPortRam(DEPTH, WIDTH));
        var sim = new BatchSim(e.circuit());
        var r = new Random[BatchSim.LANES];
        var m = new Model[BatchSim.LANES];
        for (int l = 0; l < BatchSim.LANES; l++) {
            r[l] = new Random(100 + l);
            m[l] = new Model();
        }
        sim.pokeAll("reset", 1);
        sim.step();
        sim.pokeAll("reset", 0);
        for (int t = 0; t < 1000; t++) {
            var ops = new Op[BatchSim.LANES];
            for (int l = 0; l < BatchSim.LANES; l++) {
                var o = ops[l] = Op.random(r[l]);
                sim.poke("addr_a", l, o.addrA());
                sim.poke("en_a", l, o.enA() ? 1 : 0);
                sim.poke("we_a", l, o.weA() ? 1 : 0);
                sim.poke("wdata_a", l, o.dA());
                sim.poke("addr_b", l, o.addrB());
                sim.poke("en_b", l, o.enB() ? 1 : 0);
                sim.poke("we_b", l, o.weB() ? 1 : 0);
                sim.poke("wdata_b", l, o.dB());
            }
            sim.step();
            for (int l = 0; l < BatchSim.LANES; l++) {
                m[l].step(ops[l]);
                assertEquals(m[l].qa, sim.peek("rdata_a", l), "lane " + l + " port a, cycle " + t);
                assertEquals(m[l].qb, sim.peek("rdata_b", l), "lane " + l + " port b, cycle " + t);
            }
        }
    }

    /** Both ports writing one word in a cycle is undefined: every simulator stops on it. */
    @ParameterizedTest
    @EnumSource(Sim.Backend.class)
    void writeCollisionStopsTheSimulator(Sim.Backend backend) {
        var e = Tessera.elaborate(() -> new TrueDualPortRam(DEPTH, WIDTH));
        try (var sim = Sim.create(e, backend)) {
            sim.reset(1);
            poke(sim, new Op(5, true, true, 1, 5, true, true, 2));
            var err = assertThrows(AssertionError.class, sim::step);
            assertTrue(err.getMessage().contains("mem_ports_write_one_word"), err.getMessage());
        }
    }

    @Test
    void writeCollisionStopsBatchSim() {
        var e = Tessera.elaborate(() -> new TrueDualPortRam(DEPTH, WIDTH));
        var sim = new BatchSim(e.circuit());
        sim.pokeAll("reset", 1);
        sim.step();
        sim.pokeAll("reset", 0);
        for (var p : new String[]{"en_a", "we_a", "en_b", "we_b"}) sim.pokeAll(p, 1);
        sim.pokeAll("addr_a", 3);
        sim.pokeAll("addr_b", 4);
        sim.poke("addr_b", 9, 3);   // only lane 9 collides
        var err = assertThrows(AssertionError.class, sim::step);
        assertTrue(err.getMessage().contains("lane 9"), err.getMessage());
    }

    /** The SV is the vendors' template: one block per port, each reading and writing its own address. */
    @Test
    void emitsOneBlockPerPort() {
        var e = Tessera.elaborate(() -> new TrueDualPortRam(1024, 32));
        String sv = SvEmitter.emit(e.circuit());
        assertEquals(2, sv.split("always @\\(posedge clock\\) begin\\n    if \\(en_", -1).length - 1, sv);
        assertTrue(sv.contains("if (we_a) mem[addr_a] <= wdata_a;"), sv);
        assertTrue(sv.contains("mem_qa <= mem[addr_a];"), sv);
    }

    /** Yosys maps it to one RAMB36E1 (via $__XILINX_BLOCKRAM_TDP_), with no logic around it. */
    @Test
    void yosysInfersOneTrueDualPortBlockRam() throws Exception {
        assumeTrue(Yosys.available(), "yosys not installed");
        var e = Tessera.elaborate(() -> new TrueDualPortRam(1024, 32));
        var st = Yosys.synth(e.circuit(), "tdp_ram", "synth_xilinx -top {top} -family xc7");
        assertEquals(1, st.byType().getOrDefault("RAMB36E1", 0), st.byType().toString());
        assertEquals(0, st.byType().getOrDefault("RAMB18E1", 0), st.byType().toString());
        // and the memory is mapped as true dual port (both ports read and write), not as simple dual port
        var dir = org.eninehq.tessera.test.Verilator.workDir("tdp_ram");
        var p = new ProcessBuilder("yosys", "-p", "read_verilog -sv tdp_ram.sv; synth_xilinx -top TrueDualPortRam -family xc7 -run :map_ffs")
                .directory(dir.toFile()).redirectErrorStream(true).start();
        String log = new String(p.getInputStream().readAllBytes());
        assertEquals(0, p.waitFor());
        assertTrue(log.contains("mapping memory TrueDualPortRam.mem via $__XILINX_BLOCKRAM_TDP_"), log);
    }
}
