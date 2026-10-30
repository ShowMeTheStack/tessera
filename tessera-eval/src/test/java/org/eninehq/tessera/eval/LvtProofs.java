package org.eninehq.tessera.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.eninehq.tessera.core.Tessera;
import org.eninehq.tessera.formal.Formal;
import org.eninehq.tessera.formal.YosysFormal;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

/**
 * The live-value-table register file equals the two-write register array ({@link LibraryProofs.LvtRaw}),
 * by Tessera's k-induction, with BMC from arbitrary memory contents, and two negative controls: the wrong
 * same-cycle order must fail BMC; without the strengthening assertions induction must not close.
 * Results to {@code -Plvtproofs=<csv>} (seconds are this machine's).
 */
class LvtProofs {
    static final List<String> ROWS = new ArrayList<>();

    /** -Plvtproofs.solver (default yices-smt2) and -Plvtproofs.size (registers = bits, default 32: the cores' file). */
    static final String SOLVER = System.getProperty("lvtproofs.solver", "yices-smt2");
    static final int N = Integer.getInteger("lvtproofs.size", 32), W = Integer.getInteger("lvtproofs.width", N);
    /** The solver for k-induction: bitwuzla by default (yices did not close the 32-bit step in 45 min). */
    static final String IND = System.getProperty("lvtproofs.indsolver", "bitwuzla");

    static Formal.Options opt() {
        return Formal.Options.defaults().withSolver(SOLVER);
    }

    static void row(String name, String check, Formal.Result r, double s) {
        synchronized (ROWS) {
            ROWS.add(String.format("\"%s\",%s,%s,%.2f", name, check, r.status(), s));
        }
        System.out.printf("LVTPROOF %s %s: %s (%.2f s)%s%n", name, check, r.status(), s, r.failed() ? " " + r : "");
    }

    static Formal.Result run(String name, String check, java.util.function.Supplier<Formal.Result> f) {
        long t0 = System.nanoTime();
        var r = f.get();
        row(name, check, r, (System.nanoTime() - t0) / 1e9);
        return r;
    }

    @Test
    void lvtEqualsTheArray() {
        assumeTrue(YosysFormal.available(), "needs yices");
        for (boolean zero : new boolean[]{false, true}) {
            String n = "lvt " + N + "x" + W + " 5r 2w" + (zero ? " x0" : "") + " " + SOLVER;
            var e = Tessera.elaborate(() -> new LibraryProofs.LvtRaw(N, W, 5, zero, true, false));
            // -Plvtproofs.indsolver: another solver for the induction (base k=2 and step); -Plvtproofs.skipbmc: skip BMC 12
            if (!Boolean.getBoolean("lvtproofs.skipbmc")) {
                var bmc = run(n, "bmc12-arbitrary-memory", () -> Formal.bmc(e.circuit(), 12, opt().withArbitraryMemory(true)));
                assertEquals(Formal.Status.PASS, bmc.status(), bmc.toString());
            }
            String is = IND;
            var ind = run(n, "k-induction-2 (" + is + ")", () -> Formal.prove(e.circuit(), 2, opt().withSolver(is)));
            assertEquals(Formal.Status.PROVEN, ind.status(), ind.toString());
        }
    }

    @Test
    void wrongSameCycleOrderFails() {
        assumeTrue(YosysFormal.available(), "needs yices");
        var e = Tessera.elaborate(() -> new LibraryProofs.LvtRaw(N, W, 5, false, true, true));
        var bmc = run("control: port 0 wins " + N + " " + SOLVER, "bmc12", () -> Formal.bmc(e.circuit(), 12, opt()));
        assertEquals(Formal.Status.FAIL, bmc.status(), bmc.toString());
    }

    @Test
    void inductionNeedsTheStrengthening() {
        assumeTrue(YosysFormal.available(), "needs yices");
        var e = Tessera.elaborate(() -> new LibraryProofs.LvtRaw(N, W, 5, false, false, false));
        var bmc = run("control: unaided " + N + " " + SOLVER, "bmc12", () -> Formal.bmc(e.circuit(), 12, opt()));
        assertEquals(Formal.Status.PASS, bmc.status(), bmc.toString());
        for (int k : new int[]{2, 6}) {
            var ind = run("control: unaided " + N + "x" + W, "k-induction-" + k + " (" + IND + ")", () -> Formal.prove(e.circuit(), k, opt().withSolver(IND)));
            assertNotEquals(Formal.Status.PROVEN, ind.status(), ind.toString());
        }
    }

    @AfterAll
    static void write() throws Exception {
        String to = System.getProperty("lvtproofs", "");
        if (to.isBlank() || ROWS.isEmpty()) return;
        var lines = new ArrayList<String>(List.of("design,check,status,seconds"));
        lines.addAll(ROWS);
        Path out = Path.of(to);
        Files.createDirectories(out.toAbsolutePath().getParent());
        Files.write(out, lines);
    }
}
