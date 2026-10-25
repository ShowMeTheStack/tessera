package org.eninehq.tessera.studio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.eninehq.tessera.studio.CodeGenTest.D;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Java back to a drawing: generate, read the Java back, generate again; the Java and the parts' places must not change. */
class RoundTripTest {
    static Elaborator elab;
    static Modules mods;

    @BeforeAll
    static void setUp() throws Exception {
        elab = new Elaborator(List.of(), 1000);
        mods = new Modules(List.of(), 1000);
    }

    static String body(String java) {
        int m = java.lastIndexOf(CodeGen.MARK);
        return (m < 0 ? java : java.substring(0, m)).strip();
    }

    /** Places every part somewhere of its own, so that a kept place is a real check. */
    static D placed(D d) {
        int k = 0;
        for (var o : d.parts) { @SuppressWarnings("unchecked") var p = (Map<String, Object>) o; p.put("x", 3 + 7 * k); p.put("y", 2 + 5 * (k % 4)); k++; }
        return d;
    }

    static Map<String, Object> roundTrip(D d) {
        var g1 = CodeGen.generate(d.doc);
        assertTrue(g1.ok(), g1.problems() + "\n" + g1.java());
        var read = JavaReader.readSource(body(g1.java()), d.doc, elab, mods);
        var g2 = CodeGen.generate(read.doc());
        assertTrue(g2.ok(), g2.problems() + "\n" + g2.java());
        assertEquals(body(g1.java()), body(g2.java()), "the Java changed on the way round; read: " + read.doc());
        assertEquals(List.of(), read.report(), "nothing of the studio's own Java should become a code part");
        // every part kept its id and place
        var before = new LinkedHashMap<String, String>();
        for (var o : d.parts) { @SuppressWarnings("unchecked") var p = (Map<String, Object>) o; before.put(String.valueOf(p.get("id")), p.get("x") + "," + p.get("y")); }
        var after = new LinkedHashMap<String, String>();
        for (var p : CodeGen.list(read.doc().get("parts"))) after.put(String.valueOf(p.get("id")), p.get("x") + "," + p.get("y"));
        assertEquals(before, after, "parts and places");
        return read.doc();
    }

    @Test
    void gatesRegistersAndLibraryParts() {
        roundTrip(placed(CodeGenTest.blinky()));
        roundTrip(placed(CodeGenTest.counter()));
        roundTrip(placed(new D("Lib")
                .part("i", "in", "name", "inc", "width", 1)
                .part("c", "counter", "max", 4)
                .part("e", "edge", "kind", "rising")
                .part("x", "in", "name", "x", "width", 6)
                .part("pc", "bitop", "op", "popCount")
                .part("pe", "prienc")
                .part("dl", "delay", "cycles", 2)
                .part("o1", "out", "name", "value", "width", 3).part("o2", "out", "name", "wrap", "width", 1)
                .part("o3", "out", "name", "rise", "width", 1).part("o4", "out", "name", "ones", "width", 3)
                .part("o5", "out", "name", "low", "width", 3).part("o6", "out", "name", "any", "width", 1)
                .part("o7", "out", "name", "late", "width", 6)
                .net("n1", "i.y", "c.inc", "e.a").net("n2", "c.value", "o1.a").net("n3", "c.wrap", "o2.a").net("n4", "e.y", "o3.a")
                .net("n5", "x.y", "pc.a", "pe.a", "dl.a").net("n6", "pc.y", "o4.a").net("n7", "pe.index", "o5.a").net("n8", "pe.valid", "o6.a").net("n9", "dl.y", "o7.a")));
    }

    @Test
    void theBoardTest() {
        // the drawing that checked the ZedBoard's pins: slices, a concatenation, muxes, a reduction, one-hot
        roundTrip(placed(new D("BoardTest")
                .part("sw", "in", "name", "sw", "width", 8).part("btn", "in", "name", "btn", "width", 5)
                .part("ev", "every", "seconds", 0.25).part("cnt", "counter", "max", 3).part("oh", "onehot", "n", 4)
                .part("any", "reduce", "op", "or").part("m1", "mux", "inputs", 2)
                .part("bc", "slice", "hi", 0, "lo", 0).part("ff", "const", "value", "0xff", "width", 8).part("m2", "mux", "inputs", 2)
                .part("bh", "slice", "hi", 4, "lo", 1).part("cat", "concat", "inputs", 2).part("led", "out", "name", "led", "width", 8)
                .net("a", "sw.y", "any.a", "m1.in1").net("b", "any.y", "m1.sel").net("c", "ev.y", "cnt.inc").net("d", "cnt.value", "oh.idx")
                .net("e", "oh.y", "cat.in1").net("e2", "cat.y", "m1.in0").net("f", "btn.y", "bc.a", "bh.a").net("g", "bc.y", "m2.sel")
                .net("h", "m1.y", "m2.in0").net("i", "ff.y", "m2.in1").net("j", "bh.y", "cat.in0").net("n", "m2.y", "led.a")));
    }

    @Test
    void memoriesTablesAndStateMachines() {
        roundTrip(placed(new D("Mems")
                .part("a", "in", "name", "addr", "width", 2)
                .part("rom", "mem", "name", "rom", "width", 8, "depth", 4, "write", false, "init", "0x11 0x22 0x33 0x44")
                .part("o1", "out", "name", "romOut", "width", 8)
                .part("wd", "in", "name", "wdata", "width", 8)
                .part("we", "in", "name", "we", "width", 1)
                .part("ram", "mem", "name", "ram", "width", 8, "depth", 4)
                .part("o2", "out", "name", "ramOut", "width", 8)
                .net("n1", "a.y", "rom.addr", "ram.addr").net("n2", "rom.rdata", "o1.a")
                .net("n3", "wd.y", "ram.wdata").net("n4", "we.y", "ram.we").net("n5", "ram.rdata", "o2.a")));
        roundTrip(placed(new D("Chase")
                .part("m", "mem", "name", "next", "width", 2, "depth", 4, "read", "sync", "write", false, "init", "2 3 1 0")
                .part("o", "out", "name", "p", "width", 2)
                .net("n1", "m.rdata", "m.addr", "o.a")));
        roundTrip(placed(new D("Fa")
                .part("a", "in", "name", "a", "width", 1).part("b", "in", "name", "b", "width", 1).part("c", "in", "name", "cin", "width", 1)
                .part("t", "table", "inputs", "a b cin", "outputs", "sum cout",
                        "rows", "0 0 1 | 1 0\n0 1 0 | 1 0\n1 0 0 | 1 0\n1 1 1 | 1 1\n0 1 1 | 0 1\n1 0 1 | 0 1\n1 1 0 | 0 1")
                .part("o1", "out", "name", "s", "width", 1).part("o2", "out", "name", "co", "width", 1)
                .net("n1", "a.y", "t.a").net("n2", "b.y", "t.b").net("n3", "c.y", "t.cin").net("n4", "t.sum", "o1.a").net("n5", "t.cout", "o2.a")));
        roundTrip(placed(new D("Hand")
                .part("g", "in", "name", "go", "width", 1)
                .part("s", "in", "name", "stop", "width", 1)
                .part("f", "fsm", "name", "fsm", "states", "IDLE RUN DONE", "inputs", "go stop",
                        "transitions", "IDLE -> RUN : go\nRUN -> DONE : stop & !go\nRUN -> IDLE : stop\nDONE -> IDLE : 1",
                        "outputs", "busy : RUN DONE\nidle : IDLE")
                .part("o1", "out", "name", "busy", "width", 1)
                .part("o2", "out", "name", "st", "width", 2)
                .net("n1", "g.y", "f.go").net("n2", "s.y", "f.stop").net("n3", "f.busy", "o1.a").net("n4", "f.state", "o2.a")));
    }

    @Test
    void blocksAndJavaModules() {
        var inc = placed(new D("Inc")
                .part("i", "in", "name", "a", "width", 8)
                .part("k", "const", "value", "1", "width", 8)
                .part("s", "add", "mode", "wrap")
                .part("o", "out", "name", "y", "width", 8)
                .net("n1", "i.y", "s.a").net("n2", "k.y", "s.b").net("n3", "s.y", "o.a"));
        var top = placed(new D("Twice")
                .part("x", "in", "name", "x", "width", 8)
                .part("u1", "block", "block", "Inc", "name", "u1")
                .part("u2", "block", "block", "Inc", "name", "u2")
                .part("z", "out", "name", "z", "width", 8)
                .net("m1", "x.y", "u1.a").net("m2", "u1.y", "u2.a").net("m3", "u2.y", "z.a"));
        top.doc.put("blocks", Map.of("Inc", inc.doc));
        var read = roundTrip(top);
        var b = CodeGen.map(CodeGen.map(read.get("blocks")).get("Inc"));
        assertEquals(4, CodeGen.list(b.get("parts")).size(), "the block's own parts: " + b);

        var fifo = mods.probe("new Fifo<>(UInt.w(8), 4)", List.of("org.eninehq.tessera.stdlib.Fifo"));
        roundTrip(placed(new D("Q")
                .part("v", "in", "name", "push", "width", 1).part("b", "in", "name", "data", "width", 8).part("r", "in", "name", "pop", "width", 1)
                .part("f", "module", "name", "q", "expr", "new Fifo<>(UInt.w(8), 4)", "imports", List.of("org.eninehq.tessera.stdlib.Fifo"), "pins", fifo.get("pins"))
                .part("o1", "out", "name", "head", "width", 8).part("o2", "out", "name", "have", "width", 1).part("o3", "out", "name", "n", "width", 3)
                .part("o4", "out", "name", "room", "width", 1)
                .net("n1", "v.y", "f.in_valid").net("n2", "b.y", "f.in_bits").net("n3", "r.y", "f.out_ready")
                .net("n4", "f.out_bits", "o1.a").net("n5", "f.out_valid", "o2.a").net("n6", "f.count", "o3.a").net("n7", "f.in_ready", "o4.a")));
    }

    @Test
    void clockDomainsAndSynchronisers() {
        roundTrip(placed(MultiClockTest.twoCounters(true, true)));
    }

    @Test
    void anEditInTheDialectChangesTheDrawing() {
        var d = placed(CodeGenTest.counter());
        String java = body(CodeGen.generate(d.doc).java());
        // count by 3 instead of 1, and add an output: both are statements of the dialect
        String edited = java.replace("UInt.lit(1, 8)", "UInt.lit(3, 8)")
                .replace("        output(\"value\"", "        output(\"odd\", Bool.TYPE).set(count.bit(0));\n        output(\"value\"");
        assertTrue(!edited.equals(java), java);
        var read = JavaReader.readSource(edited, d.doc, elab, mods);
        assertEquals(List.of(), read.report());
        var g = CodeGen.generate(read.doc());
        assertTrue(g.ok(), g.problems() + "\n" + g.java());
        assertTrue(g.java().contains("UInt.lit(3, 8)"), g.java());
        var parts = CodeGen.list(read.doc().get("parts"));
        assertTrue(parts.stream().anyMatch(p -> "out".equals(p.get("type")) && "odd".equals(CodeGen.map(p.get("params")).get("name"))), parts.toString());
        // the register kept its place; the new output has none yet (the canvas places it)
        var reg = parts.stream().filter(p -> "reg".equals(p.get("type"))).findFirst().orElseThrow();
        assertEquals("r", reg.get("id"));
    }

    @Test
    void javaOutsideTheDialectBecomesACodePart() throws Exception {
        String java = """
                package studio;

                import org.eninehq.tessera.core.Bool;
                import org.eninehq.tessera.core.Hw;
                import org.eninehq.tessera.core.Module;
                import org.eninehq.tessera.core.UInt;

                /** Parity of a byte, by hand. */
                public class Parity extends Module {
                    public Parity() {
                        UInt x = input("x", UInt.w(8));
                        Bool p = x.bit(0);
                        for (int i = 1; i < 8; i++) p = p.xor(x.bit(i));
                        Bool odd = p.named("odd");
                        output("parity", Bool.TYPE).set(odd);
                        output("x2", UInt.w(8)).set(x);
                    }
                }
                """;
        var read = JavaReader.readSource(java, null, elab, mods);
        assertEquals(1, read.report().size(), read.report().toString());
        assertTrue(read.report().getFirst().contains("became the Java code part Code1"), read.report().toString());
        var g = CodeGen.generate(read.doc());
        assertTrue(g.ok(), g.problems() + "\n" + g.java());
        var out = elab.elaborate(g, "studio");
        assertTrue(out.problems().isEmpty(), out.problems() + "\n" + g.java());
        try (var sim = org.eninehq.tessera.sim.Sim.create(out.design())) {
            for (int v : new int[]{0, 1, 3, 0x80, 0xff, 0x7f}) {
                sim.poke("x", v);
                sim.step();
                assertEquals(Integer.bitCount(v) & 1, sim.peek("parity"), "parity of " + v + "\n" + g.java());
                assertEquals(v, sim.peek("x2"));
            }
        }
    }

    /** A hand-written design: open it, generate, and check the drawing behaves like the original. */
    static JavaReader.Read openHandWritten(String java, String top) throws Exception {
        var read = JavaReader.readSource(java, null, elab, mods);
        var g = CodeGen.generate(read.doc());
        assertTrue(g.ok(), g.problems() + "\n" + g.java());
        var out = elab.elaborate(g, "studio");
        assertTrue(out.problems().isEmpty(), out.problems() + "\n" + g.java());
        return read;
    }

    static final String UART = """
            package studio;

            import static org.eninehq.tessera.core.Hw.when;

            import org.eninehq.tessera.core.Bool;
            import org.eninehq.tessera.core.Module;
            import org.eninehq.tessera.core.UInt;

            /** The examples' UartTx with clocksPerBit fixed at 4. */
            public class UartTx extends Module {
                public final UInt data = input("data", UInt.w(8));
                public final Bool valid = input("valid", Bool.TYPE);
                public final Bool ready = output("ready", Bool.TYPE);
                public final Bool tx = output("tx", Bool.TYPE);

                public UartTx() {
                    int clocksPerBit = 4;
                    int timerBits = Math.max(1, 32 - Integer.numberOfLeadingZeros(clocksPerBit - 1));
                    UInt shift = reg("shift", UInt.w(10), 0x3FF);
                    UInt bitsLeft = reg("bitsLeft", UInt.w(4), 0);
                    UInt timer = reg("timer", UInt.w(timerBits), 0);

                    Bool idle = bitsLeft.eq(0).named("idle");
                    ready.set(idle);
                    tx.set(shift.bit(0));

                    when(idle, () -> when(valid, () -> {
                        shift.set(UInt.lit(1, 1).cat(data, UInt.lit(0, 1)));
                        bitsLeft.set(10);
                        timer.set(clocksPerBit - 1);
                    })).otherwise(() -> when(timer.eq(0), () -> {
                        shift.set(UInt.lit(1, 1).cat(shift.bits(9, 1)));
                        bitsLeft.set(bitsLeft.subWrap(1));
                        timer.set(clocksPerBit - 1);
                    }).otherwise(() -> timer.set(timer.subWrap(1))));
                }
            }
            """;

    @Test
    void handWrittenDesignsOpen() throws Exception {
        // the examples' Counter, with the width fixed: all of it is the dialect
        var counter = openHandWritten("""
                package studio;

                import static org.eninehq.tessera.core.Hw.when;

                import org.eninehq.tessera.core.Bool;
                import org.eninehq.tessera.core.Module;
                import org.eninehq.tessera.core.UInt;

                public class Counter extends Module {
                    public final Bool en = input("en", Bool.TYPE);
                    public final UInt count;

                    public Counter() {
                        count = output("count", UInt.w(4));
                        UInt value = reg("value", UInt.w(4), 0);
                        when(en, () -> value.set(value.addWrap(1)));
                        count.set(value);
                    }
                }
                """, "Counter");
        assertEquals(List.of(), counter.report());
        var g = CodeGen.generate(counter.doc());
        try (var sim = org.eninehq.tessera.sim.Sim.create(elab.elaborate(g, "studio").design())) {
            sim.poke("en", 1);
            for (int i = 0; i < 18; i++) { assertEquals(i % 16, sim.peek("count"), g.java()); sim.step(); }
        }

        // the UART: its nested whens are Java, so they and the registers they set become a code part;
        // the drawing must send the same bits as the original
        var uart = openHandWritten(UART, "UartTx");
        assertEquals(1, uart.report().size(), uart.report().toString());
        var g2 = CodeGen.generate(uart.doc());
        var orig = elab.elaborate(new CodeGen.Result("UartTx", UART, Map.of(), List.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of()), "studio");
        assertTrue(orig.problems().isEmpty(), orig.problems().toString());
        var a = new StringBuilder();
        var b = new StringBuilder();
        for (var pair : List.of(new Object[]{orig.design(), a}, new Object[]{elab.elaborate(g2, "studio").design(), b})) {
            @SuppressWarnings("unchecked") var d = (org.eninehq.tessera.core.Elaborated<org.eninehq.tessera.core.Module>) pair[0];
            try (var sim = org.eninehq.tessera.sim.Sim.create(d)) {
                for (int c = 0; c < 120; c++) {
                    sim.poke("data", c < 50 ? 0xA5 : 0x3C);
                    sim.poke("valid", c % 50 < 3 ? 1 : 0);
                    ((StringBuilder) pair[1]).append(sim.peek("tx")).append(sim.peek("ready"));
                    sim.step();
                }
            }
        }
        assertEquals(a.toString(), b.toString(), g2.java());
        assertTrue(a.toString().contains("00"), "the UART sent something: " + a);
    }
}
