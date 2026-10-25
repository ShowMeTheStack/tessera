package org.eninehq.tessera.studio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.eninehq.tessera.cli.DesignCompiler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.platform.testkit.engine.EngineTestKit;

/** Testbenches beyond the live run: the template, each test from reset, and the same class under JUnit itself. */
class BenchJUnitTest {
    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> results(Map<String, Object> r) {
        assertEquals(true, r.get("ok"), r.toString());
        return (List<Map<String, Object>>) r.get("results");
    }

    static long value(Session s) {
        return MultiClockTest.last(s, "value");
    }

    @Test
    void theTemplateNamesTheDrawingAndRunsInTheStudio() {
        var s = new Session(new Elaborator(List.of(), 1000));
        assertEquals(true, s.load(CodeGenTest.counter().doc).get("elaborated"));
        String src = String.valueOf(s.bench(Map.of("action", "template", "in", "en", "out", "value")).get("source"));
        assertTrue(src.contains("@ExtendWith(HwTestExtension.class)") && src.contains("class CounterTest {")
                && src.contains("@Design Counter dut() { return new Counter(); }") && src.contains("import studio.Counter;"), src);
        // it compiles in the Studio, where the drawing's class is not in any project
        var res = results(s.bench(Map.of("source", src)));
        assertEquals(1, res.size());
        assertEquals(true, res.get(0).get("ok"), res.toString());
    }

    static final String FROM_ZERO = """
            import org.eninehq.tessera.sim.Simulator;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertEquals;

            class FromZero {
                @Test
                void startsAtZero(Simulator sim) {
                    assertEquals(0, sim.peek("value"));
                    sim.step(5);
                    assertEquals(0, sim.peek("value"), "en is 0 again");
                    sim.poke("en", 1);
                    sim.step(3);
                }

                @Test
                void andSoDoesTheNext(Simulator sim) {
                    assertEquals(0, sim.peek("value"));
                }
            }
            """;

    @Test
    void eachTestFromResetStartsLikeANewSimulator() {
        var s = new Session(new Elaborator(List.of(), 1000));
        assertEquals(true, s.load(CodeGenTest.counter().doc).get("elaborated"));
        s.command(Map.of("cmd", "poke", "path", "en", "value", "1"));
        s.command(Map.of("cmd", "step", "n", 20));
        assertTrue(value(s) >= 20, "the counter ran: " + value(s));
        // on the live run the first assertion fails: the counter is where the run left it
        var live = results(s.bench(Map.of("source", FROM_ZERO)));
        assertEquals(false, live.get(0).get("ok"), live.toString());
        // from reset both pass, and the run's history has the resets in it (the timeline only goes on)
        long c0 = ((Number) s.command(Map.of("cmd", "step", "n", 0)).get("cycle")).longValue();
        var reset = results(s.bench(Map.of("source", FROM_ZERO, "fromReset", true)));
        assertEquals(true, reset.get(0).get("ok"), reset.toString());
        assertEquals(true, reset.get(1).get("ok"), reset.toString());
        assertEquals(8L, (long) reset.get(0).get("to") - (long) reset.get(0).get("from"), "the test's own cycles, after the reset");
        assertTrue((long) reset.get(0).get("from") >= c0 + 2, "two reset cycles before the first test: " + reset);
        assertTrue((long) reset.get(1).get("from") >= (long) reset.get(0).get("to") + 2, "and before the second: " + reset);
    }

    @Test
    void eachTestFromResetAlsoStartsWithTheMemoriesInitialContents() {
        // a RAM (initial word 1 at address 1) written by the first test: from reset, the second reads the initial word,
        // as a new simulator under JUnit would; on the live run it reads what the first test wrote
        var d = new CodeGenTest.D("Ram").part("a", "in", "name", "addr", "width", 2)
                .part("wd", "in", "name", "wdata", "width", 8).part("we", "in", "name", "we", "width", 1)
                .part("ram", "mem", "name", "ram", "width", 8, "depth", 4, "init", "0 1 2 3")
                .part("o", "out", "name", "q", "width", 8)
                .net("n1", "a.y", "ram.addr").net("n3", "wd.y", "ram.wdata").net("n4", "we.y", "ram.we").net("n5", "ram.rdata", "o.a");
        String bench = """
                import org.eninehq.tessera.sim.Simulator;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertEquals;
                class RamBench {
                    @Test void writes(Simulator sim) {
                        sim.poke("addr", 1); sim.poke("wdata", 0x5a); sim.poke("we", 1); sim.step(); sim.poke("we", 0); sim.step();
                        assertEquals(0x5a, sim.peek("q"));
                    }
                    @Test void readsTheInitialWord(Simulator sim) {
                        sim.poke("addr", 1); sim.step();
                        assertEquals(1, sim.peek("q"));
                    }
                }
                """;
        var live = new Session(new Elaborator(List.of(), 1000));
        assertEquals(true, live.load(d.doc).get("elaborated"));
        var r = results(live.bench(Map.of("source", bench)));
        assertEquals(true, r.get(0).get("ok"), r.toString());
        assertEquals(false, r.get(1).get("ok"), "on the live run the word written by the first test is still there: " + r);
        var fresh = new Session(new Elaborator(List.of(), 1000));
        assertEquals(true, fresh.load(d.doc).get("elaborated"));
        r = results(fresh.bench(Map.of("source", bench, "fromReset", true)));
        assertEquals(true, r.get(0).get("ok"), r.toString());
        assertEquals(true, r.get(1).get("ok"), "from reset the memory is back to its initial contents: " + r);
        assertEquals("1", ((List<?>) fresh.mem(Map.of("path", "ram", "from", 1, "count", 1)).get("words")).getFirst());
    }

    @Test
    void aTestbenchWithoutImportsGetsTheExtensionsImportsAfterItsPackage(@TempDir Path project) throws Exception {
        Path main = project.resolve("src/main/java");
        Files.createDirectories(main.resolve("studio"));
        var s = new Session(new Elaborator(List.of(main), 1000));
        var doc = CodeGenTest.counter().doc;
        assertEquals(true, s.load(doc).get("elaborated"));
        // the template's own way of making the design, written with full names so that no import is needed
        String template = String.valueOf(s.bench(Map.of("action", "template", "in", "en", "out", "value")).get("source"));
        var dm = java.util.regex.Pattern.compile("@Design\\s+(\\w+)\\s+dut\\(\\)\\s*\\{\\s*return\\s+new\\s+(\\w+)\\(([^)]*)\\);").matcher(template);
        assertTrue(dm.find(), template);
        String args = dm.group(3).replace("Clock.", "org.eninehq.tessera.core.Clock.");
        String bare = "class Bare {\n"
                + "    @org.eninehq.tessera.test.Design studio." + dm.group(1) + " dut() { return new studio." + dm.group(2) + "(" + args + "); }\n"
                + "    @org.junit.jupiter.api.Test void runs(org.eninehq.tessera.sim.Simulator sim) { sim.step(3); }\n"
                + "}\n";
        String java = String.valueOf(s.bench(Map.of("action", "junit", "source", bare)).get("java"));
        assertTrue(java.startsWith("package studio;\n\nimport org.eninehq.tessera.test.HwTestExtension;\nimport org.junit.jupiter.api.extension.ExtendWith;\n"), java);
        assertTrue(java.contains("@ExtendWith(HwTestExtension.class)\nclass Bare"), java);
        // and it compiles with the drawing's class and passes under JUnit
        Files.writeString(main.resolve("studio/Counter.java"), CodeGen.generate(doc).java());
        Path test = project.resolve("src/test/java/studio/Bare.java");
        Files.createDirectories(test.getParent());
        Files.writeString(test, java);
        var c = DesignCompiler.compile(List.of(main, project.resolve("src/test/java")), project.resolve("classes"));
        assertTrue(c.ok(), String.valueOf(c.errors()));
        var ev = EngineTestKit.engine("junit-jupiter").selectors(selectClass(Class.forName("studio.Bare", true, c.loader()))).execute().testEvents();
        ev.assertStatistics(st -> st.started(1).succeeded(1));
    }

    @Test
    void savedAsAJUnitTestItRunsUnderJUnitFromReset(@TempDir Path project) throws Exception {
        Path main = project.resolve("src/main/java");
        Files.createDirectories(main);
        var s = new Session(new Elaborator(List.of(main), 1000));
        var doc = CodeGenTest.counter().doc;
        assertEquals(true, s.load(doc).get("elaborated"));
        String bench = String.valueOf(s.bench(Map.of("action", "template", "in", "en", "out", "value")).get("source"))
                .replace("assertTrue(v >= 0, \"value is \" + v);", "assertEquals(10, v);");
        // without writing: what would go where, and that the design is not in the project yet
        var dry = s.bench(Map.of("action", "junit", "source", bench));
        Path target = project.resolve("src/test/java/studio/CounterTest.java");
        assertEquals(target.toString(), dry.get("path"));
        assertEquals(false, dry.get("wrote"));
        assertFalse(Files.exists(target));
        assertTrue(String.valueOf(dry.get("note")).contains("no studio/Counter.java yet"), dry.toString());
        String java = String.valueOf(dry.get("java"));
        assertTrue(java.startsWith("package studio;\n"), java);
        // written, and not over an existing file unless asked
        assertEquals(true, s.bench(Map.of("action", "junit", "source", bench, "write", true)).get("wrote"));
        assertEquals(java, Files.readString(target));
        var again = s.bench(Map.of("action", "junit", "source", bench.replace("10", "11"), "write", true));
        assertEquals(true, again.get("exists"));
        assertEquals(false, again.get("wrote"));
        assertEquals(java, Files.readString(target));
        // with the drawing saved into the project, the project compiles and JUnit runs the test: it passes,
        // so the counter started at 0 and counted 10 with a simulator of its own
        Files.createDirectories(main.resolve("studio"));
        Files.writeString(main.resolve("studio/Counter.java"), CodeGen.generate(doc).java());
        assertNull(s.bench(Map.of("action", "junit", "source", bench)).get("note"));
        var c = DesignCompiler.compile(List.of(main, project.resolve("src/test/java")), project.resolve("classes"));
        assertTrue(c.ok(), String.valueOf(c.errors()));
        var ev = EngineTestKit.engine("junit-jupiter").selectors(selectClass(Class.forName("studio.CounterTest", true, c.loader()))).execute().testEvents();
        ev.assertStatistics(st -> st.started(1).succeeded(1));
        // a class that does not say which design it tests is refused, with what to add
        var e = org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> s.bench(Map.of("action", "junit", "source", FROM_ZERO)));
        assertTrue(e.getMessage().contains("add a @Design method"), e.getMessage());
        // and one that does, without the extension, gets it
        String noExt = bench.replace("@ExtendWith(HwTestExtension.class)\n", "").replace("import org.eninehq.tessera.test.HwTestExtension;\n", "");
        String fixed = String.valueOf(s.bench(Map.of("action", "junit", "source", noExt)).get("java"));
        assertTrue(fixed.contains("@ExtendWith(HwTestExtension.class)\nclass CounterTest {") && fixed.contains("import org.eninehq.tessera.test.HwTestExtension;"), fixed);
    }
}
