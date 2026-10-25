package org.eninehq.tessera.studio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * FST read back by GTKWave's own reader (fstapi.c, built as fstdump from the copy Verilator ships; see
 * README.md). Skipped where fstdump is not built.
 */
class FstWriterTest {
    static final Path DUMP = Path.of(System.getProperty("user.home"), "tessera-tools/fstdump/fstdump");

    @Test
    void gtkwavesReaderReadsBackEveryChange() throws Exception {
        assumeTrue(Files.isExecutable(DUMP), "fstdump is not built");
        var rnd = new Random(7);
        var vars = new ArrayList<FstWriter.Var>();
        var expect = new ArrayList<String>();
        int[] widths = {1, 8, 13, 64, 1, 100, 3};
        for (int h = 0; h < widths.length; h++) {
            int w = widths[h];
            var times = new ArrayList<long[]>();
            var vals = new ArrayList<BigInteger>();
            if (h != 4) {   // one signal that never changes
                long t = h == 2 ? 5 : 0;
                for (int k = 0; k < 40; k++) {
                    times.add(new long[]{t});
                    vals.add(new BigInteger(w, rnd));
                    t += 1 + rnd.nextInt(h == 6 ? 300 : 9);
                }
            }
            vars.add(new FstWriter.Var("s" + h + (w > 1 ? "_" + w : ""), w, times, vals));
        }
        // what the reader should say: time, handle, bits, in time order then handle order
        var rows = new ArrayList<long[]>();
        for (int h = 0; h < vars.size(); h++) for (int k = 0; k < vars.get(h).times().size(); k++) rows.add(new long[]{vars.get(h).times().get(k)[0], h, k});
        rows.sort((a, b) -> a[0] != b[0] ? Long.compare(a[0], b[0]) : Long.compare(a[1], b[1]));
        for (var r : rows) {
            var v = vars.get((int) r[1]);
            String bits = v.values().get((int) r[2]).toString(2);
            expect.add(r[0] + " " + (r[1] + 1) + " " + "0".repeat(v.width() - bits.length()) + bits);
        }
        Path f = Files.createTempFile("tessera", ".fst");
        Files.write(f, FstWriter.write("Top", vars, -9));
        var p = new ProcessBuilder(DUMP.toString(), f.toString()).redirectErrorStream(true).start();
        var lines = new String(p.getInputStream().readAllBytes()).lines().toList();
        assertEquals(0, p.waitFor(), String.join("\n", lines));
        assertEquals("timescale -9 start 0 end " + rows.getLast()[0] + " vars 7 scopes 1", lines.getFirst());
        assertEquals("scope Top", lines.get(1));
        for (int h = 0; h < vars.size(); h++) assertEquals("var " + (h + 1) + " " + vars.get(h).name() + " " + widths[h], lines.get(2 + h));
        var got = new ArrayList<>(lines.subList(2 + vars.size(), lines.size()));
        // a signal with no changes shows as x at the start; within one time the reader's order is its own
        got.removeIf(l -> l.matches("\\d+ 5 x+"));
        var times = got.stream().map(l -> Long.parseLong(l.split(" ")[0])).toList();
        for (int i = 1; i < times.size(); i++) assertTrue(times.get(i - 1) <= times.get(i), "in time order: " + got);
        expect.sort(null);
        got.sort(null);
        assertEquals(String.join("\n", expect), String.join("\n", got));
    }
}
