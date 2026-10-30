package org.eninehq.tessera.riscv;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import org.eninehq.tessera.core.Module;
import org.eninehq.tessera.core.Tessera;
import org.eninehq.tessera.formal.Btor2Emitter;
import org.eninehq.tessera.formal.Formal;
import org.eninehq.tessera.sim.Netlist;

/**
 * SHA-256 of the SMT-LIB text the formal back end sends for every core (and the formal test designs of this
 * module), and of the BTOR2 model: {@code SmtHashes <out file> <solver> <tee file>}. Run with a fake solver
 * that answers {@code unsat} to every query, so that BMC (depth 3) and k-induction (k = 2)
 * each send their whole problem without solving anything. Comparing the files of two versions shows
 * whether a change to the back end changed the problems it poses (single-clock designs must not change).
 * Needs {@code -Dtessera.formal.tee=<tee file>}.
 */
public final class SmtHashes {
    private SmtHashes() {}

    public static void main(String[] args) throws Exception {
        Path outFile = Path.of(args[0]);
        String solver = args[1];
        Path tee = Path.of(args[2]);
        if (!tee.toAbsolutePath().toString().equals(Path.of(System.getProperty("tessera.formal.tee", "")).toAbsolutePath().toString()))
            throw new IllegalArgumentException("run with -Dtessera.formal.tee=" + tee);
        Map<String, Supplier<? extends Module>> designs = new LinkedHashMap<>();
        for (var spec : Cores.all()) designs.put("core/" + spec.id(), () -> spec.build(16, null));
        designs.put("proof/divider_step", () -> new DividerProofs.Step(false));
        designs.put("proof/divider_step_broken", () -> new DividerProofs.Step(true));
        designs.put("proof/divider_control", () -> new DividerProofs.Control(false, false));
        designs.put("proof/divider_control_canonical", () -> new DividerProofs.Control(true, false));
        designs.put("proof/divider_fixup", () -> new DividerProofs.Fixup(false));
        designs.put("proof/csr_idle_single", () -> new CsrIdleProof.Idle(false, false));
        designs.put("proof/csr_idle_dual", () -> new CsrIdleProof.Idle(true, false));
        var opt = Formal.Options.defaults().withSolver(solver);
        var md = MessageDigest.getInstance("SHA-256");
        var out = new StringBuilder();
        for (var d : designs.entrySet()) {
            var c = Tessera.elaborate(d.getValue()).circuit();
            for (String mode : new String[]{"bmc3", "prove2", "btor2"}) {
                Files.deleteIfExists(tee);
                Files.createFile(tee);
                String text;
                switch (mode) {
                    case "bmc3" -> Formal.bmc(c, 3, opt);
                    case "prove2" -> Formal.prove(c, 2, opt);
                    default -> { }
                }
                text = mode.equals("btor2") ? Btor2Emitter.emit(Netlist.build(c)).text() : Files.readString(tee);
                String hash = HexFormat.of().formatHex(md.digest(text.getBytes(StandardCharsets.UTF_8)));
                out.append(hash).append("  ").append(d.getKey()).append(' ').append(mode).append(' ').append(text.length()).append('\n');
                System.out.println(hash.substring(0, 12) + "  " + d.getKey() + " " + mode + " " + text.length() + " chars");
            }
        }
        Files.createDirectories(outFile.toAbsolutePath().getParent());
        Files.writeString(outFile, out);
    }
}
