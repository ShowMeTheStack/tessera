package org.eninehq.tessera.syntaxlitetests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.net.URI;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import org.eninehq.tessera.core.Module;
import org.eninehq.tessera.core.Tessera;
import org.eninehq.tessera.emit.SvEmitter;
import org.junit.jupiter.api.Test;

/** -Xplugin:TesseraSyntaxLite on stock javac: unsigned lowering and automatic local names. */
class SyntaxLiteTest {

    static final String CHECKER = System.getProperty("tessera.checker.classes");
    static final String LITE = System.getProperty("tessera.lite.classes");

    record Result(List<String> errors, Path out) {}

    static Result compile(String className, String source, List<String> plugins, String processorPath) throws Exception {
        var compiler = ToolProvider.getSystemJavaCompiler();
        var diags = new DiagnosticCollector<JavaFileObject>();
        var file = new SimpleJavaFileObject(URI.create("string:///demo/" + className + ".java"), JavaFileObject.Kind.SOURCE) {
            @Override public CharSequence getCharContent(boolean ignore) { return source; }
        };
        Path out = Files.createTempDirectory("lite");
        var opts = new ArrayList<>(List.of("-classpath", System.getProperty("java.class.path"), "-processorpath", processorPath,
                "-d", out.toString(), "-proc:none"));
        for (String p : plugins) opts.add("-Xplugin:" + p);
        compiler.getTask(null, null, diags, opts, null, List.of(file)).call();
        var errs = new ArrayList<String>();
        for (var d : diags.getDiagnostics())
            if (d.getKind() == Diagnostic.Kind.ERROR) errs.add("line " + d.getLineNumber() + ": " + d.getMessage(Locale.ROOT));
        return new Result(errs, out);
    }

    static Result both(String className, String source) throws Exception {
        return compile(className, source, List.of("TesseraCheck", "TesseraSyntaxLite"), LITE + File.pathSeparator + CHECKER);
    }

    static String run(Path out, String className) throws Exception {
        var buf = new ByteArrayOutputStream();
        PrintStream saved = System.out;
        try (var loader = new URLClassLoader(new java.net.URL[] {out.toUri().toURL()}, SyntaxLiteTest.class.getClassLoader())) {
            System.setOut(new PrintStream(buf, true));
            loader.loadClass("demo." + className).getMethod("main", String[].class).invoke(null, (Object) new String[0]);
        } finally {
            System.setOut(saved);
        }
        return buf.toString().strip();
    }

    static final String UNSIGNED = """
            package demo;
            import org.eninehq.tessera.core.Unsigned;
            public class Low {
                static @Unsigned int half(@Unsigned int x) { return x / 2; }
                static long wide(long x) { return x; }
                public static void main(String[] args) {
                    @Unsigned int a = 0xFFFF_FFFE, b = 7;
                    @Unsigned long big = 0xFFFF_FFFF_FFFF_FFFFL;
                    System.out.println("" + (a / b) + " " + (a % b) + " " + (a > b) + " " + (b <= a) + " " + (a >> 28));
                    System.out.println("" + big / 3 + " " + (big > 1L) + " " + (big >> 60));
                    long w = a;
                    long w2 = (long) a;
                    System.out.println(w + " " + w2 + " " + wide(a) + " " + half(a));
                    @Unsigned int c = a;
                    c /= 3; System.out.println("" + c);
                    c = a; c %= 1000; System.out.println("" + c);
                    c = a; c >>= 1; System.out.println("" + c);
                    for (@Unsigned int i = 0xFFFF_FFFD; i > 5; i++) System.out.println("i=" + i);
                    var r = new java.util.Random(7);
                    boolean ok = true;
                    for (int k = 0; k < 100000; k++) {
                        int x = r.nextInt(), y = r.nextInt() | 1;
                        @Unsigned int ux = (@Unsigned int) x, uy = (@Unsigned int) y;
                        ok &= (int) (ux / uy) == Integer.divideUnsigned(x, y);
                        ok &= (int) (ux % uy) == Integer.remainderUnsigned(x, y);
                        ok &= (ux < uy) == (Integer.compareUnsigned(x, y) < 0);
                        ok &= (ux >= uy) == (Integer.compareUnsigned(x, y) >= 0);
                        ok &= (int) (ux >> 3) == x >>> 3;
                        ok &= ("" + ux).equals(Integer.toUnsignedString(x));
                        long lx = r.nextLong(), ly = r.nextLong() | 1;
                        @Unsigned long ulx = (@Unsigned long) lx, uly = (@Unsigned long) ly;
                        ok &= (long) (ulx / uly) == Long.divideUnsigned(lx, ly);
                        ok &= (ulx <= uly) == (Long.compareUnsigned(lx, ly) <= 0);
                        ok &= ("" + ulx).equals(Long.toUnsignedString(lx));
                    }
                    System.out.println(ok);
                }
            }
            """;

    static final String EXPECTED = """
            613566756 2 true true 15
            6148914691236517205 true 15
            4294967294 4294967294 4294967294 2147483647
            1431655764
            294
            2147483647
            i=4294967293
            i=4294967294
            i=4294967295
            true""";

    @Test
    void unsignedOperatorsAreLowered() throws Exception {
        var r = both("Low", UNSIGNED);
        assertEquals(List.of(), r.errors());
        assertEquals(EXPECTED, run(r.out(), "Low"));
    }

    @Test
    void pluginOrderDoesNotMatter() throws Exception {
        var r = compile("Low", UNSIGNED, List.of("TesseraSyntaxLite", "TesseraCheck"), CHECKER + File.pathSeparator + LITE);
        assertEquals(List.of(), r.errors());
        assertEquals(EXPECTED, run(r.out(), "Low"));
    }

    @Test
    void withoutTheLiteTheCheckerRejectsTheSameProgram() throws Exception {
        var r = compile("Low", UNSIGNED, List.of("TesseraCheck"), CHECKER);
        assertFalse(r.errors().isEmpty());
        for (String e : r.errors())
            assertTrue(e.contains("signed semantics") || e.contains("arithmetic shift") || e.contains("sign-extends")
                    || e.contains("string concatenation"), e);
        // each of: / % > <= >> on int, / > >> on long, 4 widenings, /= %= >>=, the loop's >, the random loop's 6 + 3 + 1 ... are reported
        assertTrue(r.errors().size() >= 20, r.errors().size() + " " + r.errors());
    }

    @Test
    void withoutAnyPluginJavaGivesTheSignedAnswers() throws Exception {
        var r = compile("Low", UNSIGNED, List.of(), CHECKER);
        assertEquals(List.of(), r.errors());
        String got = run(r.out(), "Low");
        assertFalse(got.equals(EXPECTED));
        assertTrue(got.startsWith("0 -2 false false -1"), got); // stock Java: signed division, comparison and shift
    }

    @Test
    void mixingIsStillRejectedWithTheLite() throws Exception {
        var r = both("Mix", """
                package demo;
                import org.eninehq.tessera.core.Unsigned;
                public class Mix {
                    static void m(int i) {
                        @Unsigned int u = 5;
                        var q = u / i;
                        double d = (double) u;
                        long p = u + 1L;
                        System.out.println(u);
                    }
                }
                """);
        assertEquals(4, r.errors().size(), r.errors().toString());
        assertTrue(r.errors().get(0).startsWith("line 6:") && r.errors().get(0).contains("cannot mix signed int and @Unsigned int"));
        assertTrue(r.errors().get(1).startsWith("line 7:") && r.errors().get(1).contains("to double uses the signed value"));
        assertTrue(r.errors().get(2).startsWith("line 8:") && r.errors().get(2).contains("promoted with sign extension"));
        assertTrue(r.errors().get(3).startsWith("line 9:") && r.errors().get(3).contains("cannot convert @Unsigned int to signed int"));
    }

    static final String NAMED = """
            package demo;
            import org.eninehq.tessera.core.*;
            import org.eninehq.tessera.core.Module;
            public class Named extends Module {
                public Named() {
                    UInt a = input("a", UInt.w(8));
                    UInt b = input("b", UInt.w(8));
                    UInt sum = a.add(b);
                    UInt twice = sum.add(sum);
                    output("y", UInt.w(10)).set(twice);
                }
            }
            """;

    @SuppressWarnings("unchecked")
    static String sv(Path out) throws Exception {
        try (var loader = new URLClassLoader(new java.net.URL[] {out.toUri().toURL()}, SyntaxLiteTest.class.getClassLoader())) {
            var ctor = (java.lang.reflect.Constructor<Module>) loader.loadClass("demo.Named").getConstructor();
            Supplier<Module> s = () -> {
                try {
                    return ctor.newInstance();
                } catch (ReflectiveOperationException e) {
                    throw new RuntimeException(e);
                }
            };
            return SvEmitter.emit(Tessera.elaborate(s));
        }
    }

    @Test
    void localsBecomeNamedWires() throws Exception {
        var r = compile("Named", NAMED, List.of("TesseraSyntaxLite"), LITE + File.pathSeparator + CHECKER);
        assertEquals(List.of(), r.errors());
        String sv = sv(r.out());
        assertTrue(sv.matches("(?s).*\\bsum\\b.*") && sv.matches("(?s).*\\btwice\\b.*"), sv);
    }

    @Test
    void withoutTheLiteLocalsAreNotNamed() throws Exception {
        var r = compile("Named", NAMED, List.of(), CHECKER);
        assertEquals(List.of(), r.errors());
        String sv = sv(r.out());
        assertFalse(sv.matches("(?s).*\\bsum\\b.*") || sv.matches("(?s).*\\btwice\\b.*"), sv);
    }
}
