package org.eninehq.tessera.babylontests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Random;
import org.eninehq.tessera.babylon.JavaHw;
import org.eninehq.tessera.core.Bool;
import org.eninehq.tessera.core.Data;
import org.eninehq.tessera.core.Hw;
import org.eninehq.tessera.core.HwEnum;
import org.eninehq.tessera.core.Module;
import org.eninehq.tessera.core.Tessera;
import org.eninehq.tessera.core.TesseraException;
import org.eninehq.tessera.core.UInt;
import org.eninehq.tessera.emit.SvEmitter;
import org.eninehq.tessera.examples.Alu;
import org.eninehq.tessera.formal.Formal;
import org.eninehq.tessera.riscv.HwDom;
import org.eninehq.tessera.riscv.Rv32i;
import org.eninehq.tessera.riscv.SwDom;
import org.eninehq.tessera.sim.Sim;
import org.junit.jupiter.api.Test;

class JavaHwTest {

    /** The plain-Java ALU next to the builder ALU, with an assertion that they agree. */
    static class AluMiter extends Module {
        AluMiter() {
            var op = input("op", HwEnum.of(Alu.Op.class));
            UInt a = input("a", UInt.w(32)), b = input("b", UInt.w(32));
            Alu builder = instance("builder", () -> new Alu(32));
            builder.op.set(op);
            builder.a.set(a);
            builder.b.set(b);
            UInt plain = (UInt) JavaHw.of(PlainJava.class, "alu").apply(op, a, b);
            output("plain", UInt.w(32)).set(plain);
            output("built", UInt.w(32)).set(builder.y);
            Hw.assertThat(plain.eq(builder.y), "same_as_builder");
        }
    }

    @Test
    void aluIsFormallyEquivalentToTheBuilderVersion() {
        var e = Tessera.elaborate(AluMiter::new);
        var r = Formal.bmc(e, 1);
        assertEquals(Formal.Status.PASS, r.status(), r.toString());
    }

    @Test
    void compiledAluMatchesRunningTheSameJava() {
        var e = Tessera.elaborate(AluMiter::new);
        var rnd = new Random(1);
        try (var sim = Sim.create(e)) {
            for (int i = 0; i < 2000; i++) {
                var op = Alu.Op.values()[rnd.nextInt(Alu.Op.values().length)];
                int a = rnd.nextInt(), b = rnd.nextInt(i % 3 == 0 ? 64 : Integer.MAX_VALUE);
                sim.poke("op", op.ordinal());
                sim.poke("a", Integer.toUnsignedLong(a));
                sim.poke("b", Integer.toUnsignedLong(b));
                assertEquals(PlainJava.alu(op, a, b), (int) sim.peek("plain"), op + " " + a + " " + b);
            }
        }
    }

    static class Funcs extends Module {
        Funcs() {
            UInt x = input("x", UInt.w(32)), y = input("y", UInt.w(32));
            output("pop", UInt.w(32)).set((UInt) JavaHw.of(PlainJava.class, "popcount").apply(x));
            output("sat", UInt.w(32)).set((UInt) JavaHw.of(PlainJava.class, "saturate").apply(x, 10));
            output("csum", UInt.w(32)).set((UInt) JavaHw.of(PlainJava.class, "clampedSum").apply(x, y));
            output("top", UInt.w(32)).set((UInt) JavaHw.of(PlainJava.class, "firstSetFromTop").apply(x));
            output("mulh", UInt.w(64)).set((UInt) JavaHw.of(PlainJava.class, "mulHigh").apply(x, y));
        }
    }

    @Test
    void loopsReturnsCallsAndConstantsFold() {
        var e = Tessera.elaborate(Funcs::new);
        var rnd = new Random(2);
        try (var sim = Sim.create(e)) {
            for (int i = 0; i < 2000; i++) {
                int x = i < 40 ? (1 << (i % 32)) - (i / 32) : rnd.nextInt() >> rnd.nextInt(32);
                int y = rnd.nextInt() >> rnd.nextInt(32);
                sim.poke("x", Integer.toUnsignedLong(x));
                sim.poke("y", Integer.toUnsignedLong(y));
                assertEquals(PlainJava.popcount(x), (int) sim.peek("pop"));
                assertEquals(PlainJava.saturate(x, 10), (int) sim.peek("sat"));
                assertEquals(PlainJava.clampedSum(x, y), (int) sim.peek("csum"));
                assertEquals(PlainJava.firstSetFromTop(x), (int) sim.peek("top"), "x=" + x);
                assertEquals(PlainJava.mulHigh(x, y), sim.peek("mulh"));
            }
        }
        String sv = SvEmitter.emit(e);
        assertTrue(sv.contains(" max") || sv.contains("result") || sv.contains(" n"), "locals become named wires");
    }

    /** RV32I execute in plain Java next to the Dom-based Rv32i, with assertions on every output. */
    static class Rv32iMiter extends Module {
        Rv32iMiter() {
            UInt inst = input("inst", UInt.w(32)), pc = input("pc", UInt.w(32));
            UInt a = input("a", UInt.w(32)), b = input("b", UInt.w(32));
            var d = HwDom.INSTANCE;
            var dec = Rv32i.decode(d, inst);
            var ex = Rv32i.execute(d, dec, pc, a, b);
            @SuppressWarnings("unchecked")
            var p = (Map<String, Data>) JavaHw.of(Rv32iPlain.class, "exec").apply(inst, pc, a, b);
            check("result", (UInt) p.get("result"), ex.result());
            check("target", (UInt) p.get("target"), ex.target());
            check("memAddr", (UInt) p.get("memAddr"), ex.memAddr());
            checkB("redirect", (Bool) p.get("redirect"), ex.redirect());
            checkB("trap", (Bool) p.get("trap"), ex.trap());
            checkB("writesRd", (Bool) p.get("writesRd"), ex.writesRd());
            checkB("load", (Bool) p.get("load"), dec.load());
            checkB("store", (Bool) p.get("store"), dec.store());
        }

        void check(String n, UInt x, UInt y) {
            output(n, UInt.w(32)).set(x);
            Hw.assertThat(x.eq(y), n + "_equal");
        }

        void checkB(String n, Bool x, Bool y) {
            output(n, Bool.TYPE).set(x);
            Hw.assertThat(x.eq(y), n + "_equal");
        }
    }

    @Test
    void rv32iPlainJavaIsFormallyEquivalentToTheDomModel() {
        var e = Tessera.elaborate(Rv32iMiter::new);
        var r = Formal.bmc(e, 1);
        assertEquals(Formal.Status.PASS, r.status(), r.toString());
    }

    @Test
    void rv32iPlainJavaAgreesInSoftwareToo() {
        var rnd = new Random(3);
        var sw = SwDom.INSTANCE;
        int[] opcodes = {0x37, 0x17, 0x6F, 0x67, 0x63, 0x03, 0x23, 0x13, 0x33, 0x0F, 0x73, 0x7F};
        for (int i = 0; i < 200_000; i++) {
            int inst = (rnd.nextInt() & ~0x7F) | opcodes[rnd.nextInt(opcodes.length)];
            int pc = rnd.nextInt() & ~3, a = rnd.nextInt(), b = rnd.nextInt();
            var dec = Rv32i.decode(sw, inst);
            var ex = Rv32i.execute(sw, dec, pc, a, b);
            var p = Rv32iPlain.exec(inst, pc, a, b);
            assertEquals(ex.result(), p.result());
            assertEquals(ex.trap(), p.trap());
            assertEquals(ex.writesRd(), p.writesRd());
            assertEquals(ex.redirect(), p.redirect());
        }
    }

    @Test
    void unsupportedJavaIsReportedAtTheJavaLine() {
        var div = assertThrows(TesseraException.class, () -> Tessera.elaborate(() -> new Module() {
            {
                UInt x = input("x", UInt.w(32));
                output("y", UInt.w(32)).set((UInt) JavaHw.of(PlainJava.class, "divide").apply(x, x));
            }
        }));
        assertTrue(div.getMessage().contains("division of hardware values") && div.getMessage().contains("PlainJava.java:"), div.getMessage());
        var spin = assertThrows(TesseraException.class, () -> Tessera.elaborate(() -> new Module() {
            {
                UInt x = input("x", UInt.w(32));
                output("y", UInt.w(32)).set((UInt) JavaHw.of(PlainJava.class, "spin").apply(x));
            }
        }));
        assertTrue(spin.getMessage().contains("loop condition depends on hardware"), spin.getMessage());
    }
}
