package org.eninehq.tessera.playgroundtests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.regex.Pattern;
import org.eninehq.tessera.core.Bool;
import org.eninehq.tessera.core.Module;
import org.eninehq.tessera.core.UInt;
import org.eninehq.tessera.examples.Counter;
import org.eninehq.tessera.playground.Diagram;
import org.eninehq.tessera.playground.Playground;
import org.eninehq.tessera.stdlib.Clock;
import org.eninehq.tessera.stdlib.Timing;
import org.junit.jupiter.api.Test;

class PlaygroundTest {
    static final HttpClient HTTP = HttpClient.newHttpClient();

    static String get(Playground pg, String path) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create(pg.url() + path.substring(1))).build(), HttpResponse.BodyHandlers.ofString()).body();
    }

    static String post(Playground pg, String path, String body) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create(pg.url() + path.substring(1))).POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString()).body();
    }

    static String value(String state, String port) {
        var m = Pattern.compile("\"name\": \"" + port + "\"[^}]*\"value\": \"([0-9a-f]+)\"").matcher(state);
        return m.find() ? m.group(1) : null;
    }

    /** A board design: the LED blinks; button 0 inverts it; a counter shows on a hex display. */
    public static class Board extends Module {
        public Board(Clock clk) {
            Bool btn = input("btn0", Bool.TYPE);
            Bool blink = Timing.blink(clk, Duration.ofMillis(100));
            output("led", Bool.TYPE).set(blink.xor(btn));
            var counter = instance("counter", () -> new Counter(8));
            counter.en.set(Timing.every(clk, Duration.ofMillis(10)));
            output("hex", UInt.w(8)).set(counter.count);
        }
    }

    @Test
    void boardRunsInRealTimeAndTakesInput() throws Exception {
        try (var pg = Playground.start(Board::new, 0, 2000)) {
            String state = get(pg, "/state");
            assertTrue(state.contains("\"kind\": \"led\"") && state.contains("\"kind\": \"button\"") && state.contains("\"kind\": \"hex\""), state);
            pg.awaitCycles(1000);
            long count = Long.parseLong(value(pg.state(), "hex"), 16);
            assertTrue(count >= 40, "counter advances in real time: " + count);
            post(pg, "/input?name=btn0&value=1", "");
            pg.awaitCycles(10);
            String led1 = value(pg.state(), "led");
            post(pg, "/input?name=btn0&value=0", "");
            assertTrue(led1 != null);
            assertTrue(get(pg, "/").contains("Tessera Playground"));
            assertTrue(get(pg, "/blocks").contains("Tessera Studio"));          // the old editor's address points to its replacement
            String svg = get(pg, "/diagram.svg");
            assertTrue(svg.startsWith("<svg") && svg.contains("counter : Counter"), svg);
            assertTrue(get(pg, "/design.sv").contains("module Board"));
        }
    }

    @Test
    void sourcePostedToRunCompilesAndRuns() throws Exception {
        try (var pg = Playground.start(Board::new, 0, 1000)) {
            String java = """
                    package blocks;

                    import java.time.Duration;
                    import org.eninehq.tessera.core.Hw;
                    import org.eninehq.tessera.core.Module;
                    import org.eninehq.tessera.core.UInt;
                    import org.eninehq.tessera.stdlib.Clock;
                    import org.eninehq.tessera.stdlib.Timing;

                    public class Blinky extends Module {
                        public Blinky(Clock clk) {
                            UInt sw0 = input("sw0", UInt.w(1));
                            UInt count = reg("count", UInt.w(4), 0);
                            Hw.when(Timing.every(clk, Duration.ofMillis(5)), () -> count.set(count.addWrap(1)));
                            output("leds", UInt.w(4)).set(count.xor(sw0.zext(4)));
                        }
                    }
                    """;
            String r = post(pg, "/run?class=blocks.Blinky", java);
            assertTrue(r.contains("\"ok\": true"), r);
            pg.awaitCycles(200);
            assertTrue(pg.state().contains("\"design\": \"Blinky\"") && pg.state().contains("\"name\": \"leds\""), pg.state());
            String bad = post(pg, "/run?class=blocks.Broken", "package blocks; public class Broken extends org.eninehq.tessera.core.Module { public Broken() { int x = ; } }");
            assertTrue(bad.contains("\"ok\": false"), bad);
        }
    }

    @Test
    void editingSourcesReloadsWithoutLosingState() throws Exception {
        Path src = Files.createTempDirectory("pgsrc");
        Path file = Files.createDirectories(src.resolve("demo")).resolve("Count.java");
        String v1 = """
                package demo;
                import org.eninehq.tessera.core.Module;
                import org.eninehq.tessera.core.UInt;
                public class Count extends Module {
                    public Count() {
                        UInt c = reg("c", UInt.w(16), 0);
                        c.set(c.addWrap(STEP));
                        output("value", UInt.w(16)).set(c);
                    }
                    static final int STEP = 1;
                }
                """;
        Files.writeString(file, v1);
        // compile once so the class exists on the context class loader for the first load
        var first = org.eninehq.tessera.cli.DesignCompiler.compile(List.of(src), Files.createTempDirectory("pgout"));
        var prev = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(first.loader());
        Playground pg;
        try {
            pg = Playground.start("demo.Count", List.of(src), 0, 1000);
        } finally {
            Thread.currentThread().setContextClassLoader(prev);
        }
        try (pg) {
            pg.awaitCycles(300);
            long before = Long.parseLong(value(pg.state(), "value"), 16);
            Files.writeString(file, v1.replace("STEP = 1", "STEP = 1000"));
            String r = post(pg, "/reload", "");
            assertTrue(r.contains("\"ok\": true"), r);
            pg.awaitCycles(5);
            long after = Long.parseLong(value(pg.state(), "value"), 16);
            assertTrue(after >= before, "state kept across reload: " + before + " -> " + after);
            pg.awaitCycles(5);
            long later = Long.parseLong(value(pg.state(), "value"), 16);
            assertTrue((later - after + 65536) % 65536 >= 1000, "new behaviour: " + after + " -> " + later);
            assertTrue(pg.state().contains("reloaded Count: kept 1 registers"), pg.state());
            // a width error: the reload reports it with a fix, and /fix repairs the source
            Files.writeString(file, v1.replace("c.set(c.addWrap(STEP));", "c.set(c.add(STEP));"));
            String err = post(pg, "/reload", "");
            assertTrue(err.contains("\"ok\": false") && err.contains("use a wrapping add"), err);
            String fixed = post(pg, "/fix", "");
            assertTrue(fixed.contains("\"ok\": true"), fixed);
            assertTrue(Files.readString(file).contains("c.set(c.addWrap(STEP));"));
        }
    }

    @Test
    void diagramOfAPlainModule() {
        var e = org.eninehq.tessera.core.Tessera.elaborate(() -> new Counter(8));
        String svg = Diagram.svg(e.circuit());
        assertTrue(svg.contains("Counter") && svg.contains("1 registers"), svg);
        assertEquals(1, svg.split("<svg").length - 1);
    }
}
