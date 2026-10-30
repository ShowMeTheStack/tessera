package org.eninehq.tessera.formal;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.eninehq.tessera.emit.SvEmitter;
import org.eninehq.tessera.ir.Circuit;
import org.eninehq.tessera.sim.Netlist;

/**
 * The standard open-source flow for the same properties: the SystemVerilog emitted under
 * {@code `ifdef FORMAL}, read by Yosys and checked by {@code yosys-smtbmc}. Used to
 * cross-check Tessera's own checker, and for users who prefer SymbiYosys.
 *
 * <p>A design with one clock and synchronous resets is checked as before ({@code async2sync},
 * one smtbmc step per clock cycle). A design with several clocks or an asynchronous reset is
 * checked with {@code clk2fflogic} instead (no {@code async2sync}): every clock is a free input and
 * a flip-flop updates when its clock rises between two smtbmc steps, and an asynchronous reset acts
 * in the step it is asserted. Such a check of depth {@code d} runs {@code 2d + 2} smtbmc steps, so
 * every clock can rise as often as Tessera's ticks allow ({@code d + 1} times, one of them in reset).
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

    /** True when {@link #bmc} uses {@code clk2fflogic}: several clocks or an asynchronous reset. */
    public static boolean needsClk2ff(Circuit c) {
        var net = Netlist.build(c);
        return net.clockPaths().size() > 1 || net.regs().stream().anyMatch(r -> r.hasReset() && r.asyncReset());
    }

    /** Bounded check from reset with yosys-smtbmc ({@code depth} steps, reset held in step 0). */
    public static Result bmc(Circuit c, Path dir, int depth, String solver) throws IOException, InterruptedException {
        return bmc(c, dir, depth, solver, new Formal.Options(null, 1, false));
    }

    /**
     * As {@link #bmc(Circuit, Path, int, String)}, with the reset cycles and {@code freeAsyncResets} of
     * {@code opt} (both only used with {@code clk2fflogic}; the single-clock check keeps one reset cycle).
     */
    public static Result bmc(Circuit c, Path dir, int depth, String solver, Formal.Options opt) throws IOException, InterruptedException {
        if (needsClk2ff(c)) return bmcClk2ff(c, dir, depth, solver, opt.freeAsyncResets(), Math.max(1, opt.resetCycles()));
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

    /**
     * Several clocks or asynchronous resets: clk2fflogic; for each of the {@code resetCycles} reset cycles all
     * clocks are low in one step and high in the next with the resets active (the reset edges), then the clocks
     * are free; the resets are inactive from then on, or free if they drive an asynchronous reset and
     * {@code freeAsyncResets}.
     */
    private static Result bmcClk2ff(Circuit c, Path dir, int depth, String solver, boolean freeAsyncResets, int resetCycles)
            throws IOException, InterruptedException {
        dir = dir.toAbsolutePath();
        Files.createDirectories(dir);
        var net = Netlist.build(c);
        Path sv = dir.resolve("design.sv");
        String top = SvEmitter.write(c, sv);
        Path smt2 = dir.resolve("design.smt2");
        String script = "read_verilog -sv -formal " + sv + "; prep -top " + top + "; clk2fflogic; write_smt2 -wires " + smt2;
        run(List.of("yosys", "-q", "-p", script), dir);
        var resets = new java.util.LinkedHashMap<String, Long>();
        var async = new java.util.HashSet<String>();
        for (var r : net.regs()) {
            if (!r.hasReset()) continue;
            for (var e : net.inputSlots().entrySet())
                if (e.getValue() == r.resetSlot()) {
                    resets.put(e.getKey(), r.resetActive());
                    if (r.asyncReset()) async.add(e.getKey());
                }
        }
        var smtc = new StringBuilder();
        for (int k = 0; k < 2 * resetCycles; k++) {
            smtc.append("state ").append(k).append('\n');
            for (var clk : net.clockPaths()) smtc.append(k % 2 == 0 ? "assume (not [" + clk + "])\n" : "assume [" + clk + "]\n");
            for (var e : resets.entrySet()) smtc.append("assume (= [").append(e.getKey()).append("] ").append(e.getValue() == 1).append(")\n");
        }
        smtc.append("state ").append(2 * resetCycles).append(":*\n");
        for (var e : resets.entrySet())
            if (!(freeAsyncResets && async.contains(e.getKey())))
                smtc.append("assume (= [").append(e.getKey()).append("] ").append(e.getValue() != 1).append(")\n");
        Path constr = dir.resolve("clocks.smtc");
        Files.writeString(constr, smtc);
        var cmd = new java.util.ArrayList<>(List.of("yosys-smtbmc", "-s", solver, "-t", Integer.toString(2 * depth + 2),
                "--smtc", constr.toString(), smt2.toString()));
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
