package org.eninehq.tessera.eval;

import java.io.IOException;
import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;
import org.eninehq.tessera.cli.Fixer;
import org.eninehq.tessera.core.Module;
import org.eninehq.tessera.core.Tessera;
import org.eninehq.tessera.core.TesseraException;
import org.eninehq.tessera.test.Verilator;

/**
 * The error corpus. Every case holds one seeded mistake on the line marked
 * {@code // MISTAKE}, written as a Tessera design ({@code Design.java}), in the Tessera javac
 * syntax ({@code syntax/Design.java}) and as SystemVerilog ({@code design.sv}) where it can be
 * expressed. For each form this records where the mistake is caught (compile, elaboration,
 * lint, or missed), whether the message points at the marked line, and, for Tessera
 * diagnostics with a fix-it, whether applying it gives a design that elaborates.
 *
 * <p>Usage: {@code ErrorCorpus <corpus/errors> <out.csv> <tessera-jdk>}
 */
public final class ErrorCorpus {
    private ErrorCorpus() {}

    /** Where a mistake was caught, the message, and whether it points at the marked line. */
    record Outcome(String stage, String message, boolean atLine, String fix) {
        static Outcome missed() {
            return new Outcome("missed", "", false, "");
        }
    }

    public static void main(String[] args) throws Exception {
        Path corpus = Path.of(args[0]), out = Path.of(args[1]);
        Path tesseraJavac = Path.of(args[2], "bin", "javac");
        List<Path> cases;
        try (var s = Files.list(corpus)) {
            cases = s.filter(Files::isDirectory).sorted().toList();
        }
        Files.createDirectories(out.getParent());
        try (var w = new PrintWriter(Files.newBufferedWriter(out))) {
            w.println("case,category,what,form,stage,at_line,fix,message");
            for (Path c : cases) {
                var props = new Properties();
                try (var r = Files.newBufferedReader(c.resolve("case.properties"))) {
                    props.load(r);
                }
                String id = props.getProperty("id"), cat = props.getProperty("category"), what = props.getProperty("what");
                if (Files.exists(c.resolve("Design.java"))) {
                    row(w, id, cat, what, "tessera", tessera(c.resolve("Design.java"), true));
                    // the same without the javac plugin: what elaboration alone catches, and its fix-its
                    row(w, id, cat, what, "tessera-no-plugin", tessera(c.resolve("Design.java"), false));
                }
                if (Files.exists(c.resolve("syntax/Design.java")))
                    row(w, id, cat, what, "tessera-javac", syntax(c.resolve("syntax/Design.java"), tesseraJavac));
                if (Files.exists(c.resolve("design.sv"))) row(w, id, cat, what, "systemverilog", verilog(c.resolve("design.sv")));
                w.flush();
            }
        }
        System.out.println("wrote " + out);
    }

    static void row(PrintWriter w, String id, String cat, String what, String form, Outcome o) {
        w.println(String.join(",", id, cat, csv(what), form, o.stage(), o.stage().equals("missed") ? "" : String.valueOf(o.atLine()),
                o.fix(), csv(o.message())));
        System.out.printf("%s %-13s %-12s line=%-5s fix=%-10s %s%n", id, form, o.stage(), o.atLine(), o.fix(),
                o.message().length() > 110 ? o.message().substring(0, 110) : o.message());
    }

    static String csv(String s) {
        return "\"" + s.replace("\"", "'").replace("\n", " ").replace("\r", " ") + "\"";
    }

    /** The 1-based line holding the {@code MISTAKE} marker. */
    static int marker(Path file) throws IOException {
        var lines = Files.readAllLines(file);
        for (int i = 0; i < lines.size(); i++) if (lines.get(i).contains("MISTAKE")) return i + 1;
        return -1;
    }

    // ------------------------------------------------------------------ Tessera (builder API, javac plugin)

    static Outcome tessera(Path src, boolean plugin) throws Exception {
        Path work = Files.createTempDirectory("corpus");
        Path copy = work.resolve("src/Design.java");
        Files.createDirectories(copy.getParent());
        Files.copy(src, copy);
        int line = marker(src);
        var compiled = compile(copy, work.resolve("classes"), plugin);
        if (compiled != null) return new Outcome("compile", compiled.message(), compiled.atLine() == line, "");
        org.eninehq.tessera.core.Elaborated<Module> e;
        try {
            e = loadAndElaborate(work.resolve("classes"));
        } catch (TesseraException ex) {
            int at = ex.diagnostics().isEmpty() ? -1 : ex.diagnostics().getFirst().loc().line();
            boolean inFile = ex.diagnostics().isEmpty() || ex.diagnostics().getFirst().loc().file().equals("Design.java");
            // the message may name the line instead ("first assigned under a condition at Design.java:9")
            boolean named = ex.getMessage().contains("Design.java:" + line + ")") || ex.getMessage().contains("Design.java:" + line + " ");
            return new Outcome("elaboration", ex.getMessage(), inFile && at == line || named, fixIt(ex, work, copy));
        } catch (Throwable t) {
            return new Outcome("elaboration-crash", t.toString(), false, "");
        }
        try {
            Verilator.lint(e.circuit(), "corpus-" + src.getParent().getFileName());
        } catch (IOException ex) {
            String msg = ex.getMessage() == null ? ex.toString() : ex.getMessage();
            return new Outcome("lint", firstWarning(msg), false, "");
        }
        return Outcome.missed();
    }

    record CompileError(int atLine, String message) {}

    /** Compile with the system javac and the Tessera plugin; the first error, or null. */
    static CompileError compile(Path src, Path classes, boolean plugin) throws IOException {
        Files.createDirectories(classes);
        var javac = ToolProvider.getSystemJavaCompiler();
        var diags = new DiagnosticCollector<JavaFileObject>();
        try (var fm = javac.getStandardFileManager(diags, null, null)) {
            var units = fm.getJavaFileObjects(src.toFile());
            var opts = new ArrayList<>(List.of("-classpath", System.getProperty("java.class.path"), "-d", classes.toString(),
                    "-proc:none", "-Xlint:none"));
            if (plugin) opts.add("-Xplugin:TesseraCheck");
            boolean ok = javac.getTask(null, fm, diags, opts, null, units).call();
            if (ok) return null;
            for (var d : diags.getDiagnostics())
                if (d.getKind() == javax.tools.Diagnostic.Kind.ERROR) return new CompileError((int) d.getLineNumber(), d.getMessage(null));
            return new CompileError(-1, "compilation failed");
        }
    }

    static org.eninehq.tessera.core.Elaborated<Module> loadAndElaborate(Path classes) throws Exception {
        try (var cl = new URLClassLoader(new java.net.URL[]{classes.toUri().toURL()}, ErrorCorpus.class.getClassLoader())) {
            var ctor = cl.loadClass("Design").getConstructor();
            Supplier<Module> s = () -> {
                try {
                    return (Module) ctor.newInstance();
                } catch (InvocationTargetException e) {
                    if (e.getCause() instanceof RuntimeException r) throw r;
                    throw new RuntimeException(e.getCause());
                } catch (ReflectiveOperationException e) {
                    throw new RuntimeException(e);
                }
            };
            return Tessera.elaborate(s);
        }
    }

    /** Apply the first fix-it to a copy and rebuild: "fixed", "fix-fails", or "" without a fix. */
    static String fixIt(TesseraException e, Path work, Path copy) {
        if (e.diagnostics().stream().allMatch(d -> d.fixes().isEmpty())) return "";
        try {
            var applied = Fixer.apply(e, List.of(copy.getParent()));
            if (applied.isEmpty()) return "not-applicable";
            Path classes = work.resolve("classes-fixed");
            if (compile(copy, classes, false) != null) return "fix-fails";
            loadAndElaborate(classes);
            return "fixed";
        } catch (Throwable t) {
            return "fix-fails";
        }
    }

    static String firstWarning(String log) {
        for (String l : log.split("\n")) if (l.contains("%Warning") || l.contains("%Error")) return l.strip();
        return log.strip();
    }

    // ------------------------------------------------------------------ the Tessera javac (uint, bits<N>)

    static Outcome syntax(Path src, Path javac) throws Exception {
        if (!Files.isExecutable(javac)) return new Outcome("not-run", "Tessera JDK not found", false, "");
        Path classes = Files.createTempDirectory("corpus-syntax");
        var p = new ProcessBuilder(javac.toString(), "-d", classes.toString(), src.toString()).redirectErrorStream(true).start();
        String log = new String(p.getInputStream().readAllBytes());
        p.waitFor(120, TimeUnit.SECONDS);
        if (p.exitValue() == 0) return Outcome.missed();
        int line = -1;
        String msg = log.strip();
        var m = java.util.regex.Pattern.compile("Design\\.java:(\\d+): error: (.*)").matcher(log);
        if (m.find()) {
            line = Integer.parseInt(m.group(1));
            msg = m.group(2);
        }
        return new Outcome("compile", msg, line == marker(src), "");
    }

    // ------------------------------------------------------------------ plain SystemVerilog (Verilator)

    static Outcome verilog(Path sv) throws Exception {
        int line = marker(sv);
        var cmd = new ArrayList<>(List.of("verilator", "--lint-only", "--top-module", "dut"));
        cmd.addAll(Verilator.LINT_FLAGS);
        cmd.add(sv.toString());
        var p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String log = new String(p.getInputStream().readAllBytes());
        p.waitFor(120, TimeUnit.SECONDS);
        String first = null;
        boolean error = false;
        int at = -1;
        var m = java.util.regex.Pattern.compile("%(Warning|Error)(-[A-Z0-9_]+)?: [^:]*design\\.sv:(\\d+):\\d+: (.*)").matcher(log);
        while (m.find()) {
            int l = Integer.parseInt(m.group(3));
            if (first == null || l == line) {
                first = m.group(1) + (m.group(2) == null ? "" : m.group(2)) + ": " + m.group(4);
                at = l;
                error = m.group(1).equals("Error");
                if (l == line) break;
            }
        }
        if (first == null) return p.exitValue() == 0 ? Outcome.missed() : new Outcome("lint-error", log.strip(), false, "");
        return new Outcome(error ? "lint-error" : "lint-warning", first, at == line, "");
    }
}
