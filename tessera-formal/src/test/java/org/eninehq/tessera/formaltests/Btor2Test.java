package org.eninehq.tessera.formaltests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.eninehq.tessera.core.Tessera;
import org.eninehq.tessera.formal.Btor2Emitter;
import org.eninehq.tessera.formal.Formal;
import org.eninehq.tessera.sim.Netlist;
import org.junit.jupiter.api.Test;

/** BTOR2 output, checked with btorsim (from btor2tools): a Tessera counterexample is a valid BTOR2 witness. */
class Btor2Test {

    static String btorsim() {
        for (var p : List.of(System.getProperty("user.home") + "/tessera-tools/btor2tools/build/bin/btorsim", "btorsim")) {
            try {
                var pr = new ProcessBuilder(p, "-h").redirectErrorStream(true).start();
                pr.getInputStream().readAllBytes();
                pr.waitFor();
                return p;
            } catch (Exception e) {
                // next
            }
        }
        return null;
    }

    static String run(Path dir, String... cmd) throws Exception {
        var p = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        p.waitFor(60, TimeUnit.SECONDS);
        return "exit=" + p.exitValue() + "\n" + out;
    }

    @Test
    void counterexampleIsAValidWitness() throws Exception {
        String sim = btorsim();
        assumeTrue(sim != null && Formal.Options.defaults().solver() != null);
        var e = Tessera.elaborate(() -> new FormalTest.Counter(true));
        var model = Btor2Emitter.emit(Netlist.build(e.circuit()));
        var r = Formal.bmc(e, 12);
        assertEquals(Formal.Status.FAIL, r.status());
        Path dir = Path.of("build", "formal", "btor").toAbsolutePath();
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("counter.btor"), model.text());
        Files.writeString(dir.resolve("counter.wit"), Btor2Emitter.witness(model, r, 0));
        String out = run(dir, sim, "-c", "counter.btor", "counter.wit");
        assertTrue(out.startsWith("exit=0"), out + "\n" + model.text() + "\n" + Btor2Emitter.witness(model, r, 0));
    }

    @Test
    void correctDesignNeverReachesBadInRandomSimulation() throws Exception {
        String sim = btorsim();
        assumeTrue(sim != null);
        var e = Tessera.elaborate(() -> new FormalTest.Counter(false));
        var model = Btor2Emitter.emit(Netlist.build(e.circuit()));
        Path dir = Path.of("build", "formal", "btor").toAbsolutePath();
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("good.btor"), model.text());
        String out = run(dir, sim, "-r", "200", "-s", "7", "good.btor");
        assertTrue(out.startsWith("exit=0"), out);
        assertTrue(!out.contains("\nsat"), out);
    }
}
