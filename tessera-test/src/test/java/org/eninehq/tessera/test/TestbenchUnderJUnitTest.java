package org.eninehq.tessera.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.platform.testkit.engine.EngineTestKit;
import org.junit.platform.testkit.engine.Events;

/**
 * Testbenches as the Tessera Studio writes them (plain {@code @Test} methods that take a {@code Simulator})
 * run under JUnit itself: compiled from source, as a project's src/test/java would be, and run by the
 * Jupiter engine. Each test gets a fresh simulator from reset, and a failure points at its line.
 */
class TestbenchUnderJUnitTest {
    static final String BENCH = """
            package bench;

            import org.eninehq.tessera.sim.Simulator;
            import org.eninehq.tessera.test.Design;
            import org.eninehq.tessera.test.HwTestExtension;
            import org.eninehq.tessera.test.Tally;
            import org.junit.jupiter.api.Test;
            import org.junit.jupiter.api.extension.ExtendWith;
            import static org.junit.jupiter.api.Assertions.assertEquals;

            @ExtendWith(HwTestExtension.class)
            class TallyBench {
                @Design Tally dut() { return new Tally(5); }

                @Test
                void counts(Simulator sim) {
                    assertEquals(5, sim.peek("value"));
                    sim.poke("en", 1);
                    sim.step(10);
                    assertEquals(15, sim.peek("value"));
                }

                @Test
                void startsFromResetToo(Simulator sim) {
                    assertEquals(5, sim.peek("value"));
                    sim.poke("en", 1);
                    sim.step(2);
                    assertEquals(7, sim.peek("value"));
                }

                @Test
                void wrongOnPurpose(Simulator sim) {
                    sim.step(3);
                    assertEquals(-1, sim.peek("value"), "never");
                }
            }
            """;

    static final String NAMED = """
            package bench;

            import org.eninehq.tessera.sim.Simulator;
            import org.eninehq.tessera.test.HwTestExtension;
            import org.eninehq.tessera.test.Tally;
            import org.eninehq.tessera.test.TestsDesign;
            import org.junit.jupiter.api.Test;
            import org.junit.jupiter.api.extension.ExtendWith;
            import static org.junit.jupiter.api.Assertions.assertEquals;

            @ExtendWith(HwTestExtension.class)
            @TestsDesign(Tally.class)
            class NamedBench {
                @Test
                void usesTheNoArgumentConstructor(Simulator sim) {
                    assertEquals(5, sim.peek("value"));
                }
            }
            """;

    static final String UNBOUND = """
            package bench;

            import org.eninehq.tessera.sim.Simulator;
            import org.eninehq.tessera.test.HwTestExtension;
            import org.junit.jupiter.api.Test;
            import org.junit.jupiter.api.extension.ExtendWith;

            @ExtendWith(HwTestExtension.class)
            class UnboundBench {
                @Test
                void noDesign(Simulator sim) {}
            }
            """;

    /** Compile {@code source} (class {@code bench.<name>}) against the test class path and run it with the Jupiter engine. */
    static Events run(Path dir, String name, String source) throws Exception {
        Path src = dir.resolve("src/bench/" + name + ".java"), out = dir.resolve("classes");
        Files.createDirectories(src.getParent());
        Files.createDirectories(out);
        Files.writeString(src, source);
        var javac = ToolProvider.getSystemJavaCompiler();
        int rc = javac.run(null, null, null, "-classpath", System.getProperty("java.class.path"), "-d", out.toString(), "-proc:none", src.toString());
        assertEquals(0, rc, "the testbench compiles");
        var loader = new URLClassLoader(new java.net.URL[]{out.toUri().toURL()}, TestbenchUnderJUnitTest.class.getClassLoader());
        Class<?> k = Class.forName("bench." + name, true, loader);
        return EngineTestKit.engine("junit-jupiter").selectors(selectClass(k)).execute().testEvents();
    }

    /** Each failed test with what it threw, for assertion messages. */
    static String failures(Events ev) {
        var sb = new StringBuilder();
        for (var e : ev.failed().list()) {
            var t = e.getPayload(org.junit.platform.engine.TestExecutionResult.class).flatMap(org.junit.platform.engine.TestExecutionResult::getThrowable);
            sb.append(e.getTestDescriptor().getDisplayName()).append(": ").append(t.map(String::valueOf).orElse("?")).append('\n');
        }
        return sb.toString();
    }

    static int lineOf(String source, String text) {
        List<String> lines = source.lines().toList();
        for (int i = 0; i < lines.size(); i++) if (lines.get(i).contains(text)) return i + 1;
        throw new AssertionError(text);
    }

    @Test
    void eachTestGetsAFreshSimulatorAndAFailureKeepsItsLine(@TempDir Path dir) throws Exception {
        var ev = run(dir, "TallyBench", BENCH);
        assertEquals(1, ev.failed().count(), failures(ev));
        ev.assertStatistics(s -> s.started(3).succeeded(2).failed(1));
        var failure = ev.failed().stream().findFirst().orElseThrow();
        var t = failure.getPayload(org.junit.platform.engine.TestExecutionResult.class).orElseThrow().getThrowable().orElseThrow();
        assertTrue(t.getMessage().startsWith("never"), t.getMessage());
        int line = -1;
        for (var el : t.getStackTrace()) if (el.getClassName().equals("bench.TallyBench")) { line = el.getLineNumber(); break; }
        assertEquals(lineOf(BENCH, "\"never\""), line, "the failing assertEquals's line in the testbench");
        assertEquals("wrongOnPurpose(Simulator)", failure.getTestDescriptor().getDisplayName());
    }

    @Test
    void theDesignCanBeNamedOnTheClass(@TempDir Path dir) throws Exception {
        var ev = run(dir, "NamedBench", NAMED);
        assertEquals(0, ev.failed().count(), failures(ev));
        ev.assertStatistics(s -> s.started(1).succeeded(1));
    }

    @Test
    void aClassThatNamesNoDesignSaysHowToFixIt(@TempDir Path dir) throws Exception {
        var ev = run(dir, "UnboundBench", UNBOUND);
        ev.assertStatistics(s -> s.started(1).failed(1));
        var t = ev.failed().stream().findFirst().orElseThrow()
                .getPayload(org.junit.platform.engine.TestExecutionResult.class).orElseThrow().getThrowable().orElseThrow();
        assertTrue(t.getMessage().contains("add a @Design method"), t.getMessage());
    }
}
