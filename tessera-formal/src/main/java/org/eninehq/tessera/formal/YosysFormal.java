package org.eninehq.tessera.formal;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.eninehq.tessera.emit.SvEmitter;
import org.eninehq.tessera.ir.Circuit;

/**
 * The standard open-source flow for the same properties: the SystemVerilog emitted under
 * {@code `ifdef FORMAL}, read by Yosys and checked by {@code yosys-smtbmc}. Used to
 * cross-check Tessera's own checker, and for users who prefer SymbiYosys.
 */
public final class YosysFormal {
    private YosysFormal() {}

    public static boolean available() {
        try {
            var p = new ProcessBuilder("yosys-smtbmc", "--help").redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            p.waitFor();
            return true;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    /** Result: whether a property failed within {@code depth} steps, and the solver log. */
    public record Result(boolean failed, String log) {}

    /** Bounded check from reset with yosys-smtbmc ({@code depth} steps, reset held in step 0). */
    public static Result bmc(Circuit c, Path dir, int depth, String solver) throws IOException, InterruptedException {
        dir = dir.toAbsolutePath();
        Files.createDirectories(dir);
        Path sv = dir.resolve("design.sv");
        String top = SvEmitter.write(c, sv);
        Path smt2 = dir.resolve("design.smt2");
        String script = "read_verilog -sv -formal " + sv + "; prep -top " + top
                + "; async2sync; dffunmap; write_smt2 -wires " + smt2;
        run(List.of("yosys", "-q", "-p", script), dir);
        // reset is held during the first cycle, like Formal.bmc
        Path constr = dir.resolve("reset.smtc");
        boolean hasReset = c.top().ports().stream().anyMatch(p -> p.name().equals("reset"));
        Files.writeString(constr, hasReset ? "initial\nassume (= [reset] true)\nalways 1\nassume (= [reset] false)\n" : "");
        var cmd = new java.util.ArrayList<>(List.of("yosys-smtbmc", "-s", solver, "-t", Integer.toString(depth + 1)));
        if (hasReset) cmd.addAll(List.of("--smtc", constr.toString()));
        cmd.add(smt2.toString());
        var p = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).start();
        String log = new String(p.getInputStream().readAllBytes());
        if (!p.waitFor(600, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new IOException("yosys-smtbmc timed out");
        }
        if (log.contains("Status: PASSED")) return new Result(false, log);
        if (log.contains("Status: FAILED")) return new Result(true, log);
        throw new IOException("yosys-smtbmc gave no verdict:\n" + log);
    }

    private static void run(List<String> cmd, Path dir) throws IOException, InterruptedException {
        var p = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).start();
        String log = new String(p.getInputStream().readAllBytes());
        if (!p.waitFor(600, TimeUnit.SECONDS) || p.exitValue() != 0) throw new IOException(String.join(" ", cmd) + " failed:\n" + log);
    }
}
