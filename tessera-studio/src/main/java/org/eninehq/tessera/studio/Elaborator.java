package org.eninehq.tessera.studio;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.eninehq.tessera.cli.DesignCompiler;
import org.eninehq.tessera.core.Elaborated;
import org.eninehq.tessera.core.Module;
import org.eninehq.tessera.core.Tessera;
import org.eninehq.tessera.core.TesseraException;
import org.eninehq.tessera.ir.Signal;
import org.eninehq.tessera.stdlib.Clock;

/**
 * Compiles the Java a drawing generates (with the project's own sources, so drawings can use the
 * project's modules) and elaborates it. Compiler and elaborator messages are mapped back to the
 * parts whose lines they point at, so they appear in the problems list like the studio's own.
 */
public final class Elaborator {
    /** The outcome: the elaborated design when it worked, and the problems either way. */
    public record Outcome(Elaborated<Module> design, List<CodeGen.Problem> problems, long millis) {
        public boolean ok() { return design != null; }
    }

    private static final Pattern JAVAC = Pattern.compile("^(?:[^:]*\\.java):(\\d+): (.*)$", Pattern.DOTALL);

    private final List<Path> projectSources;
    private final long simHz;
    private final Path work;
    private int generation;

    public Elaborator(List<Path> projectSources, long simHz) {
        this.projectSources = projectSources;
        this.simHz = simHz;
        try {
            this.work = Files.createTempDirectory("tessera-studio");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public long simHz() { return simHz; }

    /** The project's source roots (designs and modules the drawing may use). */
    public List<Path> projectSources() { return projectSources; }

    /** A fresh directory for one compilation's sources and classes. */
    public synchronized Path scratch(String what) {
        try { return Files.createTempDirectory(work, what); } catch (IOException e) { throw new UncheckedIOException(e); }
    }

    /** Compile and elaborate a generated design. */
    public synchronized Outcome elaborate(CodeGen.Result gen, String pkg) {
        long t0 = System.nanoTime();
        var problems = new ArrayList<CodeGen.Problem>();
        int g = ++generation;
        Path src = work.resolve("src" + g), out = work.resolve("classes" + g);
        try {
            Path file = src.resolve(pkg.replace('.', '/')).resolve(gen.className() + ".java");
            Files.createDirectories(file.getParent());
            Files.writeString(file, gen.java());
            var roots = new ArrayList<Path>(projectSources);
            roots.add(src);
            var r = DesignCompiler.compile(roots, out);
            if (!r.ok()) {
                for (var e : r.errors()) problems.add(compilerProblem(e, gen));
                return new Outcome(null, problems, ms(t0));
            }
            Class<?> c = Class.forName(pkg + "." + gen.className(), true, r.loader());
            Clock clk = Clock.hz(simHz);
            Elaborated<Module> e = Tessera.elaborate(() -> construct(c, clk));
            return new Outcome(e, problems, ms(t0));
        } catch (TesseraException e) {
            for (var d : e.diagnostics()) problems.add(tesseraProblem(d, gen));
            return new Outcome(null, problems, ms(t0));
        } catch (Exception e) {
            problems.add(new CodeGen.Problem("error", "elaboration", "Elaboration failed: " + e, List.of(), List.of(), List.of()));
            return new Outcome(null, problems, ms(t0));
        } finally {
            cleanOld(g);
        }
    }

    private static Module construct(Class<?> c, Clock clk) {
        try {
            try {
                var k = c.getDeclaredConstructor(Clock.class);
                k.setAccessible(true);
                return (Module) k.newInstance(clk);
            } catch (NoSuchMethodException e) {
                var k = c.getDeclaredConstructor();
                k.setAccessible(true);
                return (Module) k.newInstance();
            }
        } catch (java.lang.reflect.InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException re) throw re;
            throw new IllegalStateException(e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot create " + c.getName() + ": " + e);
        }
    }

    private static long ms(long t0) {
        return (System.nanoTime() - t0) / 1_000_000;
    }

    /** Keep the last two generations of sources and classes (a running simulation may still use the previous one). */
    private void cleanOld(int g) {
        for (int old = g - 2; old >= Math.max(1, g - 4); old--)
            for (var d : List.of(work.resolve("src" + old), work.resolve("classes" + old))) deleteTree(d);
    }

    static void deleteTree(Path d) {
        if (!Files.exists(d)) return;
        try (Stream<Path> s = Files.walk(d)) {
            s.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException ignored) {
            // a temporary directory; nothing to do
        }
    }

    private static List<String> partsAt(CodeGen.Result gen, int line) {
        var l = new ArrayList<String>();
        for (var e : gen.lines().entrySet()) if (e.getValue().contains(line)) l.add(e.getKey());
        return l;
    }

    private static CodeGen.Problem compilerProblem(String error, CodeGen.Result gen) {
        Matcher m = JAVAC.matcher(error);
        if (m.matches()) {
            int line = Integer.parseInt(m.group(1));
            return new CodeGen.Problem("error", "javac", "Java: " + m.group(2).lines().findFirst().orElse("").trim() + " (line " + line + ")",
                    partsAt(gen, line), List.of(), List.of());
        }
        return new CodeGen.Problem("error", "javac", "Java: " + error, List.of(), List.of(), List.of());
    }

    private static CodeGen.Problem tesseraProblem(TesseraException.Diagnostic d, CodeGen.Result gen) {
        var parts = d.loc() == null ? List.<String>of() : partsAt(gen, d.loc().line());
        var fixes = new ArrayList<Map<String, Object>>();
        for (var f : d.fixes()) {
            var m = new LinkedHashMap<String, Object>();
            m.put("label", f.description());
            m.put("op", "java");
            m.put("find", f.find());
            m.put("replace", f.replace());
            fixes.add(m);
        }
        return new CodeGen.Problem("error", "tessera", d.message() + (d.loc() == null ? "" : " (line " + d.loc().line() + ")"), parts, List.of(), fixes);
    }

    /** The module's ports, as the browser lists them (for the waves and the board). */
    public static List<Map<String, Object>> ports(Elaborated<Module> e) {
        var l = new ArrayList<Map<String, Object>>();
        for (Signal p : e.circuit().top().ports()) {
            var m = new LinkedHashMap<String, Object>();
            m.put("name", p.name());
            m.put("dir", p.kind() == Signal.Kind.INPUT ? "in" : "out");
            m.put("width", p.width());
            m.put("clockOrReset", p.isClockOrReset());
            l.add(m);
        }
        return l;
    }
}
