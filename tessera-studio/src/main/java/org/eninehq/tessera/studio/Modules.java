package org.eninehq.tessera.studio;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.eninehq.tessera.cli.DesignCompiler;
import org.eninehq.tessera.core.Bool;
import org.eninehq.tessera.core.Data;
import org.eninehq.tessera.core.Module;
import org.eninehq.tessera.core.SInt;
import org.eninehq.tessera.core.Tessera;
import org.eninehq.tessera.core.TesseraException;
import org.eninehq.tessera.ir.Signal;
import org.eninehq.tessera.stdlib.Clock;

/**
 * Modules written in Java, used as parts of a drawing: the standard library's (a FIFO, an arbiter, a
 * register file) and the project's own (every {@code Module} subclass in the {@code --src} sources whose
 * constructor takes nothing or a {@code Clock}).
 *
 * <p>A module part is a Java expression that makes the module, such as {@code new Fifo<>(UInt.w(8), 16)}.
 * Its pins are found by elaborating that expression: each port of the result is matched, by identity, to
 * the public field (or record component, or list element) that holds it, which gives the Java the drawing
 * uses to reach it ({@code u.in.valid()}, {@code u.read.get(0).addr()}).
 */
final class Modules {
    /** A module that can be placed: a title for the palette, the expression and the imports it needs. */
    record Choice(String title, String expr, List<String> imports, String help) {
        Map<String, Object> json() {
            var m = new LinkedHashMap<String, Object>();
            m.put("title", title);
            m.put("expr", expr);
            m.put("imports", imports);
            m.put("help", help);
            return m;
        }
    }

    private static final List<Choice> STDLIB = List.of(
            new Choice("FIFO", "new Fifo<>(UInt.w(8), 16)", List.of("org.eninehq.tessera.stdlib.Fifo"),
                    "A first-in first-out queue between two streams (valid, ready, bits); count is how many items it holds."),
            new Choice("Arbiter", "new Arbiter<>(UInt.w(8), 2, Arbiter.Policy.ROUND_ROBIN)", List.of("org.eninehq.tessera.stdlib.Arbiter"),
                    "Merges n streams into one, by priority or round robin; chosen says which input went through."),
            new Choice("Register file", "new RegFile(32, 32, 2, 1, true, false)", List.of("org.eninehq.tessera.stdlib.RegFile"),
                    "count × width registers with read and write ports (here 32 × 32, two reads, one write, register 0 reads 0)."),
            new Choice("True dual-port RAM", "new TrueDualPortRam(256, 32)", List.of("org.eninehq.tessera.stdlib.TrueDualPortRam"),
                    "depth × width words with two ports, a and b, each reading (one cycle after the address) and writing its own address; one block RAM on an FPGA."),
            new Choice("Register file (live-value table)", "new LvtRegFile(32, 32, 4, 2, true)", List.of("org.eninehq.tessera.stdlib.LvtRegFile"),
                    "A register file with several write ports from one-write LUT RAMs and a table of last writers (here 32 × 32, four reads, two writes, register 0 reads 0)."));

    /** What a code part's class may use without importing it (the generated file imports the same). */
    static final List<String> CODE_IMPORTS = List.of("org.eninehq.tessera.core.*", "org.eninehq.tessera.core.Module", "org.eninehq.tessera.stdlib.*",
            "org.eninehq.tessera.stdlib.Clock", "java.util.List", "java.util.Map");

    private static final Pattern MODULE_CLASS = Pattern.compile("public\\s+(?:final\\s+)?class\\s+(\\w+)\\s+extends\\s+Module\\b");
    private static final Pattern PACKAGE = Pattern.compile("^\\s*package\\s+([\\w.]+)\\s*;", Pattern.MULTILINE);

    private final List<Path> sources;
    private final long simHz;
    private final Path work;
    private final Map<String, Map<String, Object>> cache = new ConcurrentHashMap<>();
    private int generation;

    Modules(List<Path> sources, long simHz) throws IOException {
        this.sources = sources;
        this.simHz = simHz;
        this.work = Files.createTempDirectory("tessera-studio-modules");
    }

    /** The choices: the standard library's, then the project's. */
    List<Object> list() throws IOException {
        var l = new ArrayList<Object>();
        for (var c : STDLIB) { var m = c.json(); m.put("group", "Standard library"); l.add(m); }
        for (var c : project()) { var m = c.json(); m.put("group", "This project"); l.add(m); }
        return l;
    }

    private List<Choice> project() throws IOException {
        var l = new ArrayList<Choice>();
        for (Path root : sources) {
            if (!Files.isDirectory(root)) continue;
            try (Stream<Path> files = Files.walk(root)) {
                for (Path f : files.filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
                    String text = Files.readString(f);
                    var m = MODULE_CLASS.matcher(text);
                    if (!m.find()) continue;
                    String cls = m.group(1);
                    var pk = PACKAGE.matcher(text);
                    String fqn = (pk.find() ? pk.group(1) + "." : "") + cls;
                    // the first public constructor whose arguments all have an obvious default; the inspector edits them
                    var k = Pattern.compile("public\\s+" + cls + "\\s*\\(([^)]*)\\)").matcher(text);
                    String args = null, sig = null;
                    while (k.find() && args == null) {
                        var vals = new ArrayList<String>();
                        boolean ok = true;
                        for (String a : k.group(1).trim().isEmpty() ? new String[0] : k.group(1).split(",")) {
                            String type = a.trim().replaceAll("\\s+\\w+$", "").trim();
                            String v = switch (type) {
                                case "int", "long", "Integer", "Long" -> "8";
                                case "boolean", "Boolean" -> "false";
                                case "Clock" -> "clk";
                                case "double", "float" -> "1.0";
                                default -> null;
                            };
                            if (v == null) { ok = false; break; }
                            vals.add(v);
                        }
                        if (ok) { args = String.join(", ", vals); sig = k.group(1).trim(); }
                    }
                    if (args == null) continue;
                    l.add(new Choice(cls, "new " + cls + "(" + args + ")", List.of(fqn),
                            "A module of this project: " + fqn + (sig.isEmpty() ? "" : " (" + sig + "; edit the arguments in the inspector)") + "."));
                }
            }
        }
        return l;
    }

    /**
     * The pins of a Java code part: {@code source} is a nested class ({@code public static class X extends Module}),
     * compiled inside a holder class so that it can be probed like any module.
     */
    Map<String, Object> probeSource(String cls, String source) {
        if (!cls.matches("[A-Z]\\w*")) return Map.of("error", "The class name must be a Java type name.");
        if (!source.matches("(?s).*\\bclass\\s+" + cls + "\\b.*")) return Map.of("error", "The code must declare the class " + cls + ".");
        return probe("new Holder." + cls + (takesClock(cls, source) ? "(clk)" : "()"), List.of(), "public final class Holder {\n" + source + "\n}\n");
    }

    /** Whether a code part's class is made with the clock ({@code public Cls(Clock clk)}). */
    static boolean takesClock(String cls, String source) {
        return Pattern.compile("\\b" + Pattern.quote(cls) + "\\s*\\(\\s*Clock\\s+\\w+\\s*\\)").matcher(source).find();
    }

    /** The pins of the module {@code expr} makes: [{name, dir, width, path, jtype}], or {error}. */
    Map<String, Object> probe(String expr, List<String> imports) { return probe(expr, imports, null); }

    private Map<String, Object> probe(String expr, List<String> imports, String holder) {
        String key = expr + "|" + imports + "|" + holder;
        var hit = cache.get(key);
        if (hit != null) return hit;
        var r = probeUncached(expr, imports, holder);
        if (!r.containsKey("error")) cache.put(key, r);
        return r;
    }

    private synchronized Map<String, Object> probeUncached(String expr, List<String> imports, String holder) {
        var res = new LinkedHashMap<String, Object>();
        int g = ++generation;
        Path src = work.resolve("src" + g), out = work.resolve("classes" + g);
        try {
            var java = new StringBuilder("package studio_probe;\n\n");
            for (String i : CODE_IMPORTS) java.append("import ").append(i).append(";\n");
            for (String i : imports) if (i.matches("[\\w.]+")) java.append("import ").append(i).append(";\n");
            if (holder != null) {   // a code part: the holder of its class, in a file of its own
                Path hf = src.resolve("studio_probe/Holder.java");
                Files.createDirectories(hf.getParent());
                var hj = new StringBuilder("package studio_probe;\n\n");
                for (String i : CODE_IMPORTS) hj.append("import ").append(i).append(";\n");
                Files.writeString(hf, hj + "\n" + holder);
            }
            java.append("\npublic final class Probe {\n    public static Module make(Clock clk) {\n        return ").append(expr).append(";\n    }\n}\n");
            Path file = src.resolve("studio_probe/Probe.java");
            Files.createDirectories(file.getParent());
            Files.writeString(file, java);
            var roots = new ArrayList<Path>();
            for (Path r : sources) if (Files.isDirectory(r)) roots.add(r);   // a missing --src directory is not this module's problem
            roots.add(src);
            var c = DesignCompiler.compile(roots, out);
            if (!c.ok()) {
                int off = holder == null ? 0 : CODE_IMPORTS.size() + 4;   // the lines the holder adds before the class
                res.put("error", "Java: " + String.join("; ", c.errors().stream().map(e -> {
                    var lm = Pattern.compile("^.*?(Holder)?\\.java:(\\d+): (.*)$", Pattern.DOTALL).matcher(e);
                    if (!lm.matches()) return e.lines().findFirst().orElse("");
                    String msg = lm.group(3).lines().findFirst().orElse("");
                    return lm.group(1) != null ? "line " + (Integer.parseInt(lm.group(2)) - off) + ": " + msg : msg;
                }).toList()));
                return res;
            }
            var make = Class.forName("studio_probe.Probe", true, c.loader()).getMethod("make", Clock.class);
            Clock clk = Clock.hz(simHz);
            Module[] held = new Module[1];
            var e = Tessera.elaborate(() -> {
                try { return held[0] = (Module) make.invoke(null, clk); }
                catch (java.lang.reflect.InvocationTargetException x) { throw x.getCause() instanceof RuntimeException re ? re : new IllegalStateException(x.getCause()); }
                catch (ReflectiveOperationException x) { throw new IllegalStateException(x); }
            });
            // where each port's Data lives in the module object
            var where = new IdentityHashMap<Signal, String[]>();
            walk(held[0], "", where, 0, new IdentityHashMap<>());
            var pins = new ArrayList<Object>();
            var missing = new ArrayList<String>();
            for (Signal s : e.circuit().top().ports()) {
                if (s.name().equals("clock") || s.name().equals("reset")) continue;
                String[] w = where.get(s);
                if (w == null) { missing.add(s.name()); continue; }
                var m = new LinkedHashMap<String, Object>();
                m.put("name", s.name());
                m.put("dir", s.kind() == Signal.Kind.INPUT ? "in" : "out");
                m.put("width", s.width());
                m.put("path", w[0]);
                m.put("jtype", w[1]);
                pins.add(m);
            }
            if (!missing.isEmpty()) {
                res.put("error", "Ports " + String.join(", ", missing) + " are not in public fields, so a drawing cannot reach them.");
                return res;
            }
            res.put("module", e.circuit().top().name());
            res.put("clock", expr.matches("(?s).*\\bclk\\b.*"));
            res.put("pins", pins);
            return res;
        } catch (TesseraException x) {
            res.put("error", x.getMessage());
            return res;
        } catch (Exception x) {
            res.put("error", String.valueOf(x.getCause() != null ? x.getCause() : x));
            return res;
        } finally {
            for (int old = g - 2; old >= Math.max(1, g - 4); old--)
                for (var d : List.of(work.resolve("src" + old), work.resolve("classes" + old))) Elaborator.deleteTree(d);
        }
    }

    /** Record, for every Data reachable from {@code o} through public fields, records and lists, the Java that reaches it. */
    private static void walk(Object o, String path, Map<Signal, String[]> where, int depth, Map<Object, Boolean> seen) {
        if (o == null || depth > 6 || seen.put(o, true) != null) return;
        if (o instanceof Data d) {
            if (d.signal() != null) where.putIfAbsent(d.signal(), new String[]{path, o instanceof Bool ? "Bool" : o instanceof SInt ? "SInt" : "UInt"});
            return;
        }
        if (o instanceof Module && depth > 0) return;
        if (o instanceof List<?> l) {
            for (int i = 0; i < l.size(); i++) walk(l.get(i), path + ".get(" + i + ")", where, depth + 1, seen);
            return;
        }
        Class<?> c = o.getClass();
        if (c.isRecord()) {
            for (RecordComponent rc : c.getRecordComponents()) {
                try { walk(rc.getAccessor().invoke(o), path + "." + rc.getName() + "()", where, depth + 1, seen); }
                catch (ReflectiveOperationException ignored) { /* not reachable */ }
            }
            return;
        }
        if (!(o instanceof Module) && !c.getName().startsWith("org.eninehq.tessera")) return;
        for (Class<?> k = c; k != null && k != Object.class && k != Module.class; k = k.getSuperclass())
            for (Field f : k.getDeclaredFields()) {
                if (!Modifier.isPublic(f.getModifiers()) || Modifier.isStatic(f.getModifiers())) continue;
                try { walk(f.get(o), path + "." + f.getName(), where, depth + 1, seen); }
                catch (IllegalAccessException ignored) { /* not public after all */ }
            }
    }
}
