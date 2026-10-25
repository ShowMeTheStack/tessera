package org.eninehq.tessera.playground;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.function.Supplier;
import org.eninehq.tessera.cli.DesignCompiler;
import org.eninehq.tessera.cli.Fixer;
import org.eninehq.tessera.core.Elaborated;
import org.eninehq.tessera.core.Module;
import org.eninehq.tessera.core.Tessera;
import org.eninehq.tessera.core.TesseraException;
import org.eninehq.tessera.emit.SvEmitter;
import org.eninehq.tessera.ir.Signal;
import org.eninehq.tessera.sim.HotSwap;
import org.eninehq.tessera.sim.Sim;
import org.eninehq.tessera.sim.Simulator;
import org.eninehq.tessera.stdlib.Clock;

/**
 * A virtual board in the browser. The design runs continuously in the simulator at
 * {@code simHz} cycles per second; ports named {@code led*} light up, {@code btn*} are push
 * buttons, {@code sw*} are switches, {@code seg*} (7 bits) and {@code hex*} (4 bits per
 * digit) are displays, and every other port is shown as a number. Designs that take a
 * {@link Clock} are elaborated for the simulation speed, so a one-second blink blinks once a
 * second on screen.
 *
 * <p>Edit the Java sources and press reload (or build a design from blocks on the
 * {@code /blocks} page): the design is recompiled in the running JVM and continues from its
 * current state.
 *
 * <pre>
 * java ... org.eninehq.tessera.playground.Playground --top com.example.Blinky --src src/main/java
 * </pre>
 */
public final class Playground implements AutoCloseable {
    private final HttpServer server;
    private final long simHz;
    private final List<Path> sources;
    private volatile String topClass;
    private volatile Elaborated<Module> design;
    private volatile Simulator sim;
    private volatile String message = "";
    private volatile boolean running = true;
    private final ConcurrentLinkedQueue<Runnable> inputs = new ConcurrentLinkedQueue<>();
    private final List<HttpExchange> listeners = new CopyOnWriteArrayList<>();
    private final Thread loop;
    private volatile String lastState = "{}";
    private volatile TesseraException lastError;

    private Playground(Supplier<Module> factory, String topClass, List<Path> sources, int port, long simHz) throws IOException {
        this.simHz = simHz;
        this.sources = sources;
        this.topClass = topClass;
        load(factory, false);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/", this::handle);
        server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        loop = Thread.ofVirtual().name("playground-sim").start(this::run);
    }

    /** Start a playground for {@code design}, which receives the simulation clock. Port 0 picks a free port. */
    public static Playground start(Function<Clock, ? extends Module> design, int port, long simHz) throws IOException {
        Clock clk = Clock.hz(simHz);
        return new Playground(() -> design.apply(clk), null, List.of(), port, simHz);
    }

    /** Start a playground for class {@code topClass}, recompiled from {@code sources} on reload. */
    public static Playground start(String topClass, List<Path> sources, int port, long simHz) throws IOException {
        var pg = new Playground(() -> instantiate(Thread.currentThread().getContextClassLoader(), topClass, Clock.hz(simHz)),
                topClass, sources, port, simHz);
        return pg;
    }

    public int port() { return server.getAddress().getPort(); }

    public String url() { return "http://127.0.0.1:" + port() + "/"; }

    @Override public void close() {
        running = false;
        server.stop(0);
        loop.interrupt();
        var s = sim;
        if (s != null) s.close();
    }

    // ------------------------------------------------------------------ design loading

    static Module instantiate(ClassLoader loader, String cls, Clock clk) {
        if (cls.contains("::")) {
            var prev = Thread.currentThread().getContextClassLoader();
            Thread.currentThread().setContextClassLoader(loader);
            try {
                return org.eninehq.tessera.cli.TesseraCli.factory(cls).get();
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("cannot create " + cls + ": " + e);
            } finally {
                Thread.currentThread().setContextClassLoader(prev);
            }
        }
        try {
            Class<?> c = Class.forName(cls, true, loader);
            try {
                var ctor = c.getDeclaredConstructor(Clock.class);
                ctor.setAccessible(true);
                return (Module) ctor.newInstance(clk);
            } catch (NoSuchMethodException e) {
                var ctor = c.getDeclaredConstructor();
                ctor.setAccessible(true);
                return (Module) ctor.newInstance();
            }
        } catch (java.lang.reflect.InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException re) throw re;
            throw new IllegalStateException(e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot create " + cls + ": " + e);
        }
    }

    /** Elaborate a new design and run it, carrying the state over when {@code keepState}. */
    private synchronized void load(Supplier<Module> factory, boolean keepState) {
        Elaborated<Module> e = Tessera.elaborate(factory);
        Simulator next;
        if (keepState && sim != null) {
            var swapped = HotSwap.swap(sim, e.circuit(), Sim.Backend.COMPILED);
            next = swapped.sim();
            message = "reloaded " + e.circuit().top().name() + ": kept " + swapped.report().registers() + " registers"
                    + (swapped.report().newRegisters().isEmpty() ? "" : ", new: " + swapped.report().newRegisters());
        } else {
            if (sim != null) sim.close();
            next = Sim.create(e, Sim.Backend.COMPILED);
            next.reset(1);
            message = "running " + e.circuit().top().name();
        }
        design = e;
        sim = next;
    }

    /** Recompile the sources and reload. Returns null on success, or the errors. */
    public synchronized String reload() {
        if (topClass == null || sources.isEmpty()) return "no sources to reload";
        try {
            var out = Files.createTempDirectory("tessera-playground");
            var r = DesignCompiler.compile(sources, out);
            if (!r.ok()) return String.join("\n", r.errors());
            var prev = Thread.currentThread().getContextClassLoader();
            Thread.currentThread().setContextClassLoader(r.loader());
            try {
                String cls = topClass;
                ClassLoader loader = r.loader();
                load(() -> instantiate(loader, cls, Clock.hz(simHz)), true);
            } finally {
                Thread.currentThread().setContextClassLoader(prev);
            }
            return null;
        } catch (TesseraException ex) {
            lastError = ex;
            return ex.getMessage() + "\n" + Fixer.json(ex, sources);
        } catch (IOException | RuntimeException ex) {
            return ex.toString();
        }
    }

    /** Apply the suggested fixes of the last reload error to the sources, then reload. */
    public synchronized String applyFixes() {
        var ex = lastError;
        if (ex == null) return "nothing to fix";
        try {
            var applied = Fixer.apply(ex, sources);
            if (applied.isEmpty()) return "no automatic fix for: " + ex.getMessage();
            lastError = null;
            String err = reload();
            var sb = new StringBuilder();
            for (var a : applied) sb.append(a.file().getFileName()).append(':').append(a.line()).append(": ").append(a.description()).append('\n');
            return err == null ? null : sb + err;
        } catch (IOException e) {
            return e.toString();
        }
    }

    /** Compile one source file (from the block editor) whose class is {@code cls} and run it. */
    public synchronized String runSource(String cls, String source) {
        try {
            Path dir = Files.createTempDirectory("tessera-blocks");
            Path file = dir.resolve(cls.replace('.', '/') + ".java");
            Files.createDirectories(file.getParent());
            Files.writeString(file, source);
            var r = DesignCompiler.compile(List.of(dir), dir.resolve("out"));
            if (!r.ok()) return String.join("\n", r.errors());
            ClassLoader loader = r.loader();
            var prev = Thread.currentThread().getContextClassLoader();
            Thread.currentThread().setContextClassLoader(loader);
            try {
                load(() -> instantiate(loader, cls, Clock.hz(simHz)), false);
            } finally {
                Thread.currentThread().setContextClassLoader(prev);
            }
            topClass = null;
            return null;
        } catch (TesseraException ex) {
            return ex.getMessage();
        } catch (IOException | RuntimeException ex) {
            return ex.toString();
        }
    }

    // ------------------------------------------------------------------ simulation loop

    private void run() {
        final int fps = 30;
        long perFrame = Math.max(1, simHz / fps);
        while (running) {
            long t0 = System.nanoTime();
            try {
                synchronized (this) {
                    Runnable in;
                    while ((in = inputs.poll()) != null) in.run();
                    for (long i = 0; i < perFrame; i++) sim.step();
                    lastState = stateJson();
                }
                broadcast(lastState);
            } catch (RuntimeException | AssertionError e) {
                message = "stopped: " + e.getMessage();
                lastState = stateJson();
                broadcast(lastState);
                sleep(500);
                continue;
            }
            long left = 1_000_000_000L / fps - (System.nanoTime() - t0);
            if (left > 0) sleep(left / 1_000_000);
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Wait until the simulator has passed {@code cycles} more cycles (for tests). */
    public void awaitCycles(long cycles) {
        long target = sim.cycle() + cycles;
        while (sim.cycle() < target) sleep(5);
    }

    public String state() { return lastState; }

    static String kind(Signal s) {
        String n = s.name().toLowerCase();
        if (s.kind() == Signal.Kind.INPUT) {
            if (n.startsWith("btn") || n.startsWith("button") || n.startsWith("key")) return "button";
            if (n.startsWith("sw") || n.startsWith("switch")) return "switch";
            return "number";
        }
        if (n.startsWith("led")) return "led";
        if (n.startsWith("seg") && s.width() == 7) return "seg";
        if (n.startsWith("hex") || n.startsWith("seg")) return "hex";
        return "number";
    }

    private String stateJson() {
        var sb = new StringBuilder("{\"cycle\": ").append(sim.cycle()).append(", \"hz\": ").append(simHz)
                .append(", \"design\": ").append(Fixer.str(design.circuit().top().name()))
                .append(", \"message\": ").append(Fixer.str(message)).append(", \"ports\": [");
        boolean first = true;
        for (var p : design.circuit().top().ports()) {
            if (p.isClockOrReset()) continue;
            if (!first) sb.append(", ");
            first = false;
            BigInteger v = sim.peekBig(p.name());
            sb.append("{\"name\": ").append(Fixer.str(p.name())).append(", \"dir\": \"")
                    .append(p.kind() == Signal.Kind.INPUT ? "in" : "out").append("\", \"width\": ").append(p.width())
                    .append(", \"kind\": \"").append(kind(p)).append("\", \"value\": \"").append(v.toString(16)).append("\"}");
        }
        return sb.append("]}").toString();
    }

    private void broadcast(String json) {
        byte[] msg = ("data: " + json + "\n\n").getBytes(StandardCharsets.UTF_8);
        for (var ex : listeners) {
            try {
                OutputStream os = ex.getResponseBody();
                os.write(msg);
                os.flush();
            } catch (IOException e) {
                listeners.remove(ex);
                ex.close();
            }
        }
    }

    // ------------------------------------------------------------------ HTTP

    private void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        try {
            switch (path) {
                case "/" -> send(ex, 200, "text/html; charset=utf-8", resource("board.html"));
                // the block editor was replaced by Tessera Studio; the address says where it went
                case "/blocks" -> send(ex, 200, "text/html; charset=utf-8",
                        "<!doctype html><meta charset=utf-8><title>Blocks: replaced by Tessera Studio</title><body style=\"font:15px/1.6 system-ui;max-width:640px;margin:60px auto;padding:0 20px\">"
                          + "<h1>The block editor is now Tessera Studio</h1><p>Draw, run, watch and prove designs in <b>Tessera Studio</b>:</p>"
                          + "<pre>./gradlew :tessera-studio:installDist\ntessera-studio/build/install/tessera-studio/bin/tessera-studio</pre>"
                          + "<p>Its guide is at <code>/guide</code> in the Studio, and in <code>docs/studio-guide.md</code>.</p></body>");
                case "/state" -> send(ex, 200, "application/json", lastState);
                case "/diagram.svg" -> send(ex, 200, "image/svg+xml", Diagram.svg(design.circuit()));
                case "/design.sv" -> send(ex, 200, "text/plain; charset=utf-8", SvEmitter.emit(design));
                case "/events" -> {
                    ex.getResponseHeaders().add("Content-Type", "text/event-stream");
                    ex.getResponseHeaders().add("Cache-Control", "no-cache");
                    ex.sendResponseHeaders(200, 0);
                    listeners.add(ex);
                }
                case "/input" -> {
                    var q = query(ex.getRequestURI().getRawQuery());
                    String name = q.get("name");
                    BigInteger v = new BigInteger(q.getOrDefault("value", "0"));
                    inputs.add(() -> sim.pokeBig(name, v));
                    send(ex, 200, "application/json", "{\"ok\": true}");
                }
                case "/reset" -> {
                    inputs.add(() -> sim.reset(1));
                    send(ex, 200, "application/json", "{\"ok\": true}");
                }
                case "/reload" -> {
                    String err = reload();
                    send(ex, 200, "application/json", err == null ? "{\"ok\": true}" : "{\"ok\": false, \"error\": " + Fixer.str(err) + "}");
                }
                case "/fix" -> {
                    String err = applyFixes();
                    send(ex, 200, "application/json", err == null ? "{\"ok\": true}" : "{\"ok\": false, \"error\": " + Fixer.str(err) + "}");
                }
                case "/run" -> {
                    String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                    String cls = query(ex.getRequestURI().getRawQuery()).getOrDefault("class", "blocks.Design");
                    String err = runSource(cls, body);
                    send(ex, 200, "application/json", err == null ? "{\"ok\": true}" : "{\"ok\": false, \"error\": " + Fixer.str(err) + "}");
                }
                default -> send(ex, 404, "text/plain", "not found");
            }
        } catch (RuntimeException e) {
            send(ex, 500, "text/plain", e.toString());
        }
    }

    private static Map<String, String> query(String raw) {
        var m = new java.util.LinkedHashMap<String, String>();
        if (raw == null) return m;
        for (var kv : raw.split("&")) {
            int i = kv.indexOf('=');
            if (i > 0) m.put(URLDecoder.decode(kv.substring(0, i), StandardCharsets.UTF_8), URLDecoder.decode(kv.substring(i + 1), StandardCharsets.UTF_8));
        }
        return m;
    }

    private static String resource(String name) throws IOException {
        try (InputStream in = Playground.class.getResourceAsStream(name)) {
            if (in == null) throw new IOException("missing resource " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void send(HttpExchange ex, int status, String type, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", type);
        ex.sendResponseHeaders(status, b.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(b);
        }
    }

    /** {@code --top <class> [--src <dir>[:<dir>]] [--port 8080] [--hz 10000]} */
    public static void main(String[] args) throws Exception {
        var opt = new java.util.HashMap<String, String>();
        for (int i = 0; i + 1 < args.length; i += 2) opt.put(args[i].replaceFirst("^--", ""), args[i + 1]);
        String top = opt.get("top");
        if (top == null) {
            System.err.println("usage: playground --top <class> [--src <dir>] [--port 8080] [--hz 10000]");
            System.exit(2);
        }
        var src = new ArrayList<Path>();
        if (opt.containsKey("src")) for (var s : opt.get("src").split(java.io.File.pathSeparator)) src.add(Path.of(s));
        var pg = start(top, src, Integer.parseInt(opt.getOrDefault("port", "8080")), Long.parseLong(opt.getOrDefault("hz", "10000")));
        System.out.println("Tessera playground: " + pg.url() + "  (blocks: " + pg.url() + "blocks)");
        Thread.currentThread().join();
    }
}
