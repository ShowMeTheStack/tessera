package org.eninehq.tessera.clitests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.eninehq.tessera.cli.TesseraCli;
import org.eninehq.tessera.test.Verilator;
import org.eninehq.tessera.test.Yosys;
import org.junit.jupiter.api.Test;

class CliTest {
    record Run(int status, String out, String err) {}

    static Run run(String... args) {
        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();
        int s = TesseraCli.run(args, new PrintStream(out, true), new PrintStream(err, true));
        return new Run(s, out.toString(), err.toString());
    }

    @Test
    void emitWritesSvAndTypes() throws Exception {
        var r = run("emit", "--top", "org.eninehq.tessera.examples.Designs::counter", "--out", "build/cli");
        assertEquals(0, r.status(), r.err());
        assertTrue(Files.readString(Path.of("build/cli/Counter.sv")).contains("module Counter"));
        assertTrue(Files.exists(Path.of("build/cli/Counter.sv.types.json")));
    }

    @Test
    void lintAndSynth() {
        assumeTrue(Verilator.available() && Yosys.available());
        assertEquals(0, run("lint", "--top", "org.eninehq.tessera.examples.Designs::alu").status());
        var s = run("synth", "--top", "org.eninehq.tessera.examples.Designs::blinky", "--target", "xc7");
        assertEquals(0, s.status(), s.err());
        assertTrue(s.out().contains("LUT") || s.out().contains("CARRY"), s.out());
    }

    @Test
    void simRunsAndReports() {
        var r = run("sim", "--top", "org.eninehq.tessera.examples.Designs::counter", "--cycles", "50");
        assertEquals(0, r.status(), r.err());
        assertTrue(r.out().contains("simulated 50 cycles"), r.out());
    }

    @Test
    void elaborationErrorsAreReportedWithSourceLines() {
        var r = run("emit", "--top", "org.eninehq.tessera.clitests.Broken");
        assertEquals(2, r.status());
        assertTrue(r.err().contains("Broken.java:"), r.err());
        assertTrue(r.err().contains("9 bits wide"), r.err());
    }

    @Test
    void usage() {
        assertEquals(2, run().status());
        assertTrue(run("help").out().contains("usage: tessera"));
    }
}
