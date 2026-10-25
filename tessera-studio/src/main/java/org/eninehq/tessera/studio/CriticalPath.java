package org.eninehq.tessera.studio;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.eninehq.tessera.emit.SvEmitter;

/**
 * Vivado's worst setup paths, read from its full-path timing report ({@code report_timing -path_type
 * full}) and mapped back to the design through the emitter's name table ({@link SvEmitter#names}).
 *
 * <p>Each hop of a path is a cell's arc or a net, with its incremental delay. Its Vivado name is reduced
 * to an emitted SystemVerilog name by taking off what synthesis adds, and looked up in the table. A hop
 * is <b>exact</b> when the cell or net is that name itself (a bit of it, its register, a copy Vivado made
 * of it), <b>approximate</b> when Vivado named a LUT or carry cell after a signal its logic only helps
 * compute (the {@code _i_N} cells), and <b>unmapped</b> when synthesis made the name up. Hops outside the
 * design (in a wrapper around it) are <b>outside</b> and not counted.
 */
final class CriticalPath {
    private CriticalPath() {}

    /** A hop: a cell arc ({@code prim} set) or a net, its Vivado object, and its delay. */
    record Hop(boolean net, String object, String prim, String arc, double incr, int fanout) {}

    /** A path, as the report gives it. */
    record Path(int rank, double slack, double delay, double logic, double route, int levels, String levelsDetail,
                String start, String end, List<Hop> hops) {}

    /** A path's first line: its slack, or inf for a path no clock constrains (from an input port, out of context). */
    private static final Pattern SLACK = Pattern.compile("^Slack(?: \\((?:MET|VIOLATED)\\))?\\s*:\\s*(-?[\\d.]+ns|inf)");
    private static final Pattern FIELD = Pattern.compile("^\\s+(Source|Destination|Data Path Delay|Logic Levels):\\s+(.*)$");
    private static final Pattern DELAY = Pattern.compile("([\\d.]+)ns\\s+\\(logic ([\\d.]+)ns.*route ([\\d.]+)ns");
    private static final Pattern LEVELS = Pattern.compile("(\\d+)\\s*(?:\\((.*)\\))?");
    private static final Pattern NET = Pattern.compile("^\\s+net \\(fo=(\\d+), \\w+\\)\\s+(-?[\\d.]+)\\s+(-?[\\d.]+)\\s+(\\S+)\\s*$");
    /** A cell line: [site] primitive [(arc)] [incr path] edge object; the numbers may be on the next line. */
    private static final Pattern CELL = Pattern.compile("^\\s+(?:(\\S+_X\\d+Y\\d+|IOB\\S*|\\S+_X\\d+)\\s+)?(\\w+)(?: \\(([^)]*)\\))?(?:\\s+(-?[\\d.]+)\\s+(-?[\\d.]+))?(?:\\s+([rf])\\s+(\\S+))?\\s*$");
    private static final Pattern NUMBERS = Pattern.compile("^\\s+(-?[\\d.]+)\\s+(-?[\\d.]+)\\s+([rf])\\s+(\\S+)\\s*$");

    /** The paths of a full-path report, in its order (worst first). */
    static List<Path> parse(String report) {
        var paths = new ArrayList<Path>();
        String[] lines = report.split("\n");
        for (int i = 0; i < lines.length; i++) {
            Matcher sm = SLACK.matcher(lines[i]);
            if (!sm.find()) continue;
            double slack = sm.group(1).equals("inf") ? Double.POSITIVE_INFINITY : Double.parseDouble(sm.group(1).replace("ns", "")), delay = 0, logic = 0, route = 0;
            int levels = 0;
            String detail = "", start = "", end = "";
            int j = i + 1;
            for (; j < lines.length && !lines[j].contains("Netlist Resource(s)"); j++) {
                Matcher f = FIELD.matcher(lines[j]);
                if (!f.matches()) continue;
                String v = f.group(2).trim();
                switch (f.group(1)) {
                    case "Source" -> start = v;
                    case "Destination" -> end = v;
                    case "Data Path Delay" -> {
                        Matcher d = DELAY.matcher(v);
                        if (d.find()) { delay = Double.parseDouble(d.group(1)); logic = Double.parseDouble(d.group(2)); route = Double.parseDouble(d.group(3)); }
                    }
                    default -> {
                        Matcher l = LEVELS.matcher(v);
                        if (l.find()) { levels = Integer.parseInt(l.group(1)); detail = l.group(2) == null ? "" : l.group(2); }
                    }
                }
            }
            // the data path: from the line under the column titles to the next rule
            var hops = new ArrayList<Hop>();
            j += 2;
            String prim = null, arc = null;
            boolean started = false;
            for (; j < lines.length && !lines[j].startsWith("  ---"); j++) {
                String l = lines[j];
                Matcher n = NET.matcher(l);
                if (n.matches()) {
                    if (started) hops.add(new Hop(true, n.group(4), "", "", Double.parseDouble(n.group(2)), Integer.parseInt(n.group(1))));
                    continue;
                }
                Matcher num = NUMBERS.matcher(l);
                if (num.matches() && prim != null) {
                    hops.add(new Hop(false, num.group(4), prim, arc, Double.parseDouble(num.group(1)), 0));
                    prim = null;
                    started = true;
                    continue;
                }
                Matcher c = CELL.matcher(l);
                if (!c.matches() || l.contains("clock") || l.contains("latency")) continue;
                if (c.group(7) != null) {
                    // the source clock pin (the start, no delay of its own) or a cell arc with its delay
                    hops.add(new Hop(false, c.group(7), c.group(2), c.group(3) == null ? "" : c.group(3),
                            c.group(4) == null ? 0 : Double.parseDouble(c.group(4)), 0));
                    started = true;
                } else {
                    prim = c.group(2);
                    arc = c.group(3) == null ? "" : c.group(3);
                }
            }
            paths.add(new Path(paths.size() + 1, slack, delay, logic, route, levels, detail, start, end, hops));
            i = j;
        }
        return paths;
    }

    /** The name table indexed for lookups: module → emitted name → entry. */
    static final class Table {
        final Map<String, Map<String, SvEmitter.Name>> byModule = new HashMap<>();
        final String top;

        Table(List<SvEmitter.Name> names, String top) {
            for (var n : names) byModule.computeIfAbsent(n.module(), k -> new HashMap<>()).put(n.name(), n);
            this.top = top;
        }

        /** The table written by {@link SvEmitter#nameTable} (for reports made outside the studio). */
        static Table read(String tsv, String top) {
            var l = new ArrayList<SvEmitter.Name>();
            for (String line : tsv.split("\n")) {
                if (line.isBlank() || line.startsWith("#")) continue;
                String[] p = line.split("\t", -1);
                l.add(new SvEmitter.Name(p[0], p[1], p[2], Integer.parseInt(p[3]), p[4], p[5], p[6], p[7], Integer.parseInt(p[8])));
            }
            return new Table(l, top);
        }
    }

    /** Where a hop is in the design: the entry, the instance path to its module (a.b.), and how sure that is. */
    record Mapped(String quality, SvEmitter.Name entry, String instance) {}

    private static final Pattern[] EXACT = {
            Pattern.compile("(.+)_replica(?:_\\d+)?$"), Pattern.compile("(.+)__\\d+$"), Pattern.compile("(.+)_n_\\d+$"),
            Pattern.compile("(.+)_reg_r?\\d+(?:_\\d+)*$"), Pattern.compile("(.+)_comp(?:_\\d+)?$"), Pattern.compile("(.+)\\[\\d+\\]$"), Pattern.compile("(.+)_reg$"),
            Pattern.compile("(.+)_rep(?:_\\d+)?$"), Pattern.compile("(.+)_inferred$")};
    private static final Pattern APPROX = Pattern.compile("(.+)_i_\\d+$");

    /**
     * Maps a hop. {@code wrapper} is the instance the design has in the top level Vivado built (core for
     * the out-of-context runs, u for the board build), or empty when the design was the top.
     */
    static Mapped map(Hop hop, Table t, String wrapper) {
        String o = hop.object();
        // a pin is cell/PIN: the cell is what was placed
        if (!hop.net()) { int k = o.lastIndexOf('/'); if (k > 0) o = o.substring(0, k); }
        List<String> segs = new ArrayList<>(List.of(o.split("/")));
        if (!wrapper.isEmpty()) {
            if (segs.size() < 2 || !segs.getFirst().equals(wrapper)) return new Mapped("outside", null, "");
            segs.removeFirst();
        }
        String module = t.top;
        var inst = new StringBuilder();
        while (segs.size() > 1) {
            var e = t.byModule.getOrDefault(module, Map.of()).get(segs.getFirst());
            // not an instance: a macro's own sub-cell or net (a LUT RAM's RAMA, a DSP cascade), named under the macro
            if (e == null || !e.kind().equals("inst")) break;
            inst.append(e.signal()).append('.');
            module = e.child();
            segs.removeFirst();
        }
        var names = t.byModule.getOrDefault(module, Map.of());
        String s = segs.getFirst();
        boolean approx = false;
        for (int guard = 0; guard < 12; guard++) {
            var e = names.get(s);
            if (e != null) return new Mapped(approx ? "approximate" : "exact", e, inst.toString());
            String next = null;
            for (var p : EXACT) { Matcher m = p.matcher(s); if (m.matches()) { next = m.group(1); break; } }
            if (next == null) {
                Matcher m = APPROX.matcher(s);
                if (m.matches()) { next = m.group(1); approx = true; }
            }
            if (next == null) break;
            s = next;
        }
        return new Mapped("unmapped", null, inst.toString());
    }

    /** The paths as the browser shows them, each hop with where it is in the design. */
    static Map<String, Object> json(List<Path> paths, Table t, String wrapper, String source) {
        var out = new ArrayList<Object>();
        int[] counts = new int[4];
        for (var p : paths) {
            var hops = new ArrayList<Object>();
            var ms = new ArrayList<Mapped>();
            for (var h : p.hops()) ms.add(map(h, t, wrapper));
            // a path from (or into) the wrapper: the unmapped hops before the first (after the last) hop of the
            // design are the wrapper's logic that synthesis pushed across the boundary (a power-on reset's LUTs)
            int first = 0, last = ms.size() - 1;
            if (!ms.isEmpty() && ms.getFirst().quality().equals("outside"))
                for (; first < ms.size() && !ms.get(first).quality().matches("exact|approximate"); first++)
                    if (ms.get(first).quality().equals("unmapped")) ms.set(first, new Mapped("outside", null, ""));
            if (!ms.isEmpty() && ms.getLast().quality().equals("outside"))
                for (; last >= 0 && !ms.get(last).quality().matches("exact|approximate"); last--)
                    if (ms.get(last).quality().equals("unmapped")) ms.set(last, new Mapped("outside", null, ""));
            for (int k = 0; k < p.hops().size(); k++) {
                var h = p.hops().get(k);
                var m = ms.get(k);
                counts[switch (m.quality()) { case "exact" -> 0; case "approximate" -> 1; case "unmapped" -> 2; default -> 3; }]++;
                var j = new LinkedHashMap<String, Object>();
                j.put("net", h.net());
                j.put("object", h.object());
                j.put("prim", h.prim());
                j.put("incr", h.incr());
                j.put("quality", m.quality());
                if (m.entry() != null) {
                    var e = m.entry();
                    j.put("name", e.name());
                    j.put("kind", e.kind());
                    j.put("signal", m.instance() + e.signal());
                    j.put("op", e.op());
                    if (e.line() > 0) { j.put("file", e.file()); j.put("line", e.line()); }
                }
                hops.add(j);
            }
            var j = new LinkedHashMap<String, Object>();
            j.put("rank", p.rank());
            // a path no clock constrains has no slack (JSON has no infinity)
            j.put("slack", Double.isInfinite(p.slack()) ? null : p.slack());
            if (Double.isInfinite(p.slack())) j.put("unconstrained", true);
            j.put("delay", p.delay());
            j.put("logic", p.logic());
            j.put("route", p.route());
            j.put("levels", p.levels());
            j.put("levelsDetail", p.levelsDetail());
            j.put("start", p.start());
            j.put("end", p.end());
            j.put("hops", hops);
            out.add(j);
        }
        var r = new LinkedHashMap<String, Object>();
        r.put("source", source);
        r.put("paths", out);
        r.put("mapping", Map.of("exact", counts[0], "approximate", counts[1], "unmapped", counts[2], "outside", counts[3]));
        return r;
    }

    static final String NOT_VIVADO = "This is not a Vivado full-path timing report (report_timing -path_type full); no path was found in it. "
            + "Reports from openXC7 (nextpnr-xilinx) are not read: its timing is not trusted for these designs, since it does not model DSP and memory cells.";

    /** The name table of a design, elaborated again with its operations' locations (skipping {@code skip}). */
    static Table table(java.util.function.Supplier<org.eninehq.tessera.core.Elaborated<org.eninehq.tessera.core.Module>> elaborate, List<String> skip) {
        var locs = new java.util.IdentityHashMap<org.eninehq.tessera.ir.Expr, org.eninehq.tessera.core.SourceLoc>();
        var e = org.eninehq.tessera.core.OpLocations.record(locs, skip, elaborate);
        return new Table(SvEmitter.names(e.circuit(), locs), SvEmitter.topName(e.circuit()));
    }

    /**
     * {@link #json} for the wrapper instance that maps the most hops: none (the design was Vivado's top),
     * core (the out-of-context runs' wrapper) or u (the board build's).
     */
    static Map<String, Object> best(List<Path> paths, Table t, String source) {
        Map<String, Object> best = null;
        int most = -1;
        for (String w : List.of("", "core", "u")) {
            var j = json(paths, t, w, source);
            var m = (Map<?, ?>) j.get("mapping");
            int got = (Integer) m.get("exact") + (Integer) m.get("approximate");
            if (got > most) { most = got; best = j; best.put("wrapper", w); }
        }
        return best;
    }

    /**
     * Where to look for the Java a hop's line is in: {@code extra} (the project's sources), and every
     * {@code <module>/src/main/java} of the working directory (the repository's own, for the cores).
     */
    static List<java.nio.file.Path> sourceRoots(List<java.nio.file.Path> extra) {
        var roots = new ArrayList<java.nio.file.Path>();
        for (var r : extra) if (java.nio.file.Files.isDirectory(r)) roots.add(r);
        try (var dirs = java.nio.file.Files.list(java.nio.file.Path.of("").toAbsolutePath())) {
            dirs.map(d -> d.resolve("src/main/java")).filter(java.nio.file.Files::isDirectory).sorted().forEach(roots::add);
        } catch (java.io.IOException e) {
            // no repository here: the lines go without their text
        }
        return roots;
    }

    /** Adds {@code code}, the text of its Java line, to each hop whose file is found once under {@code roots}. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> withSource(Map<String, Object> j, List<java.nio.file.Path> roots) {
        var files = new HashMap<String, java.nio.file.Path>();
        var dup = new java.util.HashSet<String>();
        for (var r : roots) {
            try (var w = java.nio.file.Files.walk(r)) {
                w.filter(p -> p.toString().endsWith(".java")).forEach(p -> {
                    String n = p.getFileName().toString();
                    if (files.putIfAbsent(n, p) != null && !files.get(n).equals(p)) dup.add(n);
                });
            } catch (java.io.IOException | java.io.UncheckedIOException e) {
                // an unreadable root: skip it
            }
        }
        var text = new HashMap<String, List<String>>();
        for (var p : (List<Map<String, Object>>) j.get("paths"))
            for (var h : (List<Map<String, Object>>) p.get("hops")) {
                if (!(h.get("file") instanceof String f) || dup.contains(f) || !files.containsKey(f)) continue;
                var lines = text.computeIfAbsent(f, k -> {
                    try { return java.nio.file.Files.readAllLines(files.get(k)); } catch (java.io.IOException e) { return List.of(); }
                });
                int n = (Integer) h.get("line");
                if (n >= 1 && n <= lines.size()) h.put("code", lines.get(n - 1).strip());
            }
        return j;
    }

    /** Mapping rates of a report against a name table: {@code CriticalPath <paths.rpt> <names.tsv> <top module> <wrapper>}. */
    public static void main(String[] args) throws java.io.IOException {
        var paths = parse(java.nio.file.Files.readString(java.nio.file.Path.of(args[0])));
        var t = Table.read(java.nio.file.Files.readString(java.nio.file.Path.of(args[1])), args[2]);
        String wrapper = args.length > 3 ? args[3] : "";
        var m = (Map<?, ?>) json(paths, t, wrapper, args[0]).get("mapping");
        System.out.println(paths.size() + " paths, hops: " + m);
        if (args.length > 4 && args[4].equals("--unmapped")) {
            var seen = new java.util.TreeMap<String, Integer>();
            for (var p : paths)
                for (var h : p.hops()) if (map(h, t, wrapper).quality().equals("unmapped")) seen.merge((h.net() ? "net " : h.prim() + " ") + h.object(), 1, Integer::sum);
            seen.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(40).forEach(e -> System.out.println("  " + e.getValue() + "  " + e.getKey()));
        }
    }
}
