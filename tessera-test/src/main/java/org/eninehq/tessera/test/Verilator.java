package org.eninehq.tessera.test;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.eninehq.tessera.core.Elaborated;
import org.eninehq.tessera.emit.SvEmitter;
import org.eninehq.tessera.ir.Circuit;
import org.eninehq.tessera.ir.Signal;
import org.eninehq.tessera.sim.Sim;
import org.eninehq.tessera.sim.Simulator;

/** Runs Verilator on generated SystemVerilog: lint checks and cycle-by-cycle cross-checks. */
public final class Verilator {
    private Verilator() {}

    public static final List<String> LINT_FLAGS = List.of(
            "-Wall", "-Wno-DECLFILENAME", "-Wno-UNUSEDSIGNAL", "-Wno-UNUSEDPARAM");

    /** True when a {@code verilator} executable is on the PATH. */
    public static boolean available() {
        try {
            return new ProcessBuilder("verilator", "--version").redirectErrorStream(true).start().waitFor() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    public static Path workDir(String name) throws IOException {
        Path base = Path.of(System.getProperty("tessera.workdir", "build/tessera-work"));
        Path d = base.resolve(name).toAbsolutePath();
        Files.createDirectories(d);
        return d;
    }

    /** Lint the design with {@code verilator --lint-only -Wall}; throws with the log on any warning. */
    public static void lint(Circuit c, String name) throws IOException, InterruptedException {
        Path dir = workDir(name);
        Path sv = dir.resolve(name + ".sv");
        String top = SvEmitter.write(c, sv);
        var cmd = new ArrayList<>(List.of("verilator", "--lint-only", "--top-module", top));
        cmd.addAll(LINT_FLAGS);
        cmd.add(sv.toString());
        run(cmd, dir, 120);
    }

    /**
     * Simulate {@code e} for {@code stimulus.size()} cycles both in Tessera's simulator and in
     * Verilator, and fail on the first cycle where any top-level output differs.
     * Each stimulus row has one value per top-level input (in port order, reset included, clock excluded).
     */
    public static void crossCheck(Elaborated<?> e, String name, List<long[]> stimulus, Sim.Backend backend)
            throws IOException, InterruptedException {
        var big = new ArrayList<BigInteger[]>(stimulus.size());
        for (long[] row : stimulus) {
            var b = new BigInteger[row.length];
            for (int i = 0; i < row.length; i++) b[i] = new BigInteger(Long.toUnsignedString(row[i]));
            big.add(b);
        }
        crossCheckBig(e, name, big, backend);
    }

    /** Like {@link #crossCheck}, with values of any width. */
    public static void crossCheckBig(Elaborated<?> e, String name, List<BigInteger[]> stimulus, Sim.Backend backend)
            throws IOException, InterruptedException {
        Circuit c = e.circuit();
        var inputs = new ArrayList<Signal>();
        var outputs = new ArrayList<Signal>();
        for (var p : c.top().ports()) {
            if (p.kind() == Signal.Kind.INPUT && !(p.isClockOrReset() && p.name().equals("clock"))) inputs.add(p);
            if (p.kind() == Signal.Kind.OUTPUT) outputs.add(p);
        }
        // Tessera run
        var expected = new ArrayList<BigInteger[]>();
        try (Simulator sim = Sim.create(c, backend)) {
            for (BigInteger[] row : stimulus) {
                for (int i = 0; i < inputs.size(); i++) sim.pokeBig(sim.netlist().pathOf(inputs.get(i)), row[i]);
                var out = new BigInteger[outputs.size()];
                for (int i = 0; i < outputs.size(); i++) out[i] = sim.peekBig(sim.netlist().pathOf(outputs.get(i)));
                expected.add(out);
                sim.step();
            }
        }
        // Verilator run
        Path dir = workDir(name);
        Path sv = dir.resolve(name + ".sv");
        String top = SvEmitter.write(c, sv);
        Path tb = dir.resolve("tb.sv");
        Files.writeString(tb, testbench(top, c, inputs, outputs));
        var stim = new StringBuilder().append(stimulus.size()).append('\n');
        for (BigInteger[] row : stimulus) {
            for (int i = 0; i < inputs.size(); i++) {
                if (i > 0) stim.append(' ');
                stim.append(row[i].and(maskBig(inputs.get(i).width())).toString(16));
            }
            stim.append('\n');
        }
        Files.writeString(dir.resolve("stim.txt"), stim);
        var cmd = new ArrayList<>(List.of("verilator", "--binary", "--timing", "-j", "0", "-Wno-fatal",
                "--top-module", "tb", "-o", "vsim", "--Mdir", "obj_dir", tb.toString(), sv.toString()));
        run(cmd, dir, 600);
        run(List.of(dir.resolve("obj_dir/vsim").toString()), dir, 600);
        var lines = Files.readAllLines(dir.resolve("out.txt"));
        if (lines.size() != stimulus.size())
            throw new AssertionError("Verilator produced " + lines.size() + " cycles, expected " + stimulus.size());
        for (int cyc = 0; cyc < lines.size(); cyc++) {
            String[] f = lines.get(cyc).trim().split("\\s+");
            for (int i = 0; i < outputs.size(); i++) {
                BigInteger got = new BigInteger(f[i], 16);
                if (!got.equals(expected.get(cyc)[i]))
                    throw new AssertionError("cycle " + cyc + ": output '" + outputs.get(i).name() + "' is 0x"
                            + got.toString(16) + " in Verilator but 0x" + expected.get(cyc)[i].toString(16)
                            + " in Tessera (inputs " + Arrays.toString(stimulus.get(cyc)) + ")");
            }
        }
    }

    private static BigInteger maskBig(int w) {
        return BigInteger.ONE.shiftLeft(w).subtract(BigInteger.ONE);
    }

    private static String decl(Signal s) {
        return "  logic " + (s.width() == 1 ? "" : "[" + (s.width() - 1) + ":0] ") + SvEmitter.safe(s.name()) + ";\n";
    }

    private static String testbench(String top, Circuit c, List<Signal> inputs, List<Signal> outputs) {
        var sb = new StringBuilder("`timescale 1ns/1ns\nmodule tb;\n  logic clock = 1'b0;\n");
        for (var s : inputs) sb.append(decl(s));
        for (var s : outputs) sb.append(decl(s));
        sb.append("  integer tb_fd_, tb_fo_, tb_r_, tb_n_, tb_i_;\n");
        sb.append("  ").append(top).append(" dut (\n");
        var conns = new ArrayList<String>();
        for (var p : c.top().ports()) conns.add("    ." + SvEmitter.safe(p.name()) + "(" + SvEmitter.safe(p.name()) + ")");
        sb.append(String.join(",\n", conns)).append("\n  );\n");
        sb.append("  initial begin\n");
        sb.append("    tb_fd_ = $fopen(\"stim.txt\", \"r\");\n    tb_fo_ = $fopen(\"out.txt\", \"w\");\n");
        sb.append("    tb_r_ = $fscanf(tb_fd_, \"%d\\n\", tb_n_);\n");
        sb.append("    for (tb_i_ = 0; tb_i_ < tb_n_; tb_i_ = tb_i_ + 1) begin\n");
        if (!inputs.isEmpty()) {
            sb.append("      tb_r_ = $fscanf(tb_fd_, \"").append(String.join(" ", inputs.stream().map(x -> "%h").toList()))
                    .append("\\n\", ").append(String.join(", ", inputs.stream().map(x -> SvEmitter.safe(x.name())).toList())).append(");\n");
        }
        sb.append("      #1;\n");
        if (!outputs.isEmpty()) {
            sb.append("      $fwrite(tb_fo_, \"").append(String.join(" ", outputs.stream().map(x -> "%h").toList()))
                    .append("\\n\", ").append(String.join(", ", outputs.stream().map(x -> SvEmitter.safe(x.name())).toList())).append(");\n");
        } else {
            sb.append("      $fwrite(tb_fo_, \"\\n\");\n");
        }
        sb.append("      clock = 1'b1; #1; clock = 1'b0; #1;\n    end\n");
        sb.append("    $fclose(tb_fo_);\n    $finish;\n  end\nendmodule\n");
        return sb.toString();
    }

    private static String run(List<String> cmd, Path dir, int timeoutSec) throws IOException, InterruptedException {
        var pb = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true);
        Process p = pb.start();
        byte[] out = p.getInputStream().readAllBytes();
        if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new AssertionError("timeout running " + String.join(" ", cmd));
        }
        String log = new String(out);
        if (p.exitValue() != 0) throw new AssertionError("command failed: " + String.join(" ", cmd) + "\n" + log);
        if (cmd.contains("--lint-only") && log.contains("%Warning"))
            throw new AssertionError("Verilator lint warnings:\n" + log);
        return log;
    }
}
