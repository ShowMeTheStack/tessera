package org.eninehq.tessera.checkertests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;

/** Compiles small designs with -Xplugin:TesseraCheck and checks the reported errors. */
class CheckerTest {

    static List<String> compile(String className, String source) {
        var compiler = ToolProvider.getSystemJavaCompiler();
        var diags = new DiagnosticCollector<JavaFileObject>();
        var file = new SimpleJavaFileObject(URI.create("string:///demo/" + className + ".java"), JavaFileObject.Kind.SOURCE) {
            @Override public CharSequence getCharContent(boolean ignore) { return source; }
        };
        String cp = System.getProperty("java.class.path") + File.pathSeparator + System.getProperty("tessera.checker.classes");
        var out = new File("build/checker-out");
        out.mkdirs();
        var opts = List.of("-Xplugin:TesseraCheck", "-classpath", cp, "-processorpath", cp, "-d", out.getPath(), "-proc:none");
        compiler.getTask(null, null, diags, opts, null, List.of(file)).call();
        var errs = new ArrayList<String>();
        for (var d : diags.getDiagnostics())
            if (d.getKind() == Diagnostic.Kind.ERROR) errs.add("line " + d.getLineNumber() + ": " + d.getMessage(Locale.ROOT));
        return errs;
    }

    static final String HEADER = """
            package demo;
            import org.eninehq.tessera.core.*;
            import org.eninehq.tessera.core.Module;
            import org.eninehq.tessera.pipeline.*;
            """;

    @Test
    void cleanDesignHasNoErrors() {
        var errs = compile("Ok", HEADER + """
                public class Ok extends Module {
                    public Ok() {
                        UInt a = input("a", UInt.w(8));
                        UInt b = input("b", UInt.w(8));
                        UInt y = output("y", UInt.w(9));
                        y.set(a.add(b));
                        output("z", UInt.w(8)).set(a.addWrap(b).xor(b));
                        UInt w1 = input("w1", UInt.w(130)), w2 = input("w2", UInt.w(130));
                        output("w", UInt.w(130)).set(w1.xor(w2));
                    }
                }
                """);
        assertEquals(List.of(), errs);
    }

    @Test
    void narrowingSetIsACompileError() {
        var errs = compile("Narrow", HEADER + """
                public class Narrow extends Module {
                    public Narrow() {
                        UInt a = input("a", UInt.w(8));
                        UInt y = output("y", UInt.w(8));
                        y.set(a.add(a));
                    }
                }
                """);
        assertEquals(1, errs.size(), errs.toString());
        assertTrue(errs.getFirst().startsWith("line 9:") && errs.getFirst().contains("9 bits wide but 'y' is 8 bits"), errs.toString());
    }

    @Test
    void widthAnnotationsAndBitwiseMismatch() {
        var errs = compile("Ann", HEADER + """
                public class Ann extends Module {
                    UInt f(@Width(8) UInt a, @Width(4) UInt b) {
                        return a.and(b);
                    }
                    public Ann() {
                        @Width(12) UInt x = input("x", UInt.w(16));
                        UInt s = input("s", UInt.w(16));
                        UInt t = s.bits(17, 3);
                    }
                }
                """);
        assertEquals(3, errs.size(), errs.toString());
        assertTrue(errs.get(0).contains("and: operand widths differ (8 vs 4)"), errs.toString());
        assertTrue(errs.get(1).contains("16 bits wide but 'x' is declared @Width(12)"), errs.toString());
        assertTrue(errs.get(2).contains("bits(17, 3) out of range for width 16"), errs.toString());
    }

    @Test
    void laterStageValuesCannotBeUsedEarlier() {
        var errs = compile("Stages", HEADER + """
                public class Stages extends Module {
                    public Stages(@Stage(2) UInt late) {
                        UInt a = input("a", UInt.w(8));
                        var p = Pipeline.begin("p");
                        UInt early = p.input(a).addWrap(late);
                        p.stage();
                        p.stage();
                        UInt ok = early.addWrap(late);
                        p.end();
                    }
                }
                """);
        assertEquals(1, errs.size(), errs.toString());
        assertTrue(errs.getFirst().startsWith("line 9:") && errs.getFirst().contains("stage 2 but is used in stage 0"), errs.toString());
    }

    @Test
    void fnMethodsMustBeCombinational() {
        var errs = compile("Fns", HEADER + """
                public class Fns extends Module {
                    @Fn UInt good(UInt a, UInt b) { return a.addWrap(b); }
                    @Fn UInt bad(UInt a) { return regNext(a); }
                    public Fns() {}
                }
                """);
        assertEquals(1, errs.size(), errs.toString());
        assertTrue(errs.getFirst().contains("@Fn method 'bad' must be combinational but calls regNext"), errs.toString());
    }
}
