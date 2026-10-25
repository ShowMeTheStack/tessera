package org.eninehq.tessera.syntaxtests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.zip.CRC32;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import org.eninehq.tessera.core.Tessera;
import org.eninehq.tessera.emit.SvEmitter;
import org.eninehq.tessera.sim.Sim;
import org.eninehq.tessera.syntax.Crc32;
import org.eninehq.tessera.syntax.Gcd;
import org.eninehq.tessera.syntax.ImmDecode;
import org.junit.jupiter.api.Test;

/** Programs written with the Tessera javac syntax, and its compile-time errors. */
class SyntaxTest {

    @Test
    void unsignedSoftwareMatchesTheJdk() {
        var rnd = new Random(1);
        for (int i = 0; i < 200; i++) {
            byte[] data = new byte[rnd.nextInt(100)];
            rnd.nextBytes(data);
            var ref = new CRC32();
            ref.update(data);
            assertEquals(ref.getValue(), Crc32.value(data));
        }
        assertEquals(0xCBF43926L, Crc32.value("123456789".getBytes(StandardCharsets.US_ASCII)));
        assertEquals("value 4000000000 (high nibble 14)", Crc32.describe((int) 4_000_000_000L));
        assertEquals(3_500_000_000L, Integer.toUnsignedLong(Crc32.average((int) 4_000_000_000L, (int) 3_000_000_000L)));
    }

    @Test
    void gcdWithOperatorsAndTrailingBlocks() {
        var e = Tessera.elaborate(Gcd::new);
        assertTrue(SvEmitter.emit(e).contains(" result"), "the local 'result' became a named wire");
        try (var sim = Sim.create(e)) {
            sim.reset(1);
            int[][] cases = {{48, 18, 6}, {270, 192, 6}, {17, 5, 1}, {1000, 250, 250}, {65535, 255, 255}};
            for (int[] c : cases) {
                sim.poke("a", c[0]);
                sim.poke("b", c[1]);
                sim.poke("start", 1);
                sim.step();
                sim.poke("start", 0);
                for (int n = 0; sim.peek("done") == 0 && n < 100_000; n++) sim.step();
                assertEquals(c[2], sim.peek("result"), "gcd(" + c[0] + ", " + c[1] + ")");
                assertEquals(((c[2] & 0xFF) << 8) | (c[2] >> 8), sim.peek("swapped"));
                assertEquals(c[2] & 1, sim.peek("odd"));
            }
        }
    }

    @Test
    void immediatesFromSlicesAndConcatenation() {
        var e = Tessera.elaborate(ImmDecode::new);
        var rnd = new Random(2);
        try (var sim = Sim.create(e)) {
            for (int k = 0; k < 2000; k++) {
                int inst = rnd.nextInt();
                sim.poke("inst", Integer.toUnsignedLong(inst));
                int i = inst >> 20;
                int s = ((inst >> 25) << 5) | ((inst >>> 7) & 31);
                int b = ((inst >> 31) << 12) | (((inst >>> 7) & 1) << 11) | (((inst >>> 25) & 63) << 5) | (((inst >>> 8) & 15) << 1);
                int u = inst & 0xFFFFF000;
                int j = ((inst >> 31) << 20) | (((inst >>> 12) & 255) << 12) | (((inst >>> 20) & 1) << 11) | (((inst >>> 21) & 1023) << 1);
                assertEquals(i, (int) sim.peek("i"));
                assertEquals(s, (int) sim.peek("s"));
                assertEquals(b, (int) sim.peek("b"));
                assertEquals(u, (int) sim.peek("u"));
                assertEquals(j, (int) sim.peek("j"));
            }
        }
    }

    static List<String> errors(String source) {
        var compiler = ToolProvider.getSystemJavaCompiler();
        var diags = new DiagnosticCollector<JavaFileObject>();
        var file = new SimpleJavaFileObject(URI.create("string:///T.java"), JavaFileObject.Kind.SOURCE) {
            @Override public CharSequence getCharContent(boolean ignore) { return source; }
        };
        compiler.getTask(null, null, diags, List.of("-d", "build/syntax-errors", "-proc:none"), null, List.of(file)).call();
        var out = new ArrayList<String>();
        for (var d : diags.getDiagnostics()) if (d.getKind() == Diagnostic.Kind.ERROR) out.add(d.getLineNumber() + ": " + d.getMessage(Locale.ROOT));
        return out;
    }

    @Test
    void signedAndUnsignedNeverMixSilently() {
        var errs = errors("""
                class T {
                    void f(uint x) {}
                    void g(int i, uint u) {
                        uint a = i;
                        int b = u;
                        uint c = u + i;
                        f(i);
                        uint<8> n = u;
                        Object o = u;
                        uint ok = 5;
                        long widened = u;
                    }
                }
                """);
        assertEquals(6, errs.size(), errs.toString());
        assertTrue(errs.get(0).startsWith("4: cannot convert signed int to uint"), errs.toString());
        assertTrue(errs.get(1).startsWith("5: cannot convert uint to signed int"), errs.toString());
        assertTrue(errs.get(2).startsWith("6: cannot mix signed int and uint"), errs.toString());
        assertTrue(errs.get(5).startsWith("9: unsigned value uint cannot be boxed"), errs.toString());
    }

    @Test
    void bitVectorsHaveNoArithmetic() {
        var errs = errors("""
                class T {
                    bits<8> f(bits<8> a, bits<8> b) { return a + b; }
                    bits<8> g(bits<8> a, bits<8> b) { return a ^ b; }
                }
                """);
        assertEquals(1, errs.size(), errs.toString());
        assertTrue(errs.getFirst().contains("operator + is not defined for bit vectors"), errs.toString());
    }
}
