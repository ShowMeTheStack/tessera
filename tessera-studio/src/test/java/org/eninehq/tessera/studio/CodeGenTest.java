package org.eninehq.tessera.studio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CodeGenTest {
    /** A drawing built in code: parts with params, nets as lists of "part.pin". */
    static final class D {
        final Map<String, Object> doc = new LinkedHashMap<>();
        final List<Object> parts = new ArrayList<>(), nets = new ArrayList<>();

        D(String name) {
            doc.put("tessera_studio", 1);
            doc.put("name", name);
            doc.put("package", "studio");
            doc.put("parts", parts);
            doc.put("nets", nets);
        }

        D part(String id, String type, Object... kv) {
            var params = new LinkedHashMap<String, Object>();
            for (int i = 0; i < kv.length; i += 2) params.put((String) kv[i], kv[i + 1]);
            var p = new LinkedHashMap<String, Object>();
            p.put("id", id);
            p.put("type", type);
            p.put("x", 0);
            p.put("y", 0);
            p.put("params", params);
            parts.add(p);
            return this;
        }

        D net(String id, String... pins) {
            var l = new ArrayList<Object>();
            for (var s : pins) { var t = s.split("\\."); l.add(List.of(t[0], t[1])); }
            var n = new LinkedHashMap<String, Object>();
            n.put("id", id);
            n.put("pins", l);
            nets.add(n);
            return this;
        }
    }

    static D blinky() {
        return new D("Blinky")
                .part("p1", "in", "name", "btn0", "width", 1)
                .part("p2", "blink", "seconds", 0.5)
                .part("p3", "xor", "inputs", 2)
                .part("p4", "out", "name", "led", "width", 1)
                .net("w1", "p1.y", "p3.in1")
                .net("w2", "p2.y", "p3.in0")
                .net("w3", "p3.y", "p4.a");
    }

    static D counter() {
        return new D("Counter")
                .part("r", "reg", "name", "count", "width", 8, "init", "0", "enable", true)
                .part("k", "const", "value", "1", "width", 8)
                .part("a", "add", "mode", "wrap")
                .part("en", "in", "name", "en", "width", 1)
                .part("o", "out", "name", "value", "width", 8)
                .net("q", "r.q", "a.a", "o.a")
                .net("one", "k.y", "a.b")
                .net("next", "a.y", "r.d")
                .net("e", "en.y", "r.en");
    }

    @Test
    void blinkyGeneratesStrictTesseraCode() {
        var r = CodeGen.generate(blinky().doc);
        assertTrue(r.ok(), r.problems().toString());
        assertTrue(r.java().contains("public Blinky(Clock clk)"), r.java());
        assertTrue(r.java().contains("Bool btn0 = input(\"btn0\", Bool.TYPE);"), r.java());
        assertTrue(r.java().contains("Timing.blink(clk, Duration.ofMillis(500))"), r.java());
        assertTrue(r.java().contains("output(\"led\", Bool.TYPE).set("), r.java());
        assertFalse(r.java().contains("Fit."), "no forgiving casts");
        assertEquals(1, r.netWidths().get("w3"));
    }

    @Test
    void drawingRoundTripsThroughTheJava() {
        var d = counter().doc;
        var r = CodeGen.generate(d);
        assertEquals(Json.write(d), Json.write(CodeGen.embedded(r.java())));
    }

    @Test
    void counterElaboratesAndRuns() throws Exception {
        var r = CodeGen.generate(counter().doc);
        assertTrue(r.ok(), r.problems().toString());
        assertEquals(8, r.netWidths().get("next"));
        var out = new Elaborator(List.of(), 1000).elaborate(r, "studio");
        assertTrue(out.ok(), out.problems().toString() + "\n" + r.java());
        try (var sim = org.eninehq.tessera.sim.Sim.create(out.design())) {
            sim.reset(1);
            sim.poke("en", 1);
            sim.step(5);
            assertEquals(5, sim.peek("value"));
            sim.poke("en", 0);
            sim.step(3);
            assertEquals(5, sim.peek("count"));
        }
    }

    @Test
    void widthMismatchIsAProblemWithFixes() {
        var d = new D("Bad")
                .part("a", "in", "name", "a", "width", 4)
                .part("b", "in", "name", "b", "width", 8)
                .part("g", "and", "inputs", 2)
                .part("o", "out", "name", "y", "width", 8)
                .net("n1", "a.y", "g.in0").net("n2", "b.y", "g.in1").net("n3", "g.y", "o.a");
        var r = CodeGen.generate(d.doc);
        assertFalse(r.ok());
        var p = r.problems().stream().filter(x -> x.code().equals("width-mismatch")).findFirst().orElseThrow();
        assertTrue(p.message().contains("in0 is 4"), p.message());
        assertEquals("insert", p.fixes().getFirst().get("op"));
        assertEquals("in0", p.fixes().getFirst().get("pin"));
    }

    @Test
    void narrowingIntoAnOutputIsRefused() {
        var d = new D("Narrow")
                .part("a", "in", "name", "a", "width", 8)
                .part("b", "in", "name", "b", "width", 8)
                .part("s", "add", "mode", "widen")
                .part("o", "out", "name", "y", "width", 8)
                .net("n1", "a.y", "s.a").net("n2", "b.y", "s.b").net("n3", "s.y", "o.a");
        var r = CodeGen.generate(d.doc);
        var p = r.problems().stream().filter(x -> x.code().equals("narrowing")).findFirst().orElseThrow();
        assertTrue(p.message().contains("receives 9 bits"), p.message());
        assertEquals(2, p.fixes().size());
    }

    @Test
    void loopsWithoutARegisterAndDriverProblemsAreFound() {
        var loop = new D("Loop")
                .part("g", "not")
                .part("o", "out", "name", "y", "width", 1)
                .net("n", "g.y", "g.a", "o.a");
        assertTrue(CodeGen.generate(loop.doc).problems().stream().anyMatch(x -> x.code().equals("comb-loop")));

        var two = new D("Two")
                .part("a", "in", "name", "a", "width", 1)
                .part("b", "in", "name", "b", "width", 1)
                .part("o", "out", "name", "y", "width", 1)
                .net("n", "a.y", "b.y", "o.a");
        assertTrue(CodeGen.generate(two.doc).problems().stream().anyMatch(x -> x.code().equals("two-drivers")));

        var open = new D("Open").part("g", "not").part("o", "out", "name", "y", "width", 1).net("n", "g.y", "o.a");
        var p = CodeGen.generate(open.doc).problems().stream().filter(x -> x.code().equals("open-input")).findFirst().orElseThrow();
        assertEquals("tie", p.fixes().getFirst().get("op"));
    }

    @Test
    void sessionRunsSwapsAndTravelsInTime() throws Exception {
        var el = new Elaborator(List.of(), 1000);
        try (var s = new Session(el)) {
            var r = s.load(counter().doc);
            assertEquals(true, r.get("elaborated"), String.valueOf(r));
            s.command(Map.of("cmd", "pause"));
            s.command(Map.of("cmd", "poke", "path", "en", "value", "1"));
            long c0 = s.cycle();
            s.command(Map.of("cmd", "step", "n", 10));
            assertEquals(c0 + 10, s.cycle());
            // edit: count by two; the register keeps its value across the reload
            var d = counter();
            CodeGen.map(((Map<?, ?>) d.parts.get(1)).get("params")).put("value", "2");
            var before = s.waves(Map.of("paths", List.of("count"), "from", 0, "to", s.cycle(), "columns", 100));
            assertNotNull(before);
            r = s.load(d.doc);
            assertEquals(true, r.get("elaborated"), String.valueOf(r));
            assertTrue(String.valueOf(r.get("message")).contains("kept 1 registers"), String.valueOf(r.get("message")));
            long c1 = s.cycle();
            s.command(Map.of("cmd", "step", "n", 1));
            // look at the past and come back
            s.command(Map.of("cmd", "goto", "cycle", c0 + 3));
            assertEquals(c0 + 3, s.cycle());
            s.command(Map.of("cmd", "run"));
            s.command(Map.of("cmd", "pause"));
            assertTrue(s.cycle() >= c1 + 1);
            var w = s.waves(Map.of("paths", List.of("count"), "from", 0, "to", s.cycle(), "columns", 100));
            var track = (Map<?, ?>) ((Map<?, ?>) w.get("waves")).get("count");
            assertEquals(true, track.get("exact"));
        }
    }

    @Test
    void theBackgroundLoopKeepsRunningAcrossPokesAndReloads() throws Exception {
        try (var s = new Session(new Elaborator(List.of(), 1000))) {
            s.load(counter().doc);
            s.command(Map.of("cmd", "rate", "rate", 200));
            Thread.sleep(400);
            long c0 = s.cycle();
            assertTrue(c0 > 20, "runs on its own: " + c0);
            s.command(Map.of("cmd", "poke", "path", "en", "value", "1"));
            Thread.sleep(400);
            long c1 = s.cycle();
            assertTrue(c1 > c0 + 20, "still runs after a poke: " + c0 + " -> " + c1);
            s.load(counter().doc);                   // the same drawing again: no reload
            Thread.sleep(300);
            assertTrue(s.cycle() > c1 + 20, "still runs after an unchanged load");
        }
    }

    @Test
    void blocksBecomeNestedModulesAndInstancesChain() throws Exception {
        var inc = new D("Inc")
                .part("i", "in", "name", "a", "width", 8)
                .part("k", "const", "value", "1", "width", 8)
                .part("s", "add", "mode", "wrap")
                .part("o", "out", "name", "y", "width", 8)
                .net("n1", "i.y", "s.a").net("n2", "k.y", "s.b").net("n3", "s.y", "o.a");
        var top = new D("Twice")
                .part("x", "in", "name", "x", "width", 8)
                .part("u1", "block", "block", "Inc", "name", "u1")
                .part("u2", "block", "block", "Inc", "name", "u2")
                .part("z", "out", "name", "z", "width", 8)
                .net("m1", "x.y", "u1.a").net("m2", "u1.y", "u2.a").net("m3", "u2.y", "z.a");
        top.doc.put("blocks", Map.of("Inc", inc.doc));
        var r = CodeGen.generate(top.doc);
        assertTrue(r.ok(), r.problems() + "\n" + r.java());
        assertTrue(r.java().contains("public static class Inc extends Module"), r.java());
        assertTrue(r.java().contains("Inc u1 = instance(\"u1\", () -> new Inc());"), r.java());
        assertTrue(r.java().contains("u2.a.set(u1.y);"), r.java());
        assertEquals("u1.y", r.netPaths().get("m2"));
        var out = new Elaborator(List.of(), 1000).elaborate(r, "studio");
        assertTrue(out.ok(), out.problems() + "\n" + r.java());
        try (var sim = org.eninehq.tessera.sim.Sim.create(out.design())) {
            sim.poke("x", 40);
            sim.step();
            assertEquals(42, sim.peek("z"));
            assertEquals(41, sim.peek("u1.y"));
        }
    }

    @Test
    void memoryGeneratesAndSimulates() throws Exception {
        // a ROM looked up by addr, and a RAM written then read back
        var d = new D("Mems")
                .part("a", "in", "name", "addr", "width", 2)
                .part("rom", "mem", "name", "rom", "width", 8, "depth", 4, "write", false, "init", "0x11 0x22 0x33 0x44")
                .part("o1", "out", "name", "romOut", "width", 8)
                .part("wd", "in", "name", "wdata", "width", 8)
                .part("we", "in", "name", "we", "width", 1)
                .part("ram", "mem", "name", "ram", "width", 8, "depth", 4)
                .part("o2", "out", "name", "ramOut", "width", 8)
                .net("n1", "a.y", "rom.addr", "ram.addr").net("n2", "rom.rdata", "o1.a")
                .net("n3", "wd.y", "ram.wdata").net("n4", "we.y", "ram.we").net("n5", "ram.rdata", "o2.a");
        var r = CodeGen.generate(d.doc);
        assertTrue(r.ok(), r.problems() + "\n" + r.java());
        assertTrue(r.java().contains("Mem<UInt> rom = mem(\"rom\", UInt.w(8), 4).init(17, 34, 51, 68);"), r.java());
        var out = new Elaborator(List.of(), 1000).elaborate(r, "studio");
        assertTrue(out.ok(), out.problems() + "\n" + r.java());
        try (var sim = org.eninehq.tessera.sim.Sim.create(out.design())) {
            sim.poke("addr", 2);
            sim.poke("wdata", 0x5a);
            sim.poke("we", 1);
            sim.step();
            assertEquals(0x33, sim.peek("romOut"));
            sim.poke("we", 0);
            sim.step();
            assertEquals(0x5a, sim.peek("ramOut"));
            sim.poke("addr", 3);
            sim.step();
            assertEquals(0x44, sim.peek("romOut"));
        }
    }

    @Test
    void truthTableIsTheStandardLibrarysWithDontCares() throws Exception {
        // a full adder; the carry row uses don't-cares
        var d = new D("Fa")
                .part("a", "in", "name", "a", "width", 1).part("b", "in", "name", "b", "width", 1).part("c", "in", "name", "cin", "width", 1)
                .part("t", "table", "inputs", "a b cin", "outputs", "sum cout",
                        "rows", "0 0 1 | 1 0\n0 1 0 | 1 0\n1 0 0 | 1 0\n1 1 1 | 1 1\n0 1 1 | 0 1\n1 0 1 | 0 1\n1 1 0 | 0 1")
                .part("o1", "out", "name", "s", "width", 1).part("o2", "out", "name", "co", "width", 1)
                .net("n1", "a.y", "t.a").net("n2", "b.y", "t.b").net("n3", "c.y", "t.cin").net("n4", "t.sum", "o1.a").net("n5", "t.cout", "o2.a");
        var r = CodeGen.generate(d.doc);
        assertTrue(r.ok(), r.problems() + "\n" + r.java());
        assertTrue(r.java().contains("TruthTable.parse(\"\"\""), r.java());
        var out = new Elaborator(List.of(), 1000).elaborate(r, "studio");
        assertTrue(out.ok(), out.problems() + "\n" + r.java());
        try (var sim = org.eninehq.tessera.sim.Sim.create(out.design())) {
            for (int x = 0; x < 8; x++) {
                sim.poke("a", x >> 2 & 1); sim.poke("b", x >> 1 & 1); sim.poke("cin", x & 1);
                sim.step();
                int n = (x >> 2 & 1) + (x >> 1 & 1) + (x & 1);
                assertEquals(n & 1, sim.peek("s"), "sum for " + x);
                assertEquals(n >> 1, sim.peek("co"), "carry for " + x);
            }
        }
        // overlapping rows that disagree are a sentence, not a stack trace
        var bad = new D("Bad").part("t", "table", "inputs", "a", "outputs", "y", "rows", "- | 1\n0 | 0");
        var rb = CodeGen.generate(bad.doc);
        assertTrue(rb.problems().stream().anyMatch(x -> x.message().contains("rows 1 and 2 match the same inputs")), rb.problems().toString());
    }

    @Test
    void syncReadMemoryBreaksALoop() throws Exception {
        // a pointer chase: the next address is the word read from the current one, through a sync read
        var d = new D("Chase")
                .part("m", "mem", "name", "next", "width", 2, "depth", 4, "read", "sync", "write", false, "init", "2 3 1 0")
                .part("o", "out", "name", "p", "width", 2)
                .net("n1", "m.rdata", "m.addr", "o.a");
        var r = CodeGen.generate(d.doc);
        assertTrue(r.ok(), r.problems() + "\n" + r.java());
        var out = new Elaborator(List.of(), 1000).elaborate(r, "studio");
        assertTrue(out.ok(), out.problems() + "\n" + r.java());
        try (var sim = org.eninehq.tessera.sim.Sim.create(out.design())) {
            var seen = new ArrayList<Long>();
            for (int k = 0; k < 5; k++) { seen.add(sim.peek("p")); sim.step(); }
            assertEquals(List.of(0L, 2L, 1L, 3L, 0L), seen, r.java());
        }
    }

    @Test
    void stateMachineRunsItsTransitionsInOrder() throws Exception {
        var d = new D("Hand")
                .part("g", "in", "name", "go", "width", 1)
                .part("s", "in", "name", "stop", "width", 1)
                .part("f", "fsm", "name", "fsm", "states", "IDLE RUN DONE", "inputs", "go stop",
                        "transitions", "IDLE -> RUN : go\nRUN -> DONE : stop & !go\nRUN -> IDLE : stop\nDONE -> IDLE : 1",
                        "outputs", "busy : RUN DONE\nidle : IDLE")
                .part("o1", "out", "name", "busy", "width", 1)
                .part("o2", "out", "name", "st", "width", 2)
                .net("n1", "g.y", "f.go").net("n2", "s.y", "f.stop").net("n3", "f.busy", "o1.a").net("n4", "f.state", "o2.a");
        var r = CodeGen.generate(d.doc);
        assertTrue(r.ok(), r.problems() + "\n" + r.java());
        assertEquals("fsm", r.netPaths().get("@f"));
        var out = new Elaborator(List.of(), 1000).elaborate(r, "studio");
        assertTrue(out.ok(), out.problems() + "\n" + r.java());
        try (var sim = org.eninehq.tessera.sim.Sim.create(out.design())) {
            assertEquals(0, sim.peek("st"));
            sim.poke("go", 1); sim.step();
            assertEquals(1, sim.peek("st")); assertEquals(1, sim.peek("busy"));
            sim.poke("stop", 1); sim.step();          // stop & !go fails (go is 1); stop alone: back to IDLE
            assertEquals(0, sim.peek("st"), r.java());
            sim.poke("stop", 0); sim.step();          // go: RUN
            sim.poke("go", 0); sim.poke("stop", 1); sim.step();   // stop & !go: DONE
            assertEquals(2, sim.peek("st"));
            sim.step();
            assertEquals(0, sim.peek("st")); assertEquals(0, sim.peek("busy"));
        }
        // a condition naming something that is not an input is a problem, not a crash
        var bad = new D("Bad").part("f", "fsm", "states", "IDLE RUN", "inputs", "", "transitions", "IDLE -> RUN : gogo");
        var rb = CodeGen.generate(bad.doc);
        assertTrue(rb.problems().stream().anyMatch(x -> x.message().contains("there is no input gogo")), rb.problems().toString());
    }

    @Test
    void libraryPartsCallTheStandardLibrary() throws Exception {
        var d = new D("Lib")
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
                .net("n5", "x.y", "pc.a", "pe.a", "dl.a").net("n6", "pc.y", "o4.a").net("n7", "pe.index", "o5.a").net("n8", "pe.valid", "o6.a").net("n9", "dl.y", "o7.a");
        var r = CodeGen.generate(d.doc);
        assertTrue(r.ok(), r.problems() + "\n" + r.java());
        var out = new Elaborator(List.of(), 1000).elaborate(r, "studio");
        assertTrue(out.ok(), out.problems() + "\n" + r.java());
        try (var sim = org.eninehq.tessera.sim.Sim.create(out.design())) {
            sim.poke("x", 0b101100);
            sim.poke("inc", 1);
            assertEquals(3, sim.peek("ones"));
            assertEquals(2, sim.peek("low"));
            assertEquals(1, sim.peek("any"));
            assertEquals(1, sim.peek("rise"));          // inc went 0 -> 1
            var seen = new ArrayList<Long>();
            for (int k = 0; k < 6; k++) { seen.add(sim.peek("value")); sim.step(); }
            assertEquals(List.of(0L, 1L, 2L, 3L, 4L, 0L), seen);
            assertEquals(0, sim.peek("rise"));
            assertEquals(0b101100, sim.peek("late"));   // two cycles after x was set
        }
    }

    @Test
    void javaModulesBecomePartsThroughTheirPublicFields() throws Exception {
        var mods = new Modules(List.of(), 1000);
        var fifo = mods.probe("new Fifo<>(UInt.w(8), 4)", List.of("org.eninehq.tessera.stdlib.Fifo"));
        assertNull(fifo.get("error"), String.valueOf(fifo.get("error")));
        @SuppressWarnings("unchecked") var pins = (List<Map<String, Object>>) fifo.get("pins");
        var byName = new java.util.HashMap<String, Map<String, Object>>();
        for (var q : pins) byName.put(String.valueOf(q.get("name")), q);
        assertEquals(".in.valid()", byName.get("in_valid").get("path"));
        assertEquals("out", byName.get("in_ready").get("dir"));
        assertEquals(3, byName.get("count").get("width"));
        var rf = mods.probe("new RegFile(8, 8, 2, 1, true, false)", List.of("org.eninehq.tessera.stdlib.RegFile"));
        assertNull(rf.get("error"), String.valueOf(rf.get("error")));
        assertTrue(rf.get("pins").toString().contains("path=.read.get(1).data()"), rf.get("pins").toString());
        assertNotNull(mods.probe("new Nope()", List.of()).get("error"));

        var d = new D("Q")
                .part("v", "in", "name", "push", "width", 1).part("b", "in", "name", "data", "width", 8).part("r", "in", "name", "pop", "width", 1)
                .part("f", "module", "name", "q", "expr", "new Fifo<>(UInt.w(8), 4)", "imports", List.of("org.eninehq.tessera.stdlib.Fifo"), "pins", pins)
                .part("o1", "out", "name", "head", "width", 8).part("o2", "out", "name", "have", "width", 1).part("o3", "out", "name", "n", "width", 3)
                .part("o4", "out", "name", "room", "width", 1)
                .net("n1", "v.y", "f.in_valid").net("n2", "b.y", "f.in_bits").net("n3", "r.y", "f.out_ready")
                .net("n4", "f.out_bits", "o1.a").net("n5", "f.out_valid", "o2.a").net("n6", "f.count", "o3.a").net("n7", "f.in_ready", "o4.a");
        var r = CodeGen.generate(d.doc);
        assertTrue(r.ok(), r.problems() + "\n" + r.java());
        assertTrue(r.java().contains("var q = instance(\"q\", () -> new Fifo<>(UInt.w(8), 4));"), r.java());
        assertTrue(r.java().contains("import org.eninehq.tessera.stdlib.Fifo;"), r.java());
        var out = new Elaborator(List.of(), 1000).elaborate(r, "studio");
        assertTrue(out.ok(), out.problems() + "\n" + r.java());
        try (var sim = org.eninehq.tessera.sim.Sim.create(out.design())) {
            sim.poke("push", 1); sim.poke("data", 0x11); sim.step();
            sim.poke("data", 0x22); sim.step();
            sim.poke("push", 0);
            assertEquals(2, sim.peek("n"));
            assertEquals(1, sim.peek("have"));
            assertEquals(0x11, sim.peek("head"));
            sim.poke("pop", 1); sim.step();
            assertEquals(0x22, sim.peek("head"));
            assertEquals(1, sim.peek("n"));
        }
    }

    @Test
    void conditionsParseAndEvaluate() {
        var vals = Map.of("count", java.math.BigInteger.valueOf(10), "valid", java.math.BigInteger.ONE, "core.pc", java.math.BigInteger.valueOf(0x80));
        java.util.function.Function<String, java.math.BigInteger> rd = vals::get;
        assertTrue(Cond.parse("count == 10 && valid").holds(rd));
        assertFalse(Cond.parse("count != 10 || !valid").holds(rd));
        assertTrue(Cond.parse("core.pc >= 0x80 & 1").holds(rd));
        assertTrue(Cond.parse("(count + 1) == 0b1011").holds(rd));
        assertEquals(java.util.Set.of("count", "valid"), Cond.parse("count<3||valid").signals());
        var e = assertThrows(IllegalArgumentException.class, () -> Cond.parse("count == (1").holds(rd));
        assertTrue(e.getMessage().contains("not closed"), e.getMessage());
        var e2 = assertThrows(IllegalArgumentException.class, () -> Cond.parse("nope == 1").holds(rd));
        assertTrue(e2.getMessage().contains("no signal nope"), e2.getMessage());
    }

    @Test
    void codePartsAreHandWrittenClassesInTheDrawing() throws Exception {
        String src = """
                public static class Mix extends Module {
                    public final UInt a, b;
                    public final UInt y;

                    public Mix() {
                        a = input("a", UInt.w(8));
                        b = input("b", UInt.w(8));
                        y = output("y", UInt.w(8));
                        UInt s = a.addWrap(b);
                        y.set(Hw.mux(s.bit(7), s.not(), s));   // anything Java can say
                    }
                }""";
        var mods = new Modules(List.of(), 1000);
        var pr = mods.probeSource("Mix", src);
        assertNull(pr.get("error"), String.valueOf(pr.get("error")));
        var bad = mods.probeSource("Mix", src.replace("a.addWrap(b)", "a.addWrap(q)"));
        assertTrue(String.valueOf(bad.get("error")).contains("line 9"), String.valueOf(bad.get("error")));
        var d = new D("UsesMix")
                .part("x", "in", "name", "x", "width", 8).part("z", "in", "name", "z", "width", 8)
                .part("m", "code", "name", "mix", "cls", "Mix", "source", src, "pins", pr.get("pins"))
                .part("o", "out", "name", "o", "width", 8)
                .net("n1", "x.y", "m.a").net("n2", "z.y", "m.b").net("n3", "m.y", "o.a");
        var r = CodeGen.generate(d.doc);
        assertTrue(r.ok(), r.problems() + "\n" + r.java());
        assertTrue(r.java().contains("    public static class Mix extends Module {"), r.java());
        var out = new Elaborator(List.of(), 1000).elaborate(r, "studio");
        assertTrue(out.ok(), out.problems() + "\n" + r.java());
        try (var sim = org.eninehq.tessera.sim.Sim.create(out.design())) {
            sim.poke("x", 3); sim.poke("z", 4); sim.step();
            assertEquals(7, sim.peek("o"));
            sim.poke("x", 0x80); sim.poke("z", 1); sim.step();
            assertEquals(0x7e, sim.peek("o"));        // 0x81 has bit 7 set: inverted
        }
    }

    @Test
    void theWaveRecordIsCompactAndBounded() {
        var w = new Waves();
        w.retarget(Map.of("a", 8, "wide", 100), java.util.Set.of("a", "wide"), 0);
        w.budget = 1000;
        for (long c = 0; c < 5000; c++) {
            w.sample("a", c, java.math.BigInteger.valueOf(c & 255));
            w.sample("wide", c, java.math.BigInteger.ONE.shiftLeft(99).add(java.math.BigInteger.valueOf(c)));
            w.trim(c);
        }
        assertTrue(w.size() <= 1000, "size " + w.size());
        assertTrue(w.droppedBefore() > 0, "dropped");
        // what is kept is exact: the value at a kept cycle, and the summary of a window
        assertEquals(java.math.BigInteger.valueOf(4990 & 255), w.at("a", 4990));
        assertEquals(java.math.BigInteger.ONE.shiftLeft(99).add(java.math.BigInteger.valueOf(4990)), w.at("wide", 4990));
        assertNull(w.at("a", w.droppedBefore() - 10));
        assertTrue(w.marks().stream().anyMatch(m -> String.valueOf(m.get("text")).contains("dropped")), w.marks().toString());
        w.clear();
        assertEquals(0, w.size());
    }

    @Test
    void timeTravelForgetsOldHistoryButNotTheRecentPast() throws Exception {
        var d = new D("C").part("e", "in", "name", "en", "width", 1).part("r", "reg", "name", "count", "width", 16, "enable", true)
                .part("k", "const", "value", "1", "width", 16).part("s", "add", "mode", "wrap").part("o", "out", "name", "v", "width", 16)
                .net("n1", "e.y", "r.en").net("n2", "r.q", "s.a", "o.a").net("n3", "k.y", "s.b").net("n4", "s.y", "r.d");
        var out = new Elaborator(List.of(), 1000).elaborate(CodeGen.generate(d.doc), "studio");
        assertTrue(out.ok(), out.problems().toString());
        try (var sim = org.eninehq.tessera.sim.Sim.create(out.design())) {
            var tt = new org.eninehq.tessera.sim.TimeTravel(sim, 16);
            tt.poke("en", 1);
            tt.step(1000);
            int before = tt.snapshotCount();
            tt.forgetBefore(800);
            assertTrue(tt.snapshotCount() < before / 4, before + " -> " + tt.snapshotCount());
            assertTrue(tt.earliest() <= 800 && tt.earliest() > 780, "earliest " + tt.earliest());
            tt.goTo(900);
            assertEquals(900, sim.peek("v"));
            assertThrows(IllegalArgumentException.class, () -> tt.goTo(500));
            tt.goTo(tt.head());
            assertEquals(1000, sim.peek("v"));
        }
    }

    @Test
    void aStreamWireJoinsTwoFifosValidAndBitsForwardReadyBack() throws Exception {
        var mods = new Modules(List.of(), 1000);
        var pins = mods.probe("new Fifo<>(UInt.w(8), 4)", List.of("org.eninehq.tessera.stdlib.Fifo")).get("pins");
        var d = new D("Two")
                .part("v", "in", "name", "push", "width", 1).part("b", "in", "name", "data", "width", 8).part("r", "in", "name", "pop", "width", 1)
                .part("f1", "module", "name", "q1", "expr", "new Fifo<>(UInt.w(8), 4)", "imports", List.of("org.eninehq.tessera.stdlib.Fifo"), "pins", pins, "bundle", true, "unbundle", List.of("in"))
                .part("f2", "module", "name", "q2", "expr", "new Fifo<>(UInt.w(8), 4)", "imports", List.of("org.eninehq.tessera.stdlib.Fifo"), "pins", pins, "bundle", true, "unbundle", List.of("out"))
                .part("o1", "out", "name", "head", "width", 8).part("o2", "out", "name", "have", "width", 1).part("o3", "out", "name", "n1", "width", 3).part("o4", "out", "name", "n2", "width", 3)
                .net("n1", "v.y", "f1.in_valid").net("n2", "b.y", "f1.in_bits").net("n3", "r.y", "f2.out_ready")
                .net("s", "f1.out", "f2.in")                                  // one stream wire
                .net("n4", "f2.out_bits", "o1.a").net("n5", "f2.out_valid", "o2.a").net("n6", "f1.count", "o3.a").net("n7", "f2.count", "o4.a");
        var r = CodeGen.generate(d.doc);
        assertTrue(r.ok(), r.problems() + "\n" + r.java());
        assertTrue(r.java().contains("q2.in.valid().set(q1.out.valid());") && r.java().contains("q1.out.ready().set(q2.in.ready());"), r.java());
        assertEquals("q1.out_bits", r.netPaths().get("s:b"));
        var out = new Elaborator(List.of(), 1000).elaborate(r, "studio");
        assertTrue(out.ok(), out.problems() + "\n" + r.java());
        try (var sim = org.eninehq.tessera.sim.Sim.create(out.design())) {
            sim.poke("push", 1);
            for (int x : new int[]{0x11, 0x22, 0x33}) { sim.poke("data", x); sim.step(); }
            sim.poke("push", 0);
            sim.step(); sim.step(); sim.step();
            assertEquals(1, sim.peek("have"));
            assertEquals(0x11, sim.peek("head"));                          // the first in is the first out, through both
            assertEquals(3, sim.peek("n1") + sim.peek("n2"));              // nothing lost or doubled on the way
        }
        // a stream joined to a plain pin is a sentence, not broken Java
        var bad = new D("Bad").part("f1", "module", "name", "q1", "expr", "new Fifo<>(UInt.w(8), 4)", "imports", List.of("org.eninehq.tessera.stdlib.Fifo"), "pins", pins, "bundle", true)
                .part("o", "out", "name", "y", "width", 1).net("s", "f1.out", "o.a");
        assertTrue(CodeGen.generate(bad.doc).problems().stream().anyMatch(x -> x.message().contains("joins a stream to a plain pin")));
    }
}
