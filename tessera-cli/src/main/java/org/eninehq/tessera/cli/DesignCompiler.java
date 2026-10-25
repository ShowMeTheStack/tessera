package org.eninehq.tessera.cli;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;

/**
 * Compiles design sources in-process into a fresh class loader, so a design can be edited
 * and reloaded without restarting the JVM (used by {@code tessera fix --loop}, hot swapping
 * and the playground). Classes from the compiled sources are loaded child-first; everything
 * else (Tessera itself) comes from the parent.
 */
public final class DesignCompiler {
    private DesignCompiler() {}

    /** The result of a compilation: a loader for the new classes, or the compiler's errors. */
    public record Result(ClassLoader loader, List<String> errors) {
        public boolean ok() { return errors.isEmpty(); }
    }

    public static Result compile(List<Path> sourceRoots, Path outDir) {
        var compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) throw new IllegalStateException("no Java compiler in this runtime");
        var files = new ArrayList<Path>();
        for (var root : sourceRoots) {
            try (Stream<Path> s = Files.walk(root)) {
                s.filter(p -> p.toString().endsWith(".java")).forEach(files::add);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        try {
            Files.createDirectories(outDir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        var diags = new DiagnosticCollector<JavaFileObject>();
        try (var fm = compiler.getStandardFileManager(diags, Locale.ROOT, null)) {
            var units = fm.getJavaFileObjectsFromPaths(files);
            var opts = List.of("-classpath", System.getProperty("java.class.path"), "-d", outDir.toString(), "-proc:none",
                    "-parameters");
            boolean ok = compiler.getTask(null, fm, diags, opts, null, units).call();
            var errors = new ArrayList<String>();
            for (var d : diags.getDiagnostics())
                if (d.getKind() == Diagnostic.Kind.ERROR)
                    errors.add((d.getSource() == null ? "" : Path.of(d.getSource().toUri()).getFileName() + ":" + d.getLineNumber() + ": ")
                            + d.getMessage(Locale.ROOT));
            if (!ok && errors.isEmpty()) errors.add("compilation failed");
            return new Result(ok ? new ChildFirst(outDir, DesignCompiler.class.getClassLoader()) : null, errors);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Loads classes from one directory before asking the parent. */
    static final class ChildFirst extends URLClassLoader {
        private final Path dir;

        ChildFirst(Path dir, ClassLoader parent) {
            super(new URL[]{url(dir)}, parent);
            this.dir = dir;
        }

        private static URL url(Path p) {
            try {
                return p.toUri().toURL();
            } catch (MalformedURLException e) {
                throw new IllegalArgumentException(e);
            }
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                Class<?> c = findLoadedClass(name);
                if (c == null && Files.exists(dir.resolve(name.replace('.', '/') + ".class"))) c = findClass(name);
                if (c == null) return super.loadClass(name, resolve);
                if (resolve) resolveClass(c);
                return c;
            }
        }
    }
}
