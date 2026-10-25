package org.eninehq.tessera.studio;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.eninehq.tessera.core.Elaborated;
import org.eninehq.tessera.core.Module;
import org.eninehq.tessera.emit.SvEmitter;
import org.eninehq.tessera.ir.Expr;
import org.eninehq.tessera.ir.ModuleDef;
import org.eninehq.tessera.ir.Signal;

/**
 * The Analyse panel's numbers. Tessera's simulator has no gate delays, so instead of timing it shows
 * the <b>levels of logic</b> on the longest combinational paths of each module (from registers and the
 * module's inputs, to registers and its outputs; a child instance's ports end a path, and wiring such as
 * slices, concatenations and extensions is not a level), and, when Yosys is installed, the resources
 * {@code synth_xilinx} estimates.
 */
final class Analyse {
    private Analyse() {}

    /** A path's depth and the named signals it passes, from start to end. */
    private record Depth(int levels, List<String> path) {
        static final Depth ZERO = new Depth(0, List.of());
    }

    static Map<String, Object> levels(Elaborated<Module> e) {
        var mods = new ArrayList<Object>();
        var seen = new java.util.HashSet<ModuleDef>();
        visit(e.circuit().top(), "", mods, seen);
        var m = new LinkedHashMap<String, Object>();
        m.put("modules", mods);
        return m;
    }

    private static void visit(ModuleDef def, String prefix, List<Object> out, java.util.Set<ModuleDef> seen) {
        if (seen.add(def)) {
            var memo = new IdentityHashMap<Signal, Depth>();
            var ends = new ArrayList<Map<String, Object>>();
            for (Signal s : allSignals(def)) {
                boolean end = s.kind() == Signal.Kind.REG || s.kind() == Signal.Kind.OUTPUT;
                if (!end || s.driver() == null || s.isClockOrReset()) continue;
                Depth d = expr(s.driver(), memo, new java.util.HashSet<>());
                var path = new ArrayList<>(d.path());
                path.add(s.name());
                var m = new LinkedHashMap<String, Object>();
                m.put("end", s.name());
                m.put("kind", s.kind() == Signal.Kind.REG ? "register" : "output");
                m.put("levels", d.levels());
                m.put("path", path);
                ends.add(m);
            }
            ends.sort((a, b) -> Integer.compare((int) b.get("levels"), (int) a.get("levels")));
            var m = new LinkedHashMap<String, Object>();
            m.put("module", def.name());
            m.put("instance", prefix.isEmpty() ? "(top)" : prefix);
            m.put("prefix", prefix);
            m.put("levels", ends.isEmpty() ? 0 : ends.getFirst().get("levels"));
            m.put("paths", ends.subList(0, Math.min(8, ends.size())));
            out.add(m);
        }
        for (var inst : def.instances()) visit(inst.def(), prefix + inst.name() + ".", out, seen);
    }

    private static List<Signal> allSignals(ModuleDef def) {
        var l = new ArrayList<Signal>(def.ports());
        l.addAll(def.signals());
        return l;
    }

    /** The deepest path into {@code e}: registers, inputs, literals and child ports are where paths start. */
    private static Depth expr(Expr e, Map<Signal, Depth> memo, java.util.Set<Signal> active) {
        return switch (e) {
            case Expr.Lit l -> Depth.ZERO;
            case Expr.InstRef r -> new Depth(0, List.of(r.instance().name() + "." + r.port().name()));
            case Expr.Ref r -> signal(r.signal(), memo, active);
            case Expr.MemRead m -> { Depth a = expr(m.addr(), memo, active); yield new Depth(a.levels() + 1, a.path()); }
            case Expr.Op o -> {
                Depth best = Depth.ZERO;
                for (Expr a : o.args()) { Depth d = expr(a, memo, active); if (d.levels() > best.levels() || best == Depth.ZERO && !d.path().isEmpty()) best = d; }
                boolean wiring = switch (o.kind()) {
                    case CAT, SLICE, EXT, CAST, SHL, SHR, SRA -> true;   // wires only (constant shifts too)
                    default -> false;
                };
                yield wiring ? best : new Depth(best.levels() + 1, best.path());
            }
        };
    }

    private static Depth signal(Signal s, Map<Signal, Depth> memo, java.util.Set<Signal> active) {
        if (s.kind() == Signal.Kind.REG || s.kind() == Signal.Kind.INPUT || s.driver() == null) return new Depth(0, List.of(s.name()));
        var hit = memo.get(s);
        if (hit != null) return hit;
        if (!active.add(s)) return Depth.ZERO;          // a loop (Tessera reports it elsewhere)
        Depth d = expr(s.driver(), memo, active);
        active.remove(s);
        var path = new ArrayList<>(d.path());
        if (!s.autoNamed()) path.add(s.name());          // named values are the waypoints shown
        var r = new Depth(d.levels(), path);
        memo.put(s, r);
        return r;
    }

    // ---------------------------------------------------------------- Yosys

    private static final Pattern CELL = Pattern.compile("^\\s+(\\d+)\\s+(\\$?[A-Za-z0-9_$]+)\\s*$");

    /** Resource estimates from {@code yosys synth_xilinx}, or a sentence saying why there are none. */
    static Map<String, Object> resources(Elaborated<Module> e) {
        var m = new LinkedHashMap<String, Object>();
        String yosys = which("yosys");
        if (yosys == null) { m.put("error", "Yosys is not installed (not on the PATH), so there are no resource estimates."); return m; }
        try {
            Path dir = Files.createTempDirectory("tessera-analyse");
            Path sv = dir.resolve("design.sv");
            Files.writeString(sv, SvEmitter.emit(e));
            String top = e.circuit().top().name();
            var pb = new ProcessBuilder(yosys, "-q", "-p", "read_verilog -sv " + sv + "; synth_xilinx -flatten -top " + top + "; tee -o " + dir.resolve("stat.txt") + " stat")
                    .redirectErrorStream(true).directory(dir.toFile());
            long t0 = System.nanoTime();
            Process p = pb.start();
            String log = new String(p.getInputStream().readAllBytes());
            if (!p.waitFor(120, TimeUnit.SECONDS)) { p.destroyForcibly(); m.put("error", "Yosys took more than two minutes; stopped."); return m; }
            if (p.exitValue() != 0 || !Files.exists(dir.resolve("stat.txt"))) {
                m.put("error", "Yosys failed: " + log.lines().filter(l -> l.contains("ERROR")).findFirst().orElse(log.lines().reduce((a, b) -> b).orElse("")));
                return m;
            }
            var cells = new LinkedHashMap<String, Integer>();
            for (String line : Files.readAllLines(dir.resolve("stat.txt"))) {
                var c = CELL.matcher(line);
                if (c.matches()) cells.merge(c.group(2), Integer.parseInt(c.group(1)), Integer::sum);
            }
            int lut = 0, ff = 0, bram = 0, dsp = 0, lutram = 0, carry = 0;
            for (var x : cells.entrySet()) {
                String k = x.getKey();
                if (k.matches("LUT\\d")) lut += x.getValue();
                else if (k.matches("FD[A-Z]*")) ff += x.getValue();
                else if (k.startsWith("RAMB")) bram += x.getValue();
                else if (k.startsWith("DSP")) dsp += x.getValue();
                else if (k.startsWith("RAM") || k.startsWith("SRL")) lutram += x.getValue();
                else if (k.startsWith("CARRY")) carry += x.getValue();
            }
            m.put("luts", lut); m.put("ffs", ff); m.put("brams", bram); m.put("dsps", dsp); m.put("lutrams", lutram); m.put("carries", carry);
            m.put("cells", cells);
            m.put("ms", (System.nanoTime() - t0) / 1_000_000);
            Elaborator.deleteTree(dir);
            return m;
        } catch (IOException | InterruptedException x) {
            m.put("error", "Yosys could not run: " + x.getMessage());
            return m;
        }
    }

    private static String which(String exe) {
        for (String d : System.getenv().getOrDefault("PATH", "").split(java.io.File.pathSeparator)) {
            Path p = Path.of(d, exe);
            if (Files.isExecutable(p)) return p.toString();
        }
        for (String d : List.of("/opt/homebrew/bin", "/usr/local/bin", "/usr/bin")) {
            Path p = Path.of(d, exe);
            if (Files.isExecutable(p)) return p.toString();
        }
        return null;
    }

    /** The emitted SystemVerilog, for the code pane's SV view. */
    static String sv(Elaborated<Module> e) { return SvEmitter.emit(e); }
}
