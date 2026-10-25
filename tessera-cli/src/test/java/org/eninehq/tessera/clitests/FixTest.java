package org.eninehq.tessera.clitests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** tessera fix: compile, elaborate, apply the suggested fix, repeat until the design is clean. */
class FixTest {

    @Test
    void loopFixesWidthErrorsInSource() throws Exception {
        Path src = Files.createTempDirectory("fixsrc");
        Path pkg = Files.createDirectories(src.resolve("demo"));
        Files.writeString(pkg.resolve("Sum.java"), """
                package demo;
                import org.eninehq.tessera.core.Module;
                import org.eninehq.tessera.core.UInt;
                public class Sum extends Module {
                    public Sum() {
                        UInt a = input("a", UInt.w(8)), b = input("b", UInt.w(4));
                        UInt y = output("y", UInt.w(8));
                        y.set(a.add(b));
                        output("z", UInt.w(8)).set(a.xor(b));
                    }
                }
                """);
        var json = CliTest.run("fix", "--top", "demo.Sum", "--src", src.toString(), "--json", "--loop");
        assertEquals(0, json.status(), json.out() + json.err());
        String fixed = Files.readString(pkg.resolve("Sum.java"));
        assertTrue(fixed.contains("y.set(a.addWrap(b));"), fixed);
        assertTrue(fixed.contains("a.xor(b.zext(8))"), fixed);
        assertTrue(json.out().contains("\"fixes\": [{\"description\": \"use a wrapping add\""), json.out());
        assertTrue(json.out().contains("clean after 2 round(s)"), json.out());
    }
}
