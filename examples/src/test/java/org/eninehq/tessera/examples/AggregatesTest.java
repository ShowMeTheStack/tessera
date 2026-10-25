package org.eninehq.tessera.examples;

import static org.eninehq.tessera.core.Hw.when;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.ArrayList;
import java.util.Random;
import org.eninehq.tessera.core.Bits;
import org.eninehq.tessera.core.Bool;
import org.eninehq.tessera.core.Bundle;
import org.eninehq.tessera.core.Hw;
import org.eninehq.tessera.core.HwUnion;
import org.eninehq.tessera.core.Module;
import org.eninehq.tessera.core.SInt;
import org.eninehq.tessera.core.Shape;
import org.eninehq.tessera.core.Tessera;
import org.eninehq.tessera.core.TesseraException;
import org.eninehq.tessera.core.UInt;
import org.eninehq.tessera.core.Vec;
import org.eninehq.tessera.emit.SvEmitter;
import org.eninehq.tessera.ir.Signal;
import org.eninehq.tessera.sim.Sim;
import org.eninehq.tessera.test.Verilator;
import org.junit.jupiter.api.Test;

/** Bundles, flipped fields, Vecs, tagged unions and raw bit vectors. */
class AggregatesTest {

    record Req(UInt addr, UInt data, Bool write) {}

    static final Shape<Req> REQ = Bundle.of(Req.class, UInt.w(8), UInt.w(16), Bool.TYPE);

    /** A ready/valid channel: ready flows against the data. */
    record Chan(Bool valid, Bool ready, UInt bits) {}

    static Shape<Chan> chan(int w) {
        return Bundle.of(Chan.class, Bool.TYPE, Shape.flip(Bool.TYPE), UInt.w(w));
    }

    /** Passes a channel through a register slice: ready is combinational, data registered. */
    static class Slice extends Module {
        final Chan in = input("in", chan(8));
        final Chan out = output("out", chan(8));

        Slice() {
            UInt data = reg("data", UInt.w(8));
            Bool full = reg("full", Bool.TYPE, 0);
            in.ready().set(full.not().or(out.ready()));
            when(in.valid().and(in.ready()), () -> {
                data.set(in.bits());
                full.set(true);
            }).elseWhen(out.ready(), () -> full.set(false));
            out.valid().set(full);
            out.bits().set(data);
        }
    }

    static class Chain extends Module {
        Chain() {
            Chan in = input("in", chan(8));
            Chan out = output("out", chan(8));
            Slice a = instance("a", Slice::new);
            Slice b = instance("b", Slice::new);
            Hw.connect(a.in, in);
            Hw.connect(b.in, a.out);
            Hw.connect(out, b.out);
        }
    }

    @Test
    void flippedFieldsBecomeOppositePorts() {
        var e = Tessera.elaborate(Slice::new);
        var ports = e.circuit().top().ports();
        assertEquals(Signal.Kind.OUTPUT, find(ports, "in_ready").kind());
        assertEquals(Signal.Kind.INPUT, find(ports, "in_valid").kind());
        assertEquals(Signal.Kind.INPUT, find(ports, "out_ready").kind());
        assertEquals(Signal.Kind.OUTPUT, find(ports, "out_bits").kind());
    }

    private static Signal find(java.util.List<Signal> ports, String name) {
        return ports.stream().filter(p -> p.name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void connectPlugsChannelsTogether() {
        var e = Tessera.elaborate(Chain::new);
        try (var sim = Sim.create(e)) {
            sim.reset(1);
            sim.poke("out_ready", 1);
            var got = new ArrayList<Long>();
            for (int i = 0; i < 20; i++) {
                sim.poke("in_valid", i < 10 ? 1 : 0);
                sim.poke("in_bits", 100 + i);
                if (sim.peek("out_valid") == 1) got.add(sim.peek("out_bits"));
                sim.step();
            }
            assertEquals(10, got.size(), got.toString());
            for (int i = 0; i < 10; i++) assertEquals(100 + i, got.get(i));
        }
    }

    @Test
    void chainIsLintClean() throws Exception {
        assumeTrue(Verilator.available());
        Verilator.lint(Tessera.elaborate(Chain::new).circuit(), "lint-Chain");
    }

    static class BundleRegs extends Module {
        BundleRegs() {
            Req in = input("req", REQ);
            Req r = regInit("r", new Req(Hw.lit(0, 8), Hw.lit(5, 16), Bool.of(false)));
            Hw.assign(r, in);
            Bool sel = input("sel", Bool.TYPE);
            Req m = Hw.mux(sel, in, r);
            output("packed", UInt.w(25)).set(Hw.pack(m));
            Req back = REQ.unpack(Hw.pack(r));
            output("data", UInt.w(16)).set(back.data());
        }
    }

    @Test
    void bundleRegistersMuxAndPack() {
        var e = Tessera.elaborate(BundleRegs::new);
        try (var sim = Sim.create(e)) {
            sim.reset(1);
            assertEquals(5, sim.peek("data"));
            sim.poke("req_addr", 0xAB);
            sim.poke("req_data", 0x1234);
            sim.poke("req_write", 1);
            sim.poke("sel", 1);
            assertEquals((0xABL << 17) | (0x1234L << 1) | 1, sim.peek("packed"));
            sim.step();
            sim.poke("sel", 0);
            sim.poke("req_addr", 0);
            assertEquals((0xABL << 17) | (0x1234L << 1) | 1, sim.peek("packed"));
            assertEquals(0x1234, sim.peek("data"));
        }
    }

    static class VecFile extends Module {
        VecFile() {
            Vec<UInt> regs = reg("r", Vec.of(4, UInt.w(8)));
            UInt waddr = input("waddr", UInt.w(2));
            UInt wdata = input("wdata", UInt.w(8));
            Bool we = input("we", Bool.TYPE);
            UInt raddr = input("raddr", UInt.w(2));
            when(we, () -> regs.set(waddr, wdata));
            output("rdata", UInt.w(8)).set(regs.get(raddr));
            UInt sum = Hw.lit(0, 10);
            for (UInt x : regs) sum = sum.addWrap(x.zext(10));
            output("sum", UInt.w(10)).set(sum);
        }
    }

    @Test
    void vecDynamicReadAndWrite() {
        var e = Tessera.elaborate(VecFile::new);
        try (var sim = Sim.create(e)) {
            long[] model = new long[4];
            var rnd = new Random(1);
            for (int i = 0; i < 200; i++) {
                int wa = rnd.nextInt(4), ra = rnd.nextInt(4), wd = rnd.nextInt(256);
                boolean we = rnd.nextBoolean();
                sim.poke("waddr", wa);
                sim.poke("wdata", wd);
                sim.poke("we", we ? 1 : 0);
                sim.poke("raddr", ra);
                assertEquals(model[ra], sim.peek("rdata"));
                assertEquals(model[0] + model[1] + model[2] + model[3], sim.peek("sum"));
                sim.step();
                if (we) model[wa] = wd;
            }
        }
    }

    sealed interface Cmd permits Cmd.Nop, Cmd.Write, Cmd.Read {
        record Nop() implements Cmd {}
        record Write(UInt addr, UInt data) implements Cmd {}
        record Read(UInt addr) implements Cmd {}
    }

    static final HwUnion.Type<Cmd> CMD = HwUnion.of(Cmd.class,
            HwUnion.variant(Cmd.Nop.class),
            HwUnion.variant(Cmd.Write.class, UInt.w(4), UInt.w(8)),
            HwUnion.variant(Cmd.Read.class, UInt.w(4)));

    static class CmdDecoder extends Module {
        CmdDecoder() {
            UInt kind = input("kind", UInt.w(2));
            UInt addr = input("addr", UInt.w(4));
            UInt data = input("data", UInt.w(8));
            var cmd = wire("cmd", CMD);
            when(kind.eq(1), () -> cmd.set(new Cmd.Write(addr, data)))
                    .elseWhen(kind.eq(2), () -> cmd.set(new Cmd.Read(addr)))
                    .otherwise(() -> cmd.set(new Cmd.Nop()));
            UInt a = cmd.match(c -> switch (c) {
                case Cmd.Nop n -> Hw.lit(0, 4);
                case Cmd.Write(var wa, var wd) -> wa;
                case Cmd.Read(var ra) -> ra.xor(Hw.lit(0xF, 4));
            });
            output("a", UInt.w(4)).set(a);
            output("isRead", Bool.TYPE).set(cmd.is(Cmd.Read.class));
            UInt d = wireDefault(UInt.w(8), 0);
            cmd.when(c -> {
                if (c instanceof Cmd.Write w) d.set(w.data());
            });
            output("d", UInt.w(8)).set(d);
        }
    }

    @Test
    void taggedUnionMatch() {
        var e = Tessera.elaborate(CmdDecoder::new);
        assertEquals(2 + 12, find(e.circuit().top().signals(), "cmd").width());
        try (var sim = Sim.create(e)) {
            sim.poke("addr", 6);
            sim.poke("data", 0x77);
            sim.poke("kind", 0);
            assertEquals(0, sim.peek("a"));
            assertEquals(0, sim.peek("d"));
            sim.poke("kind", 1);
            assertEquals(6, sim.peek("a"));
            assertEquals(0x77, sim.peek("d"));
            assertEquals(0, sim.peek("isRead"));
            sim.poke("kind", 2);
            assertEquals(6 ^ 0xF, sim.peek("a"));
            assertEquals(1, sim.peek("isRead"));
            assertEquals(0, sim.peek("d"));
        }
    }

    @Test
    void unionMustCoverSealedPermits() {
        var ex = assertThrows(TesseraException.class, () -> HwUnion.of(Cmd.class,
                HwUnion.variant(Cmd.Nop.class), HwUnion.variant(Cmd.Read.class, UInt.w(4))));
        assertTrue(ex.getMessage().contains("permits Write"), ex.getMessage());
    }

    static class BitsOps extends Module {
        BitsOps() {
            Bits a = input("a", Bits.w(8));
            Bits b = input("b", Bits.w(8));
            output("x", Bits.w(8)).set(a.xor(b).not());
            output("hi", Bits.w(12)).set(a.bits(7, 4).cat(b));
            SInt s = a.asSInt();
            output("neg", SInt.w(9)).set(s.neg());
        }
    }

    @Test
    void bitsHaveNoArithmeticButConvert() {
        var e = Tessera.elaborate(BitsOps::new);
        try (var sim = Sim.create(e)) {
            sim.poke("a", 0xF0);
            sim.poke("b", 0x0F);
            assertEquals(0x00, sim.peek("x"));
            assertEquals(0xF0F, sim.peek("hi"));
            assertEquals(16, sim.peek("neg")); // -(-16)
        }
        assertTrue(SvEmitter.emit(e).contains("module BitsOps"));
    }
}
