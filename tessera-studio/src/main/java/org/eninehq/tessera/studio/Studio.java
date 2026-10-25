package org.eninehq.tessera.studio;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Tessera Studio's server: serves the page and a JSON API on the loopback interface only.
 *
 * <pre>
 * java ... org.eninehq.tessera.studio.Studio [--port 8800] [--src src/main/java] [--hz 1000] [--dev]
 * </pre>
 *
 * {@code --src} adds a project's sources, whose modules can then be used in drawings;
 * {@code --dev} serves the front end file by file from {@code src/main/web}.
 */
public final class Studio implements AutoCloseable {
    private final HttpServer server;
    private final Elaborator elaborator;
    private final Path devWeb;
    private final Map<String, java.util.function.Supplier<? extends org.eninehq.tessera.core.Module>> designs;
    final Session session;
    private final Modules modules;
    private final Fpga fpga;

    public Studio(int port, List<Path> sources, long simHz, Path devWeb) throws IOException {
        this(port, sources, simHz, devWeb, List.of());
    }

    public Studio(int port, List<Path> sources, long simHz, Path devWeb, List<String> registries) throws IOException {
        this.elaborator = new Elaborator(sources, simHz);
        this.modules = new Modules(sources, simHz);
        this.fpga = new Fpga(sources);
        this.designs = Designs.available(registries);
        this.devWeb = devWeb;
        this.session = new Session(elaborator);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/", this::handle);
        server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
        server.start();
    }

    public int port() { return server.getAddress().getPort(); }

    public String url() { return "http://127.0.0.1:" + port() + "/"; }

    @Override public void close() {
        session.close();
        server.stop(0);
    }

    private void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        try {
            if (path.equals("/") || path.equals("/studio")) {
                send(ex, 200, "text/html; charset=utf-8", page());
                return;
            }
            if (path.equals("/guide")) {
                send(ex, 200, "text/html; charset=utf-8", Guide.page());
                return;
            }
            if (path.startsWith("/guide/studio-guide/")) {
                byte[] png = Guide.picture(path.substring(path.lastIndexOf('/') + 1));
                if (png == null) { send(ex, 404, "text/plain", "not found"); return; }
                ex.getResponseHeaders().set("Content-Type", "image/png");
                ex.sendResponseHeaders(200, png.length);
                try (OutputStream o = ex.getResponseBody()) { o.write(png); }
                return;
            }
            if (devWeb != null && path.startsWith("/web/")) {
                Path f = devWeb.resolve(path.substring(5)).normalize();
                if (!f.startsWith(devWeb) || !Files.isRegularFile(f)) { send(ex, 404, "text/plain", "not found"); return; }
                String type = f.toString().endsWith(".css") ? "text/css" : f.toString().endsWith(".js") ? "text/javascript" : "text/plain";
                send(ex, 200, type + "; charset=utf-8", Files.readString(f));
                return;
            }
            if (path.startsWith("/api/")) {
                Object r = api(path.substring(5), ex);
                if (r instanceof Sse) return;
                send(ex, 200, "application/json", Json.write(r));
                return;
            }
            send(ex, 404, "text/plain", "not found");
        } catch (IllegalArgumentException e) {
            send(ex, 400, "application/json", Json.write(Map.of("error", e.getMessage())));
        } catch (Exception e) {
            send(ex, 500, "application/json", Json.write(Map.of("error", String.valueOf(e))));
        }
    }

    /** Marker for endpoints that answered by streaming. */
    record Sse() {}

    private Object api(String name, HttpExchange ex) throws IOException {
        Map<String, Object> req = ex.getRequestMethod().equals("POST") ? body(ex) : Map.of();
        return switch (name) {
            case "parts" -> Parts.catalogue();
            case "info" -> Map.of("hz", elaborator.simHz());
            case "guide" -> Guide.sections();
            case "pins" -> Parts.pinsJson(String.valueOf(req.get("type")), CodeGen.map(req.get("params")));
            case "codegen" -> CodeGen.generate(CodeGen.map(req.get("doc"))).json();
            case "elaborate" -> session.load(CodeGen.map(req.get("doc")));
            case "open" -> open(String.valueOf(req.get("java")));
            case "read" -> {   // edited Java back to the drawing it came from
                var r = readJava(String.valueOf(req.get("java")), CodeGen.map(req.get("doc")));
                yield Map.of("doc", r.doc(), "report", r.report());
            }
            case "events" -> { session.stream(ex); yield new Sse(); }
            case "sim" -> session.command(req);
            case "waves" -> session.waves(req);
            case "fst" -> session.fst(req);
            case "uart" -> session.uart(req);
            case "activity" -> session.activity(req);
            case "transactions" -> session.transactions(req);
            case "pipeview" -> session.pipeview(req);
            case "bench" -> session.bench(req);
            case "prove" -> session.prove(req);
            case "designs" -> new ArrayList<>(designs.keySet());
            case "programs" -> Map.of("cores", new ArrayList<>(Designs.CORES.keySet()), "programs", new ArrayList<>(Designs.programs().keySet()));
            case "modules" -> modules.list();
            case "analyse" -> session.analyse(req);
            case "timing" -> switch (String.valueOf(req.getOrDefault("action", "map"))) {
                case "map" -> session.timing(req);
                case "run" -> fpga.startTiming(session.again(), ((Number) req.getOrDefault("period", 10)).doubleValue(),
                        ((Number) req.getOrDefault("paths", 20)).intValue());
                case "status" -> fpga.status(((Number) req.get("job")).intValue());
                default -> throw new IllegalArgumentException("timing: action is map, run or status");
            };
            case "code" -> modules.probeSource(String.valueOf(req.getOrDefault("cls", "")), String.valueOf(req.getOrDefault("source", "")));
            case "sv" -> session.sv();
            case "mem" -> session.mem(req);
            case "fpga" -> switch (String.valueOf(req.getOrDefault("action", "plan"))) {
                case "plan" -> fpga.planFor(CodeGen.map(req.get("doc")));
                case "build" -> fpga.start(CodeGen.map(req.get("doc")), Boolean.TRUE.equals(req.get("program")));
                case "status" -> fpga.status(((Number) req.get("job")).intValue());
                default -> throw new IllegalArgumentException("unknown fpga action");
            };
            case "mems" -> session.mems();
            case "module" -> modules.probe(String.valueOf(req.getOrDefault("expr", "")),
                    req.get("imports") instanceof List<?> l ? l.stream().map(String::valueOf).toList() : List.of());
            case "view" -> {
                String dn = String.valueOf(req.get("design"));
                java.util.function.Supplier<? extends org.eninehq.tessera.core.Module> d = designs.get(dn);
                if (d == null) throw new IllegalArgumentException("no design called " + dn);
                if (req.get("program") instanceof String prog && !prog.isBlank()) {   // a core running a chosen program
                    var core = Designs.CORES.get(dn);
                    var bin = Designs.programs().get(prog);
                    if (core == null) throw new IllegalArgumentException(dn + " is not a core that takes a program.");
                    if (bin == null) throw new IllegalArgumentException("There is no program " + prog + ".");
                    int[] image = Designs.image(bin);
                    d = () -> core.apply(image);
                }
                long t0 = System.nanoTime();
                var factory = d;
                @SuppressWarnings("unchecked")
                java.util.function.Supplier<org.eninehq.tessera.core.Elaborated<org.eninehq.tessera.core.Module>> elaborate =
                        () -> (org.eninehq.tessera.core.Elaborated<org.eninehq.tessera.core.Module>) (org.eninehq.tessera.core.Elaborated<?>) org.eninehq.tessera.core.Tessera.elaborate(factory::get);
                var e = elaborate.get();
                long t1 = System.nanoTime();
                var v = session.loadDesign(e, dn, elaborate);
                v.put("elaborateMs", (t1 - t0) / 1_000_000);
                v.put("loadMs", (System.nanoTime() - t1) / 1_000_000);   // drawing it, and building its simulator
                yield v;
            }
            default -> throw new IllegalArgumentException("no such API: " + name);
        };
    }

    /**
     * A Java file as a drawing. A file the studio wrote and nobody edited opens from the drawing it carries;
     * an edited file, or one written by hand, is read back from its Java (the carried drawing, if any,
     * keeps the places of the parts that are still there).
     */
    private Map<String, Object> open(String java) {
        var embedded = CodeGen.embedded(java);
        int mark = java.lastIndexOf(CodeGen.MARK);
        String body = mark < 0 ? java : java.substring(0, mark);
        var m = new LinkedHashMap<String, Object>();
        if (embedded != null) {
            String again = CodeGen.generate(embedded).java();
            int am = again.lastIndexOf(CodeGen.MARK);
            if ((am < 0 ? again : again.substring(0, am)).strip().equals(body.strip())) {
                m.put("doc", embedded);
                m.put("report", List.of());
                return m;
            }
        }
        var read = readJava(body, embedded);
        m.put("doc", read.doc());
        m.put("report", read.report());
        return m;
    }

    JavaReader.Read readJava(String src, Map<String, Object> previous) { return JavaReader.readSource(src, previous, elaborator, modules); }

    private String page() throws IOException {
        if (devWeb != null) {
            String shell = Files.readString(devWeb.resolve("shell.html"));
            var css = new StringBuilder();
            var js = new StringBuilder();
            for (String f : manifest(devWeb)) {
                if (f.endsWith(".css")) css.append("@import url(\"/web/").append(f).append("\");\n");
                else js.append("</script><script src=\"/web/").append(f).append("\"></script><script>\n");
            }
            return shell.replace("/*@CSS@*/", css).replace("//@JS@", js);
        }
        try (InputStream in = Studio.class.getResourceAsStream("studio.html")) {
            if (in == null) throw new IOException("studio.html is not on the classpath (run the bundleWeb task)");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    static List<String> manifest(Path web) throws IOException {
        var l = new ArrayList<String>();
        for (String line : Files.readAllLines(web.resolve("manifest.txt"))) {
            String t = line.trim();
            if (!t.isEmpty() && !t.startsWith("#")) l.add(t);
        }
        return l;
    }

    private static Map<String, Object> body(HttpExchange ex) throws IOException {
        String text = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        return text.isBlank() ? Map.of() : Json.object(text);
    }

    static void send(HttpExchange ex, int status, String type, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", type);
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(status, b.length);
        try (OutputStream o = ex.getResponseBody()) {
            o.write(b);
        }
    }

    public static void main(String[] args) throws Exception {
        int port = 8800;
        long hz = 1000;
        var src = new ArrayList<Path>();
        var regs = new ArrayList<String>();
        Path dev = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--port" -> port = Integer.parseInt(args[++i]);
                case "--hz" -> hz = Long.parseLong(args[++i]);
                case "--designs" -> regs.add(args[++i]);
                case "--src" -> { for (var s : args[++i].split(java.io.File.pathSeparator)) src.add(Path.of(s)); }
                case "--dev" -> dev = Path.of(i + 1 < args.length && !args[i + 1].startsWith("--") ? args[++i] : "tessera-studio/src/main/web").toAbsolutePath();
                default -> throw new IllegalArgumentException("unknown option " + args[i]);
            }
        }
        var s = new Studio(port, src, hz, dev, regs);
        System.out.println("Tessera Studio: " + s.url());
    }
}
