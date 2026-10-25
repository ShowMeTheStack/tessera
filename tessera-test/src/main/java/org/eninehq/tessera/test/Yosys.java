package org.eninehq.tessera.test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.eninehq.tessera.emit.SvEmitter;
import org.eninehq.tessera.ir.Circuit;

/** Runs Yosys on generated SystemVerilog: synthesis smoke tests and cell counts. */
public final class Yosys {
    private Yosys() {}

    public static boolean available() {
        try {
            return new ProcessBuilder("yosys", "-V").redirectErrorStream(true).start().waitFor() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    /** Result of a synthesis run: total cells and per-cell-type counts. */
    public record Stats(int cells, Map<String, Integer> byType, String log) {}

    /**
     * Synthesize with {@code script} (default: generic {@code synth}) and return the cell counts.
     * Fails on any Yosys error or on latches (which Tessera never intends to create).
     */
    public static Stats synth(Circuit c, String name, String synthCommand) throws IOException, InterruptedException {
        Path dir = Verilator.workDir(name);
        Path sv = dir.resolve(name + ".sv");
        String top = SvEmitter.write(c, sv);
        String script = "read_verilog -sv " + sv + "; " + synthCommand.replace("{top}", top) + "; check -assert; stat";
        var p = new ProcessBuilder("yosys", "-q", "-p", script.replace("; stat", "; tee -o " + dir.resolve("stat.txt") + " stat"))
                .directory(dir.toFile()).redirectErrorStream(true).start();
        String log = new String(p.getInputStream().readAllBytes());
        if (!p.waitFor(600, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new AssertionError("yosys timed out");
        }
        if (p.exitValue() != 0) throw new AssertionError("yosys failed:\n" + log);
        String stat = Files.readString(dir.resolve("stat.txt"));
        if (stat.matches("(?s).*\\$_DLATCH.*|.*\\$dlatch.*")) throw new AssertionError("design synthesized to latches:\n" + stat);
        return parse(stat, log);
    }

    public static Stats synth(Circuit c, String name) throws IOException, InterruptedException {
        return synth(c, name, "synth -top {top}");
    }

    private static final Pattern CELLS = Pattern.compile("^\\s+(\\d+)\\s+cells\\s*$", Pattern.MULTILINE);
    private static final Pattern CELL_TYPE = Pattern.compile("^\\s+(\\d+)\\s+(\\S+)\\s*$", Pattern.MULTILINE);

    /** Parse the totals: the last "N cells" line (the whole design when hierarchical) and the cell types after it. */
    static Stats parse(String stat, String log) {
        int cells = -1, at = 0;
        var m = CELLS.matcher(stat);
        while (m.find()) {
            cells = Integer.parseInt(m.group(1));
            at = m.end();
        }
        var byType = new LinkedHashMap<String, Integer>();
        var t = CELL_TYPE.matcher(stat);
        t.region(at, stat.length());
        while (t.find()) {
            if (t.group(2).equals("submodules")) break;
            byType.merge(t.group(2), Integer.parseInt(t.group(1)), Integer::sum);
        }
        return new Stats(cells, byType, log);
    }
}
