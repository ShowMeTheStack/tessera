package org.eninehq.tessera.studio;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Turns a studio drawing into Tessera builder code, the code a person would write: input and
 * output ports, registers, one named local value per operation, Tessera's width rules (no
 * forgiving casts). On the way it works out every net's width and lists the problems that keep
 * the drawing from elaborating, each as a sentence that names the parts, with a fix where one is
 * obvious.
 *
 * <p>The drawing travels with the code in a trailing comment, so a file written by the studio
 * opens back into the same drawing ({@link #embedded}).
 */
public final class CodeGen {
    /** A problem: severity error or warning, a sentence, the parts and nets it is about, and fixes. */
    public record Problem(String severity, String code, String message, List<String> parts, List<String> nets, List<Map<String, Object>> fixes) {
        Map<String, Object> json() {
            var m = new LinkedHashMap<String, Object>();
            m.put("severity", severity);
            m.put("code", code);
            m.put("message", message);
            m.put("parts", parts);
            m.put("nets", nets);
            m.put("fixes", fixes);
            return m;
        }
    }

    /** The result: the Java source, each net's width, the problems, and which lines each part produced. */
    public record Result(String className, String java, Map<String, Integer> netWidths, List<Problem> problems,
                         Map<String, List<Integer>> lines, Map<String, String> netPaths, Map<String, String> innerPaths,
                         Map<String, Map<String, Integer>> blockWidths, Map<String, String> partVars) {
        public boolean ok() { return problems.stream().noneMatch(p -> p.severity().equals("error")); }

        public Map<String, Object> json() {
            var m = new LinkedHashMap<String, Object>();
            m.put("className", className);
            m.put("java", java);
            m.put("netWidths", netWidths);
            var ps = new ArrayList<Object>();
            for (var p : problems) ps.add(p.json());
            m.put("problems", ps);
            m.put("lines", lines);
            m.put("netPaths", netPaths);
            m.put("innerPaths", innerPaths);
            m.put("blockWidths", blockWidths);
            m.put("ok", ok());
            return m;
        }
    }

    public static final String MARK = "// tessera-studio:1 ";
    private static final Pattern IDENT = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Set<String> JAVA_WORDS = Set.of("abstract", "assert", "boolean", "break", "byte", "case", "catch", "char",
            "class", "const", "continue", "default", "do", "double", "else", "enum", "extends", "final", "finally", "float", "for",
            "goto", "if", "implements", "import", "instanceof", "int", "interface", "long", "native", "new", "package", "private",
            "protected", "public", "return", "short", "static", "strictfp", "super", "switch", "synchronized", "this", "throw",
            "throws", "transient", "try", "void", "volatile", "while", "var", "record", "yield", "true", "false", "null",
            "clk", "input", "output", "reg", "wire", "mem", "instance", "reset");

    // ---------------------------------------------------------------- the drawing, as read from JSON

    record Part(String id, String type, Map<String, Object> params, String label) {}

    /** A value at Java level: its expression, Java type (Bool or UInt) and width. */
    record Val(String expr, String jtype, int width) {}

    private final Map<String, Object> doc;
    private final Map<String, Part> parts = new LinkedHashMap<>();
    private final Map<String, List<String[]>> netPins = new LinkedHashMap<>();
    private final Map<String, String> netName = new HashMap<>();
    private final Map<String, String> pinNet = new HashMap<>();           // "part.pin" -> net
    private final List<Problem> problems = new ArrayList<>();
    private final Map<String, Integer> widths = new LinkedHashMap<>();
    private final Map<String, Val> netVal = new HashMap<>();
    private final Map<String, Set<String>> blame = new HashMap<>();
    private final Set<String> visiting = new HashSet<>();
    private final Set<String> done = new HashSet<>();
    private final Set<String> usedNames = new HashSet<>(JAVA_WORDS);
    private final List<String[]> body = new ArrayList<>();                // {partId, line}
    private final List<String[]> decls = new ArrayList<>();
    private final List<String[]> sinks = new ArrayList<>();
    private final Map<String, String> varOf = new HashMap<>();             // part id -> Java name
    private final Map<String, String> netPath = new LinkedHashMap<>();     // net -> signal path in the hardware
    private final List<String> fields = new ArrayList<>();                 // a block's port fields
    private final Map<String, Map<String, String>> blockNetPaths = new HashMap<>();
    private final Map<String, Map<String, String>> blockInner = new HashMap<>();
    private final Map<String, String> innerPaths = new LinkedHashMap<>();  // "inst/net" -> path, for nets inside blocks
    private final Map<String, Map<String, Integer>> blockWidths = new LinkedHashMap<>();
    private String nestedBody;
    private boolean usesClock, usesHw, usesBool, usesDuration, usesTiming, usesMem, usesEnum, usesTable, usesUtil, usesValid, usesDomain;

    // hierarchy: the blocks of the drawing (shared by every level), the block being generated (null at the top)
    private final Map<String, Map<String, Object>> blocks;
    private final String nested;
    private final Set<String> generating;
    private final List<String> nestedClasses = new ArrayList<>();
    private final Map<String, Boolean> blockUsesClock = new HashMap<>();

    private CodeGen(Map<String, Object> doc) {
        this(doc, null, blocksOf(doc), new HashSet<>());
    }

    private CodeGen(Map<String, Object> doc, String nested, Map<String, Map<String, Object>> blocks, Set<String> generating) {
        this.doc = doc;
        this.nested = nested;
        this.blocks = blocks;
        this.generating = generating;
    }

    private static Map<String, Map<String, Object>> blocksOf(Map<String, Object> doc) {
        var m = new LinkedHashMap<String, Map<String, Object>>();
        for (var e : map(doc.get("blocks")).entrySet()) m.put(e.getKey(), map(e.getValue()));
        return m;
    }

    /** The ports of a block, from its input and output parts, ordered top to bottom. */
    static List<Parts.Pin> blockPins(Map<String, Object> block) {
        var ins = new ArrayList<Map<String, Object>>();
        var outs = new ArrayList<Map<String, Object>>();
        for (var p : list(block.get("parts"))) {
            String t = String.valueOf(p.get("type"));
            if (t.equals("in")) ins.add(p);
            if (t.equals("out")) outs.add(p);
        }
        java.util.Comparator<Map<String, Object>> byY = java.util.Comparator.comparingDouble(p -> p.get("y") instanceof Number n ? n.doubleValue() : 0);
        ins.sort(byY);
        outs.sort(byY);
        var l = new ArrayList<Parts.Pin>();
        for (var p : ins) l.add(Parts.Pin.in(Parts.strParam(map(p.get("params")), "name", "a"), Parts.strParam(map(p.get("params")), "name", "a")));
        for (var p : outs) l.add(Parts.Pin.out(Parts.strParam(map(p.get("params")), "name", "y"), Parts.strParam(map(p.get("params")), "name", "y")));
        return l;
    }

    private static int blockPortWidth(Map<String, Object> block, String port) {
        for (var p : list(block.get("parts"))) {
            var params = map(p.get("params"));
            String t = String.valueOf(p.get("type"));
            if ((t.equals("in") || t.equals("out")) && port.equals(Parts.strParam(params, "name", ""))) return Parts.intParam(params, "width", 1);
        }
        return 1;
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> list(Object o) {
        return o instanceof List<?> l ? (List<Map<String, Object>>) l : List.of();
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : new LinkedHashMap<>();
    }

    public static Result generate(Map<String, Object> doc) {
        return new CodeGen(doc).run();
    }

    private Result run() {
        String cls = javaName(String.valueOf(doc.getOrDefault("name", "Design")), "Design");
        String pkg = String.valueOf(doc.getOrDefault("package", "studio"));
        for (var p : list(doc.get("parts"))) {
            var part = new Part(String.valueOf(p.get("id")), String.valueOf(p.get("type")), map(p.get("params")),
                    p.get("label") == null ? null : String.valueOf(p.get("label")));
            if (Parts.type(part.type()) == null && !Set.of("block", "module", "code").contains(part.type())) {
                problem("error", "unknown-part", "Part " + part.id() + " is of an unknown type '" + part.type() + "'.", List.of(part.id()), List.of(), List.of());
                continue;
            }
            parts.put(part.id(), part);
        }
        for (var n : list(doc.get("nets"))) {
            String id = String.valueOf(n.get("id"));
            var pins = new ArrayList<String[]>();
            for (Object o : n.get("pins") instanceof List<?> l ? l : List.of()) {
                if (o instanceof List<?> pr && pr.size() == 2) {
                    String part = String.valueOf(pr.get(0)), pin = String.valueOf(pr.get(1));
                    if (!parts.containsKey(part)) continue;
                    pins.add(new String[]{part, pin});
                    String key = part + "." + pin;
                    if (pinNet.containsKey(key)) {
                        problem("error", "pin-two-nets", pinText(part, pin) + " is on two wires (" + pinNet.get(key) + " and " + id + ").",
                                List.of(part), List.of(id, pinNet.get(key)), List.of());
                    }
                    pinNet.put(key, id);
                }
            }
            netPins.put(id, pins);
            if (n.get("name") instanceof String s && !s.isBlank()) netName.put(id, s.trim());
        }
        checkDrivers();
        checkNames();
        if (nested != null)   // a block's port names are its fields: keep them free for the ports
            for (var p : parts.values()) if (p.type().equals("in") || p.type().equals("out")) usedNames.add(Parts.strParam(p.params(), "name", ""));
        // sources first (ports and registers are declared before any logic reads them)
        for (var p : parts.values()) if (p.type().equals("in")) declareInput(p);
        for (var p : parts.values()) if (p.type().equals("reg")) declareReg(p);
        for (var p : parts.values()) if (p.type().equals("block")) declareBlock(p);
        for (var p : parts.values()) if (p.type().equals("module") || p.type().equals("code")) declareModule(p);
        for (var p : parts.values()) if (p.type().equals("mem")) declareMem(p);
        for (var p : parts.values()) if (p.type().equals("fsm")) declareFsm(p);
        // sinks pull in the logic they need, in order
        for (var p : parts.values()) if (p.type().equals("out")) emitOut(p);
        for (var p : parts.values()) if (p.type().equals("reg")) emitRegNext(p);
        for (var p : parts.values()) if (Set.of("assert", "assume", "cover").contains(p.type())) emitCheck(p);
        for (var p : parts.values()) if (p.type().equals("block")) emitBlockInputs(p);
        for (var p : parts.values()) if (p.type().equals("module") || p.type().equals("code")) emitModuleInputs(p);
        emitStreams();
        for (var p : parts.values()) if (p.type().equals("fsm")) emitFsmNext(p);
        for (var p : parts.values()) if (p.type().equals("mem")) emitMemReadAddr(p);
        for (var p : parts.values()) if (p.type().equals("mem")) emitMemWrite(p);
        for (var p : parts.values()) if (p.type().equals("mem")) eval(p.id());   // a memory nobody reads is still built
        // logic that reaches no sink still elaborates (and is shown), but is reported
        for (var p : parts.values()) {
            if (!done.contains(p.id()) && !Set.of("in", "out", "reg", "assert", "assume", "cover", "block", "mem", "fsm", "module", "code", "domain").contains(p.type())) {
                eval(p.id());
                if (!p.type().equals("const"))
                    problem("warning", "unused", describe(p) + " drives nothing that reaches an output or a register; it is optimised away.",
                            List.of(p.id()), List.of(), List.of());
            }
        }
        for (var net : netPins.keySet()) {
            var v = netVal.get(net);
            if (v != null) widths.put(net, v.width());
        }
        applyDomains();
        if (nested != null) {
            nestedBody = renderNested(nested);
            return new Result(nested, nestedBody, widths, problems, Map.of(), netPath, innerPaths, blockWidths, new LinkedHashMap<>(varOf));
        }
        var r = render(cls, pkg);
        return new Result(cls, r.java, widths, problems, r.lines, netPath, innerPaths, blockWidths, new LinkedHashMap<>(varOf));
    }

    // ---------------------------------------------------------------- clock domains

    private static final Pattern DECL = Pattern.compile("^([\\w<>.]+ \\w+ = )(.*);$");

    /**
     * Clock-domain parts become {@code ClockDomain x = clockDomain("x")}; a part whose domain names one
     * makes its registers inside {@code withClock(x, () -> ...)}: its register, library call, synchroniser,
     * synchronous read, instance, or memory write.
     */
    private void applyDomains() {
        var vars = new LinkedHashMap<String, String>();
        var mains = new ArrayList<String>();
        var decl = new ArrayList<String[]>();
        for (var p : parts.values()) {
            if (!p.type().equals("domain")) continue;
            done.add(p.id());
            String n = Parts.strParam(p.params(), "name", "");
            if (nested != null) {
                problem("error", "domain-in-block", describe(p) + " is inside a block: clock domains belong to the top of the drawing. Give the block's instance the domain instead; everything in it then runs on that clock.",
                        List.of(p.id()), List.of(), List.of());
                continue;
            }
            int per = Parts.intParam(p.params(), "period", 2), ph = Parts.intParam(p.params(), "phase", 0);
            if (ph >= per) problem("error", "domain-phase", describe(p) + " has its first edge at tick " + ph + ", which is not less than its period " + per + ".", List.of(p.id()), List.of(), List.of());
            if (n.equals("clock")) { mains.add(p.id()); continue; }   // the design's own clock: only its period
            if (!n.matches("[a-z]\\w*")) {
                problem("error", "domain-name", describe(p) + " needs a name such as fast (a lower-case Java name); its ports are then fast_clock and fast_reset.", List.of(p.id()), List.of(), List.of());
                continue;
            }
            if (vars.containsKey(n)) {
                problem("error", "domain-twice", "Two Clock domain parts are named " + n + ".", List.of(p.id()), List.of(), List.of());
                continue;
            }
            String v = claim(n);
            vars.put(n, v);
            decl.add(new String[]{p.id(), "ClockDomain " + v + " = clockDomain(\"" + n + "\");"});
        }
        if (mains.size() > 1) problem("error", "domain-twice", "Two Clock domain parts are named clock.", mains, List.of(), List.of());
        if (!decl.isEmpty()) { decls.addAll(0, decl); usesDomain = true; }
        for (var p : parts.values()) {
            String d = Parts.strParam(p.params(), "domain", "").trim();
            if (d.isEmpty() || d.equals("clock") || p.type().equals("domain")) continue;
            if (!Parts.SEQUENTIAL.contains(p.type())) continue;
            if (nested != null) {
                problem("error", "domain-in-block", describe(p) + " is in a block and names the clock domain " + d + ": inside a block every part runs on the block's clock. Give the block's instance the domain instead.",
                        List.of(p.id()), List.of(), List.of());
                continue;
            }
            String v = vars.get(d);
            if (v == null) {
                problem("error", "no-domain", describe(p) + " runs in the clock domain " + d + ", but no Clock domain part is named " + d + ".", List.of(p.id()),
                        List.of(), List.of());
                continue;
            }
            for (var group : List.of(decls, body, sinks))
                for (int i = 0; i < group.size(); i++) {
                    var line = group.get(i);
                    if (!line[0].equals(p.id())) continue;
                    String t = line[1];
                    if (t.contains("Timing.")) {
                        problem("error", "domain-timing", describe(p) + " counts real time on the design's clock, so it cannot run in the domain " + d + ".", List.of(p.id()), List.of(), List.of());
                        continue;
                    }
                    var m = DECL.matcher(t);
                    if (m.matches() && (m.group(2).startsWith("reg(") || m.group(2).startsWith("Util.") || m.group(2).startsWith("Hw.synchronize(")
                            || m.group(2).contains(".readSync(") || m.group(2).startsWith("instance(")))
                        group.set(i, new String[]{line[0], m.group(1) + "withClock(" + v + ", () -> " + m.group(2) + ");"});
                    else if (t.matches("^\\w+\\.write\\(.*\\);$"))
                        group.set(i, new String[]{line[0], "withClock(" + v + ", () -> " + t.substring(0, t.length() - 1) + ");"});
                }
        }
    }

    // ---------------------------------------------------------------- checks

    private void checkDrivers() {
        for (var e : netPins.entrySet()) {
            var drivers = new ArrayList<String[]>();
            var readers = new ArrayList<String[]>();
            for (var pp : e.getValue()) {
                var pin = pinOf(pp[0], pp[1]);
                if (pin == null) {
                    problem("error", "no-pin", "Part " + pp[0] + " has no pin '" + pp[1] + "' (its parameters changed?); the wire to it is dropped.",
                            List.of(pp[0]), List.of(e.getKey()), List.of(fix("Remove the wire to " + pp[1], "unwire", Map.of("part", pp[0], "pin", pp[1]))));
                    continue;
                }
                (pin.dir() == Parts.Dir.OUT ? drivers : readers).add(pp);
            }
            if (drivers.size() > 1) {
                var names = new ArrayList<String>();
                for (var d : drivers) names.add(pinText(d[0], d[1]));
                problem("error", "two-drivers", "Wire " + netLabel(e.getKey()) + " is driven by " + String.join(" and ", names)
                        + ": an output can drive many inputs, but each wire needs exactly one driver.",
                        partsOf(drivers), List.of(e.getKey()), List.of());
            } else if (drivers.isEmpty() && !readers.isEmpty()) {
                problem("error", "no-driver", "Wire " + netLabel(e.getKey()) + " reaches " + pinList(readers) + " but nothing drives it.",
                        partsOf(readers), List.of(e.getKey()), List.of());
            }
        }
        for (var p : parts.values()) {
            for (var pin : pinsOf(p)) {
                if (pin.dir() == Parts.Dir.IN && !pinNet.containsKey(p.id() + "." + pin.name()))
                    problem("error", "open-input", pinText(p.id(), pin.name()) + " is not connected; Tessera needs every input driven.",
                            List.of(p.id()), List.of(), List.of(fix("Tie it to 0", "tie", Map.of("part", p.id(), "pin", pin.name(), "value", 0))));
            }
        }
    }

    private void checkNames() {
        var seen = new HashMap<String, String>();
        for (var p : parts.values()) {
            // every name that becomes a name in the hardware: ports, registers, memories, state machines, instances
            if (!Set.of("in", "out", "reg", "assert", "assume", "cover", "mem", "fsm", "block", "module", "code").contains(p.type())) continue;
            String n = Parts.strParam(p.params(), "name", "");
            if (!IDENT.matcher(n).matches()) {
                problem("error", "bad-name", describe(p) + " has the name '" + n + "', which is not a Java identifier (a letter or _, then letters, digits or _).",
                        List.of(p.id()), List.of(), List.of());
            } else if (seen.containsKey(n)) {
                problem("error", "name-clash", "Two parts are called '" + n + "' (" + seen.get(n) + " and " + p.id() + "); ports, registers, memories and instances need distinct names.",
                        List.of(seen.get(n), p.id()), List.of(), List.of());
            } else {
                seen.put(n, p.id());
            }
        }
    }

    // ---------------------------------------------------------------- declarations

    private void declareInput(Part p) {
        int w = width(p);
        String name = Parts.strParam(p.params(), "name", "a");
        String v = claim(name);
        varOf.put(p.id(), v);
        String type = w == 1 ? "Bool" : "UInt";
        if (w == 1) usesBool = true;
        if (nested != null) {
            v = name;                         // a block's port is a public field named as the port
            varOf.put(p.id(), v);
            fields.add("public final " + type + " " + name + ";");
            decls.add(new String[]{p.id(), name + " = input(\"" + name + "\", " + typeExpr(w) + ");"});
        } else
            decls.add(new String[]{p.id(), type + " " + v + " = input(\"" + name + "\", " + typeExpr(w) + ");"});
        setOut(p.id(), "y", new Val(v, type, w));
        String net = pinNet.get(p.id() + ".y");
        if (net != null) netPath.put(net, name);
        done.add(p.id());
    }

    private void declareReg(Part p) {
        int w = width(p);
        String name = Parts.strParam(p.params(), "name", "r");
        String v = claim(name);
        varOf.put(p.id(), v);
        String type = w == 1 ? "Bool" : "UInt";
        if (w == 1) usesBool = true;
        BigInteger init = literal(p, Parts.strParam(p.params(), "init", "0"), "reset value");
        if (init != null && init.bitLength() > w)
            problem("error", "init-too-wide", describe(p) + " starts at " + init + ", which needs " + init.bitLength() + " bits; it has " + w + ".",
                    List.of(p.id()), List.of(), List.of(fix("Make it " + init.bitLength() + " bits", "param", Map.of("part", p.id(), "name", "width", "value", init.bitLength()))));
        String initText = init == null ? "0" : (init.bitLength() > 63 ? "/* " + init + " */ 0" : init.toString() + (init.bitLength() > 31 ? "L" : ""));
        decls.add(new String[]{p.id(), type + " " + v + " = reg(\"" + name + "\", " + typeExpr(w) + ", " + initText + ");"});
        setOut(p.id(), "q", new Val(v, type, w));
        String net = pinNet.get(p.id() + ".q");
        if (net != null) netPath.put(net, name);
        done.add(p.id());
    }

    private void declareBlock(Part p) {
        String bname = Parts.strParam(p.params(), "block", "");
        var b = blocks.get(bname);
        String inst = Parts.strParam(p.params(), "name", p.id());
        if (b == null) {
            problem("error", "no-block", "Block part " + inst + " refers to a block '" + bname + "' that does not exist.", List.of(p.id()), List.of(), List.of());
            done.add(p.id());
            return;
        }
        String cls = javaName(bname, "Block");
        if (generating.contains(bname)) {
            problem("error", "block-loop", "Block '" + bname + "' contains itself.", List.of(p.id()), List.of(), List.of());
            done.add(p.id());
            return;
        }
        if (!blockUsesClock.containsKey(bname)) {
            generating.add(bname);
            var sub = new CodeGen(b, cls, blocks, generating);
            var r = sub.run();
            generating.remove(bname);
            for (var pr : r.problems())
                problems.add(new Problem(pr.severity(), pr.code(), "In block " + bname + ": " + pr.message(), List.of(p.id()), List.of(), List.of()));
            nestedClasses.addAll(sub.nestedClasses);
            nestedClasses.add(sub.nestedBody);
            blockUsesClock.put(bname, sub.usesClock);
            blockNetPaths.put(bname, sub.netPath);
            blockWidths.put(bname, r.netWidths());
            blockInner.put(bname, sub.innerPaths);
            usesBool |= sub.usesBool; usesHw |= sub.usesHw; usesDuration |= sub.usesDuration; usesTiming |= sub.usesTiming; usesClock |= sub.usesClock; usesMem |= sub.usesMem; usesEnum |= sub.usesEnum; usesTable |= sub.usesTable; usesUtil |= sub.usesUtil; usesValid |= sub.usesValid;
        }
        // the nets inside the block, as seen through this instance (for live values when the block is open)
        for (var e : blockNetPaths.get(bname).entrySet()) innerPaths.put(inst + "/" + e.getKey(), inst + "." + e.getValue());
        for (var e : blockInner.get(bname).entrySet()) innerPaths.put(inst + "/" + e.getKey(), inst + "." + e.getValue());
        String v = claim(inst);
        varOf.put(p.id(), v);
        decls.add(new String[]{p.id(), cls + " " + v + " = instance(\"" + inst + "\", () -> new " + cls + "(" + (blockUsesClock.get(bname) ? "clk" : "") + "));"});
        for (var pin : blockPins(b)) {
            if (pin.dir() != Parts.Dir.OUT) continue;
            int w = blockPortWidth(b, pin.name());
            setOut(p.id(), pin.name(), new Val(v + "." + pin.name(), w == 1 ? "Bool" : "UInt", w));
            String net = pinNet.get(p.id() + "." + pin.name());
            if (net != null) netPath.put(net, inst + "." + pin.name());
        }
        done.add(p.id());
    }

    private final Set<String> extraImports = new java.util.TreeSet<>();
    private final Set<String> codeClasses = new HashSet<>();
    private boolean codeImports;

    private static Map<String, Object> pinSpec(Part p, String pin) {
        for (var q : list(p.params().get("pins"))) if (pin.equals(String.valueOf(q.get("name")))) return q;
        return Map.of();
    }

    /** A module written in Java: an instance made by the part's expression, its ports reached through the paths found when it was probed. */
    private void declareModule(Part p) {
        if (p.type().equals("code")) {   // a class written by hand: nested in this file, once per class
            String cls = Parts.strParam(p.params(), "cls", "");
            String src = Parts.strParam(p.params(), "source", "");
            if (!cls.matches("[A-Z]\\w*") || src.isBlank()) {
                problem("error", "no-code", describe(p) + " has no class yet: write it in the code pane.", List.of(p.id()), List.of(), List.of());
                done.add(p.id());
                return;
            }
            if (codeClasses.add(cls)) {
                nestedClasses.add("");
                for (String line : src.strip().split("\\R")) nestedClasses.add("    " + line);
                codeImports = true;
            }
        }
        String expr = p.type().equals("code") ? "new " + Parts.strParam(p.params(), "cls", "") + (Modules.takesClock(Parts.strParam(p.params(), "cls", ""), Parts.strParam(p.params(), "source", "")) ? "(clk)" : "()")
                : Parts.strParam(p.params(), "expr", "").trim();
        String inst = Parts.strParam(p.params(), "name", p.id());
        if (expr.isEmpty() || list(p.params().get("pins")).isEmpty()) {
            problem("error", "no-module", p.type().equals("code")
                    ? describe(p) + " has no pins: its class declares no ports in public fields, or has not been applied yet (⌘Enter in the code pane)."
                    : describe(p) + " has no module yet: give it a Java expression such as new Fifo<>(UInt.w(8), 16).", List.of(p.id()), List.of(), List.of());
            done.add(p.id());
            return;
        }
        if (p.params().get("imports") instanceof List<?> l) for (Object o : l) if (String.valueOf(o).matches("[\\w.]+")) extraImports.add(String.valueOf(o));
        if (Boolean.TRUE.equals(p.params().get("clock")) || expr.matches("(?s).*\\bclk\\b.*")) usesClock = true;
        String v = claim(inst);
        varOf.put(p.id(), v);
        decls.add(new String[]{p.id(), "var " + v + " = instance(\"" + inst + "\", () -> " + expr + ");"});
        var st = streams(p.params());
        for (var q : list(p.params().get("pins"))) {
            if (!"out".equals(q.get("dir"))) continue;
            String n = String.valueOf(q.get("name"));
            if (inStream(n, st)) continue;
            int w = q.get("width") instanceof Number num ? num.intValue() : 1;
            String jt = String.valueOf(q.getOrDefault("jtype", w == 1 ? "Bool" : "UInt"));
            setOut(p.id(), n, new Val(v + q.get("path"), jt, w));
            if (jt.equals("Bool")) usesBool = true;
            String net = pinNet.get(p.id() + "." + n);
            if (net != null) netPath.put(net, inst + "." + n);
        }
        done.add(p.id());
    }

    private static boolean inStream(String pin, Map<String, String> streams) {
        String base = pin.replaceFirst("_(valid|ready|bits)$", "");
        return !base.equals(pin) && streams.containsKey(base);
    }

    private static Map<String, Object> rawPin(Part p, String name) {
        for (var q : list(p.params().get("pins"))) if (name.equals(String.valueOf(q.get("name")))) return q;
        return Map.of();
    }

    /**
     * A stream wire joins a module's output stream to another's input stream: valid and bits go forward and
     * ready comes back. One sender and one receiver per wire; the bits must fit.
     */
    private void emitStreams() {
        for (var e : netPins.entrySet()) {
            String sender = null, senderPin = null;
            var receivers = new ArrayList<String[]>();
            boolean any = false, other = false;
            for (var pp : e.getValue()) {
                Part part = parts.get(pp[0]);
                var st = part != null && (part.type().equals("module") || part.type().equals("code")) ? streams(part.params()) : Map.<String, String>of();
                if (!st.containsKey(pp[1])) { other = true; continue; }
                any = true;
                if ("out".equals(st.get(pp[1]))) { if (sender != null) { problem("error", "stream", "Stream wire " + netLabel(e.getKey()) + " has two senders.", partsOf(e.getValue()), List.of(e.getKey()), List.of()); return; } sender = pp[0]; senderPin = pp[1]; }
                else receivers.add(pp);
            }
            if (!any) continue;
            if (other) { problem("error", "stream", "Stream wire " + netLabel(e.getKey()) + " joins a stream to a plain pin; show the stream as separate wires (inspector) to wire it bit by bit.", partsOf(e.getValue()), List.of(e.getKey()), List.of()); continue; }
            if (sender == null || receivers.size() != 1) { problem("error", "stream", "Stream wire " + netLabel(e.getKey()) + " needs one sending stream and one receiving stream; it has " + (sender == null ? 0 : 1) + " and " + receivers.size() + ".", partsOf(e.getValue()), List.of(e.getKey()), List.of()); continue; }
            String[] rcv = receivers.getFirst();
            String a = varOf.get(sender), b = varOf.get(rcv[0]);
            if (a == null || b == null) continue;
            Part sp = parts.get(sender), rp = parts.get(rcv[0]);
            var sb = rawPin(sp, senderPin + "_bits");
            var rb = rawPin(rp, rcv[1] + "_bits");
            int sw = sb.get("width") instanceof Number x ? x.intValue() : 1, rw = rb.get("width") instanceof Number x ? x.intValue() : 1;
            if (sw > rw) { problem("error", "narrowing", "Stream " + rcv[1] + " of " + describe(rp) + " carries " + rw + " bits but receives " + sw + ".", List.of(rcv[0]), List.of(e.getKey()), List.of()); continue; }
            sinks.add(new String[]{rcv[0], b + rawPin(rp, rcv[1] + "_valid").get("path") + ".set(" + a + rawPin(sp, senderPin + "_valid").get("path") + ");"});
            sinks.add(new String[]{rcv[0], b + rb.get("path") + ".set(" + a + sb.get("path") + ");"});
            sinks.add(new String[]{sender, a + rawPin(sp, senderPin + "_ready").get("path") + ".set(" + b + rawPin(rp, rcv[1] + "_ready").get("path") + ");"});
            // the wire's live value: valid, ready and bits, as "net:v", "net:r" and "net:b"
            String sInst = Parts.strParam(sp.params(), "name", sender), rInst = Parts.strParam(rp.params(), "name", rcv[0]);
            netPath.put(e.getKey() + ":v", sInst + "." + senderPin + "_valid");
            netPath.put(e.getKey() + ":r", rInst + "." + rcv[1] + "_ready");
            netPath.put(e.getKey() + ":b", sInst + "." + senderPin + "_bits");
            widths.put(e.getKey(), sw);
        }
    }

    private void emitModuleInputs(Part p) {
        String v = varOf.get(p.id());
        if (v == null) return;
        var st = streams(p.params());
        for (var q : list(p.params().get("pins"))) {
            if (!"in".equals(q.get("dir"))) continue;
            String n = String.valueOf(q.get("name"));
            if (inStream(n, st)) continue;
            Val x = input(p, n);
            if (x == null) continue;
            int w = q.get("width") instanceof Number num ? num.intValue() : 1;
            if (x.width() > w) {
                problem("error", "narrowing", pinText(p.id(), n) + " is " + w + " bits but receives " + x.width() + " bits.", List.of(p.id()), nets(p.id(), n),
                        List.of(fix("Keep the low " + w + " bits", "insert", Map.of("part", p.id(), "pin", n, "type", "resize", "params", Map.of("width", w)))));
                continue;
            }
            sinks.add(new String[]{p.id(), v + q.get("path") + ".set(" + x.expr() + ");"});
        }
    }

    private void emitBlockInputs(Part p) {
        var b = blocks.get(Parts.strParam(p.params(), "block", ""));
        if (b == null) return;
        String v = varOf.get(p.id());
        for (var pin : blockPins(b)) {
            if (pin.dir() != Parts.Dir.IN) continue;
            Val x = input(p, pin.name());
            if (x == null) continue;
            int w = blockPortWidth(b, pin.name());
            if (x.width() > w) {
                problem("error", "narrowing", pinText(p.id(), pin.name()) + " is " + w + " bits but receives " + x.width() + " bits.", List.of(p.id()), nets(p.id(), pin.name()),
                        List.of(fix("Keep the low " + w + " bits", "insert", Map.of("part", p.id(), "pin", pin.name(), "type", "resize", "params", Map.of("width", w)))));
                continue;
            }
            sinks.add(new String[]{p.id(), v + "." + pin.name() + ".set(" + x.expr() + ");"});
        }
    }

    private void emitOut(Part p) {
        int w = width(p);
        String name = Parts.strParam(p.params(), "name", "y");
        if (w == 1) usesBool = true;
        Val v = input(p, "a");
        if (v == null) return;
        if (v.width() > w) {
            problem("error", "narrowing", pinText(p.id(), "a") + " is " + w + " bit" + (w == 1 ? "" : "s") + " but receives " + v.width()
                            + " bits; Tessera never drops bits silently.",
                    List.of(p.id()), nets(p.id(), "a"), List.of(
                            fix("Make the output " + v.width() + " bits", "param", Map.of("part", p.id(), "name", "width", "value", v.width())),
                            fix("Keep the low " + w + " bits", "insert", Map.of("part", p.id(), "pin", "a", "type", "resize", "params", Map.of("width", w)))));
            return;
        }
        if (nested != null) {
            String type = w == 1 ? "Bool" : "UInt";
            fields.add("public final " + type + " " + name + ";");
            decls.add(new String[]{p.id(), name + " = output(\"" + name + "\", " + typeExpr(w) + ");"});
            sinks.add(new String[]{p.id(), "this." + name + ".set(" + v.expr() + ");"});
        } else {
            String java = "output(\"" + name + "\", " + typeExpr(w) + ").set(" + v.expr() + ");";
            sinks.add(new String[]{p.id(), java});
        }
        done.add(p.id());
    }

    private final Map<String, String> memVar = new HashMap<>();

    private void declareMem(Part p) {
        int w = width(p), depth = Math.max(2, Parts.intParam(p.params(), "depth", 16));
        String name = Parts.strParam(p.params(), "name", "m");
        String v = claim(name);
        memVar.put(p.id(), v);
        String init = "";
        var words = words(p, Parts.strParam(p.params(), "init", ""), "initial word");
        if (words == null) return;
        if (words.size() > depth) {
            problem("error", "init-too-long", describe(p) + " has " + words.size() + " initial words but only " + depth + " addresses.", List.of(p.id()), List.of(),
                    List.of(fix("Make it " + Integer.highestOneBit(words.size() - 1) * 2 + " words deep", "param", Map.of("part", p.id(), "name", "depth", "value", Integer.highestOneBit(words.size() - 1) * 2))));
            return;
        }
        for (int k = 0; k < words.size(); k++)
            if (words.get(k).bitLength() > w) {
                problem("error", "init-too-wide", describe(p) + ": word " + k + " (" + words.get(k) + ") needs " + words.get(k).bitLength() + " bits; a word has " + w + ".", List.of(p.id()), List.of(),
                        List.of(fix("Make words " + words.get(k).bitLength() + " bits", "param", Map.of("part", p.id(), "name", "width", "value", words.get(k).bitLength()))));
                return;
            }
        if (!words.isEmpty()) {
            if (w > 63) { problem("error", "init-too-wide", describe(p) + ": initial words are limited to 63-bit memories here.", List.of(p.id()), List.of(), List.of()); return; }
            var lits = new ArrayList<String>();
            for (var x : words) lits.add(litText(x) + (x.bitLength() > 31 ? "L" : ""));
            init = ".init(" + String.join(", ", lits) + ")";
        }
        usesMem = true;
        decls.add(new String[]{p.id(), "Mem<UInt> " + v + " = mem(\"" + name + "\", " + "UInt.w(" + w + "), " + depth + ")" + init + ";"});
        // a synchronous read is a register: its data is there before the logic that makes its address,
        // so a loop through it (an address computed from the data) is not combinational
        if ("sync".equals(Parts.strParam(p.params(), "read", "async")) && pinNet.get(p.id() + ".rdata") != null) {
            String a = claim(name + "_raddr"), rd = claim(name + "_rd");
            decls.add(new String[]{p.id(), "UInt " + a + " = wire(\"" + a + "\", UInt.w(" + addrBits(depth) + "));"});
            decls.add(new String[]{p.id(), "UInt " + rd + " = " + v + ".readSync(" + a + ").named(\"" + rd + "\");"});
            syncAddr.put(p.id(), a);
            varOf.put(p.id(), rd);
            setOut(p.id(), "rdata", new Val(rd, "UInt", w));
            netPath.put(pinNet.get(p.id() + ".rdata"), rd);
        }
    }

    private final Map<String, String> syncAddr = new HashMap<>();

    // ---------------------------------------------------------------- state machines

    record Fsm(List<String> states, List<String> inputs, List<String[]> transitions, List<Map.Entry<String, List<String>>> outputs, List<String> errors) {}

    /** The FSM a part describes; errors are sentences about its text. */
    static Fsm fsmOf(Map<String, Object> params) {
        var errors = new ArrayList<String>();
        var states = new ArrayList<String>();
        for (String st : Parts.strParam(params, "states", "").trim().split("\\s+")) {
            if (st.isEmpty()) continue;
            if (!IDENT.matcher(st).matches()) errors.add("“" + st + "” is not a name for a state.");
            else if (states.contains(st)) errors.add("State " + st + " is listed twice.");
            else states.add(st);
        }
        if (states.isEmpty()) errors.add("A state machine needs at least one state.");
        var inputs = new ArrayList<String>();
        for (String in : Parts.strParam(params, "inputs", "").trim().split("\\s+"))
            if (!in.isEmpty()) {
                if (!IDENT.matcher(in).matches() || JAVA_WORDS.contains(in)) errors.add("“" + in + "” is not a name for an input.");
                else inputs.add(in);
            }
        var trans = new ArrayList<String[]>();
        int ln = 0;
        for (String line : Parts.strParam(params, "transitions", "").split("\\R")) {
            ln++;
            if (line.isBlank() || line.trim().startsWith("//")) continue;
            var m = Pattern.compile("^\\s*(\\w+)\\s*->\\s*(\\w+)\\s*(?::\\s*(.*))?$").matcher(line);
            if (!m.matches()) { errors.add("Transition line " + ln + " should read FROM -> TO : condition."); continue; }
            String from = m.group(1), to = m.group(2), cond = m.group(3) == null || m.group(3).isBlank() ? "1" : m.group(3).trim();
            if (!states.contains(from)) errors.add("Transition line " + ln + ": there is no state " + from + ".");
            if (!states.contains(to)) errors.add("Transition line " + ln + ": there is no state " + to + ".");
            trans.add(new String[]{from, to, cond, Integer.toString(ln)});
        }
        var outs = new ArrayList<Map.Entry<String, List<String>>>();
        for (String line : Parts.strParam(params, "outputs", "").split("\\R")) {
            if (line.isBlank()) continue;
            int c = line.indexOf(':');
            String n = (c < 0 ? line : line.substring(0, c)).trim();
            var in = new ArrayList<String>();
            if (c >= 0) for (String st : line.substring(c + 1).trim().split("[\\s,]+")) if (!st.isEmpty()) {
                if (!states.contains(st)) errors.add("Output " + n + ": there is no state " + st + ".");
                in.add(st);
            }
            if (!IDENT.matcher(n).matches() || n.equals("state") || inputs.contains(n)) errors.add("“" + n + "” cannot name an output.");
            outs.add(Map.entry(n, in));
        }
        return new Fsm(states, inputs, trans, outs, errors);
    }

    private final Map<String, String> fsmVar = new HashMap<>();
    private final Map<String, String> fsmEnum = new HashMap<>();
    private final List<String> enums = new ArrayList<>();

    private void declareFsm(Part p) {
        var f = fsmOf(p.params());
        for (var e : f.errors()) problem("error", "fsm", describe(p) + ": " + e, List.of(p.id()), List.of(), List.of());
        if (!f.errors().isEmpty()) { done.add(p.id()); return; }
        int k = Math.max(1, 32 - Integer.numberOfLeadingZeros(f.states().size() - 1));
        String name = Parts.strParam(p.params(), "name", "fsm");
        String v = claim(name);
        fsmVar.put(p.id(), v);
        // the states are a Java enum; the register holds one of them (encoded by ordinal, the first after reset)
        String en = claimType(Character.toUpperCase(name.charAt(0)) + name.substring(1) + "State");
        fsmEnum.put(p.id(), en);
        enums.add("public enum " + en + " { " + String.join(", ", f.states()) + " }");
        usesEnum = true;
        decls.add(new String[]{p.id(), "HwEnum<" + en + "> " + v + " = reg(\"" + name + "\", HwEnum.of(" + en + ".class), 0);"});
        setOut(p.id(), "state", new Val(v + ".asUInt()", "UInt", k));
        String net = pinNet.get(p.id() + ".state");
        if (net != null) netPath.put(net, name);
        netPath.put("@" + p.id(), name);           // the state, for the drawing, wired or not
        usesBool = true;
        // one Bool per state, named so it shows in the waves: fsm_IDLE ...
        var is = new HashMap<String, String>();
        for (int i = 0; i < f.states().size(); i++) {
            String sv = claim(v + "_" + f.states().get(i));
            is.put(f.states().get(i), sv);
            decls.add(new String[]{p.id(), "Bool " + sv + " = " + v + ".is(" + en + "." + f.states().get(i) + ").named(\"" + sv + "\");"});
        }
        fsmIs.put(p.id(), is);
        for (var o : f.outputs()) {
            var terms = new ArrayList<String>();
            for (String st : o.getValue()) terms.add(is.get(st));
            String expr = terms.isEmpty() ? "Bool.of(false)" : String.join(".or(", terms) + ")".repeat(terms.size() - 1);
            String ov = claim(v + "_" + o.getKey());
            decls.add(new String[]{p.id(), "Bool " + ov + " = " + expr + ".named(\"" + ov + "\");"});
            setOut(p.id(), o.getKey(), new Val(ov, "Bool", 1));
            String on = pinNet.get(p.id() + "." + o.getKey());
            if (on != null) netPath.put(on, ov);
        }
    }

    private final Map<String, Map<String, String>> fsmIs = new HashMap<>();

    private void emitFsmNext(Part p) {
        String v = fsmVar.get(p.id());
        if (v == null) return;
        var f = fsmOf(p.params());
        var ins = new HashMap<String, String>();
        for (String in : f.inputs()) {
            Val x = input(p, in);
            if (x == null) return;
            String b = bool(x, p, in);
            if (b == null) return;
            // each input is a named value of its own (fsm_go), so that the Java says which pin a condition reads
            String iv = claim(v + "_" + in);
            sinks.add(new String[]{p.id(), "Bool " + iv + " = " + b + ".named(\"" + iv + "\");"});
            ins.put(in, iv);
        }
        if (f.transitions().isEmpty()) return;
        usesHw = true;
        var sb = new StringBuilder();
        for (var t : f.transitions()) {
            String cond;
            try { cond = new CondParser(t[2], ins).parse(); }
            catch (IllegalArgumentException e) {
                problem("error", "fsm", describe(p) + ", transition line " + t[3] + ": " + e.getMessage(), List.of(p.id()), List.of(), List.of());
                return;
            }
            String g = fsmIs.get(p.id()).get(t[0]) + (cond.equals("Bool.of(true)") ? "" : ".and(" + cond + ")");
            sb.append(sb.isEmpty() ? "Hw.when(" : "\n                .elseWhen(").append(g).append(", () -> ").append(v)
                    .append(".set(HwEnum.lit(").append(fsmEnum.get(p.id())).append(".").append(t[1]).append(")))");
        }
        sinks.add(new String[]{p.id(), sb + ";"});
    }

    /** Conditions over named 1-bit inputs: ! & | ^ ( ) 1 0, with the usual precedence (! then & then ^ then |). */
    static final class CondParser {
        private final String s;
        private final Map<String, String> names;
        private int i;

        CondParser(String s, Map<String, String> names) { this.s = s; this.names = names; }

        String parse() {
            String e = or();
            skip();
            if (i < s.length()) throw new IllegalArgumentException("unexpected “" + s.substring(i) + "” in the condition.");
            return e;
        }

        private void skip() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }
        private boolean eat(String t) { skip(); if (s.startsWith(t, i)) { i += t.length(); return true; } return false; }

        private String or() { String a = xor(); while (eat("||") || eat("|")) a = a + ".or(" + xor() + ")"; return a; }
        private String xor() { String a = and(); while (eat("^")) a = a + ".xor(" + and() + ")"; return a; }
        private String and() { String a = not(); while (eat("&&") || eat("&")) a = a + ".and(" + not() + ")"; return a; }
        private String not() {
            if (eat("!") || eat("~")) return not() + ".not()";
            if (eat("(")) { String e = or(); if (!eat(")")) throw new IllegalArgumentException("a “(” is not closed."); return e; }
            skip();
            int j = i;
            while (i < s.length() && (Character.isLetterOrDigit(s.charAt(i)) || s.charAt(i) == '_')) i++;
            String w = s.substring(j, i);
            if (w.isEmpty()) throw new IllegalArgumentException(i < s.length() ? "unexpected “" + s.charAt(i) + "”." : "the condition ends too soon.");
            if (w.equals("1") || w.equals("true")) return "Bool.of(true)";
            if (w.equals("0") || w.equals("false")) return "Bool.of(false)";
            String v = names.get(w);
            if (v == null) throw new IllegalArgumentException("there is no input " + w + " (the inputs are " + (names.isEmpty() ? "none" : String.join(", ", names.keySet())) + ").");
            return v;
        }
    }

    private void emitMemReadAddr(Part p) {
        String a = syncAddr.get(p.id());
        if (a == null) return;
        String addr = memAddr(p);
        if (addr != null) sinks.add(new String[]{p.id(), a + ".set(" + addr + ");"});
    }

    private static int addrBits(int depth) { return Math.max(1, 32 - Integer.numberOfLeadingZeros(depth - 1)); }

    /** The address on a memory's addr pin, or null with a problem when it is too wide. */
    private String memAddr(Part p) {
        Val a = input(p, "addr");
        if (a == null) return null;
        int need = addrBits(Math.max(2, Parts.intParam(p.params(), "depth", 16)));
        if (a.width() > need) {
            problem("error", "narrowing", pinText(p.id(), "addr") + " is " + need + " bits (for " + Parts.intParam(p.params(), "depth", 16) + " words) but receives " + a.width() + " bits.",
                    List.of(p.id()), nets(p.id(), "addr"), List.of(fix("Use the low " + need + " bits", "insert", Map.of("part", p.id(), "pin", "addr", "type", "slice", "params", Map.of("hi", need - 1, "lo", 0)))));
            return null;
        }
        return a.expr();   // a Bool is a 1-bit UInt
    }

    private void emitMemWrite(Part p) {
        if ("false".equals(String.valueOf(p.params().getOrDefault("write", true)))) return;
        String m = memVar.get(p.id());
        if (m == null) return;
        Val d = input(p, "wdata"), we = input(p, "we");
        if (d == null || we == null) return;
        String addr = memAddr(p);
        if (addr == null) return;
        int w = width(p);
        if (d.width() > w) {
            problem("error", "narrowing", pinText(p.id(), "wdata") + " is " + w + " bits but receives " + d.width() + " bits.", List.of(p.id()), nets(p.id(), "wdata"),
                    List.of(fix("Make words " + d.width() + " bits", "param", Map.of("part", p.id(), "name", "width", "value", d.width())),
                            fix("Keep the low " + w + " bits", "insert", Map.of("part", p.id(), "pin", "wdata", "type", "resize", "params", Map.of("width", w)))));
            return;
        }
        String c = bool(we, p, "we");
        if (c == null) return;
        String data = d.expr();
        sinks.add(new String[]{p.id(), m + ".write(" + addr + ", " + data + (d.width() < w ? ".zext(" + w + ")" : "") + ", " + c + ");"});
    }

    private static List<String> words(String text) {
        var l = new ArrayList<String>();
        for (String w : text.trim().split("\\s+")) if (!w.isEmpty()) l.add(w);
        return l;
    }

    /** What is wrong with a truth table's text, as a sentence, or null (TruthTable checks it again when it elaborates). */
    static String tableErrors(List<String> ins, List<String> outs, String rows) {
        if (ins.isEmpty()) return "it needs at least one input.";
        if (outs.isEmpty()) return "it needs at least one output.";
        for (String n : ins) if (!IDENT.matcher(n).matches() || JAVA_WORDS.contains(n)) return "“" + n + "” cannot name an input.";
        for (String n : outs) if (!IDENT.matcher(n).matches() || JAVA_WORDS.contains(n) || ins.contains(n)) return "“" + n + "” cannot name an output.";
        if (new HashSet<>(ins).size() < ins.size() || new HashSet<>(outs).size() < outs.size()) return "a name is used twice.";
        var seen = new ArrayList<String[]>();
        var lines = new ArrayList<Integer>();
        int ln = 0;
        for (String line : rows.split("\\R")) {
            ln++;
            if (line.isBlank() || line.trim().startsWith("#")) continue;
            String[] two = line.split("\\|");
            if (two.length != 2) return "row " + ln + " should read inputs | outputs.";
            String[] a = two[0].trim().split("\\s+"), b = two[1].trim().split("\\s+");
            if (a.length != ins.size() || b.length != outs.size()) return "row " + ln + " has " + a.length + " input and " + b.length + " output values; it needs " + ins.size() + " and " + outs.size() + ".";
            for (String x : a) if (!x.matches("[01-]")) return "row " + ln + ": an input value is 0, 1 or -, not “" + x + "”.";
            for (String x : b) if (!x.matches("[01]")) return "row " + ln + ": an output value is 0 or 1, not “" + x + "”.";
            for (int k = 0; k < seen.size(); k++) {
                String[] o = seen.get(k);
                boolean overlap = true;
                for (int i = 0; i < a.length; i++) if (!a[i].equals("-") && !o[i].equals("-") && !a[i].equals(o[i])) { overlap = false; break; }
                if (overlap && !String.join(" ", b).equals(o[ins.size()])) return "rows " + lines.get(k) + " and " + ln + " match the same inputs but give different outputs.";
            }
            var rec = java.util.Arrays.copyOf(a, a.length + 1);
            rec[a.length] = String.join(" ", b);
            seen.add(rec);
            lines.add(ln);
        }
        return null;
    }

    /** Words from text: 0x.., 0b.. or decimal, separated by spaces or commas. */
    private List<BigInteger> words(Part p, String text, String what) {
        var out = new ArrayList<BigInteger>();
        for (String t : text.trim().split("[\\s,;]+")) {
            if (t.isEmpty()) continue;
            BigInteger v = literal(p, t, what);
            if (v == null) return null;
            out.add(v);
        }
        return out;
    }

    private void emitRegNext(Part p) {
        int w = width(p);
        Val d = input(p, "d");
        if (d == null) return;
        if (d.width() > w) {
            problem("error", "narrowing", pinText(p.id(), "d") + " is " + w + " bits but receives " + d.width() + " bits; Tessera never drops bits silently.",
                    List.of(p.id()), nets(p.id(), "d"), List.of(
                            fix("Make the register " + d.width() + " bits", "param", Map.of("part", p.id(), "name", "width", "value", d.width())),
                            fix("Keep the low " + w + " bits", "insert", Map.of("part", p.id(), "pin", "d", "type", "resize", "params", Map.of("width", w)))));
            return;
        }
        String v = varOf.get(p.id());
        if (Boolean.TRUE.equals(p.params().get("enable")) || "true".equals(String.valueOf(p.params().get("enable")))) {
            Val en = input(p, "en");
            if (en == null) return;
            String c = bool(en, p, "en");
            if (c == null) return;
            usesHw = true;
            sinks.add(new String[]{p.id(), "Hw.when(" + c + ", () -> " + v + ".set(" + d.expr() + "));"});
        } else {
            sinks.add(new String[]{p.id(), v + ".set(" + d.expr() + ");"});
        }
    }

    private void emitCheck(Part p) {
        Val c = input(p, "cond");
        if (c == null) return;
        String b = bool(c, p, "cond");
        if (b == null) return;
        usesHw = true;
        String name = Parts.strParam(p.params(), "name", p.id());
        String fn = switch (p.type()) { case "assert" -> "assertThat"; case "assume" -> "assume"; default -> "cover"; };
        sinks.add(new String[]{p.id(), "Hw." + fn + "(" + b + ", \"" + name.replace("\"", "") + "\");"});
        done.add(p.id());
    }

    // ---------------------------------------------------------------- logic

    /** The value on a part's input pin, evaluating its driver first (null when it cannot be had). */
    private Val input(Part p, String pin) {
        String net = pinNet.get(p.id() + "." + pin);
        if (net == null) return null;
        Val v = netVal.get(net);
        if (v != null) return v;
        for (var pp : netPins.get(net)) {
            var pinSpec = pinOf(pp[0], pp[1]);
            if (pinSpec != null && pinSpec.dir() == Parts.Dir.OUT) {
                eval(pp[0]);
                return netVal.get(net);
            }
        }
        return null;
    }

    private void eval(String id) {
        if (done.contains(id)) return;
        Part p = parts.get(id);
        if (visiting.contains(id)) {
            problem("error", "comb-loop", "A loop without a register runs through " + describe(p)
                    + ": its output feeds back to its input in the same cycle. Put a register in the loop.", List.of(id), List.of(), List.of());
            return;
        }
        visiting.add(id);
        try {
            evalPart(p);
        } finally {
            visiting.remove(id);
            done.add(id);
        }
    }

    private void evalPart(Part p) {
        String t = p.type();
        switch (t) {
            case "const" -> {
                BigInteger v = literal(p, Parts.strParam(p.params(), "value", "0"), "value");
                if (v == null) return;
                int w = Parts.intParam(p.params(), "width", 0);
                int need = Math.max(1, v.bitLength());
                if (w == 0) w = need;
                if (need > w) {
                    problem("error", "const-too-wide", describe(p) + " is " + v + ", which needs " + need + " bits; its width is " + w + ".",
                            List.of(p.id()), List.of(), List.of(fix("Make it " + need + " bits", "param", Map.of("part", p.id(), "name", "width", "value", need))));
                    return;
                }
                String lit = v.bitLength() > 62 ? "UInt.lit(0x" + v.toString(16) + "L, " + w + ")" : "UInt.lit(" + litText(v) + ", " + w + ")";
                local(p, "y", lit, "UInt", w, "k");
            }
            case "and", "or", "xor", "nand", "nor", "xnor" -> {
                int n = Parts.intParam(p.params(), "inputs", 2);
                var vals = new ArrayList<Val>();
                for (int k = 0; k < n; k++) {
                    Val v = input(p, "in" + k);
                    if (v == null) return;
                    vals.add(v);
                }
                int w = vals.getFirst().width();
                boolean same = vals.stream().allMatch(v -> v.width() == w);
                if (!same) {
                    int max = vals.stream().mapToInt(Val::width).max().orElse(1);
                    var fixes = new ArrayList<Map<String, Object>>();
                    for (int k = 0; k < n; k++)
                        if (vals.get(k).width() < max)
                            fixes.add(fix("Zero-extend in" + k + " to " + max + " bits", "insert", Map.of("part", p.id(), "pin", "in" + k, "type", "resize", "params", Map.of("width", max))));
                    var ws = new ArrayList<String>();
                    for (int k = 0; k < n; k++) ws.add("in" + k + " is " + vals.get(k).width());
                    problem("error", "width-mismatch", describe(p) + " needs operands of one width; " + String.join(", ", ws) + " bits.",
                            List.of(p.id()), List.of(), fixes);
                    return;
                }
                String base = switch (t) { case "nand" -> "and"; case "nor" -> "or"; case "xnor" -> "xor"; default -> t; };
                boolean allBool = vals.stream().allMatch(v -> v.jtype().equals("Bool"));
                var e = new StringBuilder(vals.getFirst().expr());
                for (int k = 1; k < n; k++) e.append('.').append(base).append('(').append(vals.get(k).expr()).append(')');
                boolean inv = t.equals("nand") || t.equals("nor") || t.equals("xnor");
                String expr = inv ? e + ".not()" : e.toString();
                local(p, "y", expr, allBool ? "Bool" : "UInt", w, t);
            }
            case "not" -> {
                Val a = input(p, "a");
                if (a == null) return;
                local(p, "y", a.expr() + ".not()", a.jtype(), a.width(), "inv");
            }
            case "reduce" -> {
                Val a = input(p, "a");
                if (a == null) return;
                String op = Parts.strParam(p.params(), "op", "or");
                usesBool = true;
                local(p, "y", a.expr() + "." + op + "R()", "Bool", 1, op + "R");
            }
            case "add", "sub" -> {
                Val a = input(p, "a"), b = input(p, "b");
                if (a == null || b == null) return;
                boolean wrap = Parts.strParam(p.params(), "mode", t.equals("add") ? "widen" : "wrap").equals("wrap");
                int w = Math.max(a.width(), b.width()) + (wrap ? 0 : 1);
                String m = t + (wrap ? "Wrap" : "");
                local(p, "y", a.expr() + "." + m + "(" + b.expr() + ")", "UInt", w, t.equals("add") ? "sum" : "diff");
            }
            case "mul" -> {
                Val a = input(p, "a"), b = input(p, "b");
                if (a == null || b == null) return;
                local(p, "y", a.expr() + ".mul(" + b.expr() + ")", "UInt", a.width() + b.width(), "prod");
            }
            case "cmp" -> {
                Val a = input(p, "a"), b = input(p, "b");
                if (a == null || b == null) return;
                String op = Parts.strParam(p.params(), "op", "eq");
                usesBool = true;
                local(p, "y", a.expr() + "." + op + "(" + b.expr() + ")", "Bool", 1, op);
            }
            case "shift" -> {
                Val a = input(p, "a");
                if (a == null) return;
                int n = Parts.intParam(p.params(), "amount", 1);
                boolean left = Parts.strParam(p.params(), "dir", "left").equals("left");
                local(p, "y", a.expr() + (left ? ".shl(" : ".shr(") + n + ")", "UInt", left ? a.width() + n : a.width(), left ? "shl" : "shr");
            }
            case "dshift" -> {
                Val a = input(p, "a"), n = input(p, "n");
                if (a == null || n == null) return;
                boolean left = Parts.strParam(p.params(), "dir", "left").equals("left");
                local(p, "y", a.expr() + (left ? ".dshl(" : ".dshr(") + n.expr() + ")", "UInt", a.width(), left ? "shl" : "shr");
            }
            case "mux" -> {
                int n = Parts.intParam(p.params(), "inputs", 2);
                Val sel = input(p, "sel");
                var vals = new ArrayList<Val>();
                for (int k = 0; k < n; k++) {
                    Val v = input(p, "in" + k);
                    if (v == null) return;
                    vals.add(v);
                }
                if (sel == null) return;
                int w = vals.stream().mapToInt(Val::width).max().orElse(1);
                boolean allBool = vals.stream().allMatch(v -> v.jtype().equals("Bool"));
                usesHw = true;
                if (n == 2) {
                    String c = bool(sel, p, "sel");
                    if (c == null) return;
                    local(p, "y", "Hw.mux(" + c + ", " + vals.get(1).expr() + ", " + vals.get(0).expr() + ")", allBool ? "Bool" : "UInt", w, "sel");
                } else {
                    int need = 32 - Integer.numberOfLeadingZeros(n - 1);
                    if (sel.width() < need)
                        problem("warning", "sel-narrow", pinText(p.id(), "sel") + " has " + sel.width() + " bits, so it reaches only "
                                + (1 << sel.width()) + " of the " + n + " inputs.", List.of(p.id()), nets(p.id(), "sel"), List.of());
                    var items = new ArrayList<String>();
                    for (var v : vals) items.add(w == v.width() || allBool ? v.expr() : v.expr() + ".zext(" + w + ")");
                    local(p, "y", "Hw.index(" + sel.expr() + ", java.util.List.of(" + String.join(", ", items) + "))", allBool ? "Bool" : "UInt", w, "sel");
                }
            }
            case "slice" -> {
                Val a = input(p, "a");
                if (a == null) return;
                int hi = Parts.intParam(p.params(), "hi", 0), lo = Parts.intParam(p.params(), "lo", 0);
                if (hi < lo || hi >= a.width()) {
                    problem("error", "bad-slice", describe(p) + " takes bits " + hi + " down to " + lo + " of a " + a.width() + "-bit value"
                            + (hi < lo ? "; the high bit must not be below the low bit." : "; the highest bit there is " + (a.width() - 1) + "."),
                            List.of(p.id()), nets(p.id(), "a"),
                            hi >= a.width() ? List.of(fix("Take bits " + (a.width() - 1) + " down to " + Math.min(lo, a.width() - 1), "param",
                                    Map.of("part", p.id(), "name", "hi", "value", a.width() - 1))) : List.of());
                    return;
                }
                if (hi == lo) { usesBool = true; local(p, "y", a.expr() + ".bit(" + hi + ")", "Bool", 1, "bit"); }
                else local(p, "y", a.expr() + ".bits(" + hi + ", " + lo + ")", "UInt", hi - lo + 1, "bits");
            }
            case "concat" -> {
                int n = Parts.intParam(p.params(), "inputs", 2);
                var vals = new ArrayList<Val>();
                for (int k = 0; k < n; k++) {
                    Val v = input(p, "in" + k);
                    if (v == null) return;
                    vals.add(v);
                }
                usesHw = true;
                var args = new ArrayList<String>();
                int w = 0;
                for (var v : vals) { args.add(v.expr()); w += v.width(); }
                local(p, "y", "Hw.cat(" + String.join(", ", args) + ")", "UInt", w, "cat");
            }
            case "resize" -> {
                Val a = input(p, "a");
                if (a == null) return;
                int w = width(p);
                String e = a.width() == w ? a.expr() : a.width() < w ? a.expr() + ".zext(" + w + ")" : a.expr() + ".trunc(" + w + ")";
                local(p, "y", e, w == 1 && a.width() == 1 ? a.jtype() : "UInt", w, a.width() < w ? "ext" : "low");
            }
            case "mem" -> {
                String m = memVar.get(p.id());
                if (m == null) return;
                if (pinNet.get(p.id() + ".rdata") == null || syncAddr.containsKey(p.id())) return;   // nobody reads it, or declared already
                String addr = memAddr(p);
                if (addr == null) return;
                local(p, "rdata", m + ".read(" + addr + ")", "UInt", width(p), m + "_rd");
            }
            case "table" -> {
                var ins = words(Parts.strParam(p.params(), "inputs", "a b"));
                var outs = words(Parts.strParam(p.params(), "outputs", "y"));
                var err = tableErrors(ins, outs, Parts.strParam(p.params(), "rows", ""));
                if (err != null) { problem("error", "table", describe(p) + ": " + err, List.of(p.id()), List.of(), List.of()); return; }
                var args = new ArrayList<String>();
                for (String in : ins) {
                    Val x = input(p, in);
                    if (x == null) return;
                    String b = bool(x, p, in);
                    if (b == null) return;
                    args.add("\"" + in + "\", " + b);
                }
                usesTable = usesBool = true;
                String tv = claim(Parts.strParam(p.params(), "name", "table"));
                var text = new StringBuilder("TruthTable.parse(\"\"\"\n");
                text.append("                ").append(String.join(" ", ins)).append(" | ").append(String.join(" ", outs)).append("\n");
                for (String line : Parts.strParam(p.params(), "rows", "").split("\\R"))
                    if (!line.isBlank()) text.append("                ").append(line.trim().replaceAll("\\s+", " ")).append("\n");
                text.append("                \"\"\").apply(") .append(ins.size() <= 10 ? "Map.of(" + String.join(", ", args) + ")" : "Map.ofEntries(" + String.join(", ", args.stream().map(a -> "Map.entry(" + a + ")").toList()) + ")").append(")");
                body.add(new String[]{p.id(), "Map<String, Bool> " + tv + " = " + text + ";"});
                varOf.put(p.id(), tv);
                for (String o : outs) {
                    String net = pinNet.get(p.id() + "." + o);
                    String nm = net != null && netName.containsKey(net) ? netName.get(net) : o;
                    String v = claim(nm);
                    body.add(new String[]{p.id(), "Bool " + v + " = " + tv + ".get(\"" + o + "\").named(\"" + v + "\");"});
                    setOut(p.id(), o, new Val(v, "Bool", 1));
                    if (net != null) netPath.put(net, v);
                }
            }
            case "counter" -> {
                Val inc = input(p, "inc");
                if (inc == null) return;
                String c = bool(inc, p, "inc");
                if (c == null) return;
                int max = Math.max(1, Parts.intParam(p.params(), "max", 9));
                usesUtil = usesBool = true;
                String cv = claim("cnt");
                body.add(new String[]{p.id(), "Util.Count " + cv + " = Util.counter(" + max + ", " + c + ");"});
                varOf.put(p.id(), cv);
                outLocal(p, "value", cv + ".value()", "UInt", 32 - Integer.numberOfLeadingZeros(max), "count");
                outLocal(p, "wrap", cv + ".wrap()", "Bool", 1, "wrap");
            }
            case "edge", "debounce" -> {
                Val a = input(p, "a");
                if (a == null) return;
                String b = bool(a, p, "a");
                if (b == null) return;
                usesUtil = true;
                String e = t.equals("edge") ? "Util." + ("falling".equals(Parts.strParam(p.params(), "kind", "rising")) ? "fallingEdge(" : "risingEdge(") + b + ")"
                        : "Util.debounce(" + b + ", " + Math.max(1, Parts.intParam(p.params(), "cycles", 1000)) + ")";
                local(p, "y", e, "Bool", 1, t.equals("edge") ? Parts.strParam(p.params(), "kind", "rising") : "debounced");
            }
            case "delay" -> {
                Val a = input(p, "a");
                if (a == null) return;
                usesUtil = true;
                local(p, "y", "Util.delay(" + a.expr() + ", " + Math.max(1, Parts.intParam(p.params(), "cycles", 2)) + ")", a.jtype(), a.width(), "delayed");
            }
            case "sync" -> {
                Val a = input(p, "a");
                if (a == null) return;
                String b = bool(a, p, "a");
                if (b == null) return;
                usesHw = usesBool = true;
                local(p, "y", "Hw.synchronize(" + b + ", " + Math.max(2, Parts.intParam(p.params(), "stages", 2)) + ")", "Bool", 1, "synced");
            }
            case "lfsr" -> {
                Val st = input(p, "step");
                if (st == null) return;
                String b = bool(st, p, "step");
                if (b == null) return;
                int w = Math.max(2, Math.min(32, Parts.intParam(p.params(), "width", 8)));
                usesUtil = true;
                local(p, "y", "Util.lfsr(" + w + ", " + b + ")", "UInt", w, "lfsr");
            }
            case "bitop" -> {
                Val a = input(p, "a");
                if (a == null) return;
                String op = Parts.strParam(p.params(), "op", "popCount");
                if (!Set.of("popCount", "reverse", "toGray", "fromGray").contains(op)) op = "popCount";
                int w = op.equals("popCount") ? Math.max(1, 32 - Integer.numberOfLeadingZeros(a.width())) : a.width();
                usesUtil = true;
                local(p, "y", "Util." + op + "(" + a.expr() + ")", w == 1 && !op.equals("popCount") ? a.jtype() : "UInt", w, op.equals("popCount") ? "ones" : op);
            }
            case "onehot" -> {
                Val a = input(p, "idx");
                if (a == null) return;
                int n = Math.max(2, Parts.intParam(p.params(), "n", 8));
                usesUtil = true;
                local(p, "y", "Util.oneHot(" + a.expr() + ", " + n + ")", "UInt", n, "onehot");
            }
            case "prienc" -> {
                Val a = input(p, "a");
                if (a == null) return;
                usesUtil = usesBool = true;
                String pv = claim("pe");
                body.add(new String[]{p.id(), "Valid<UInt> " + pv + " = Util.priorityEncoder(" + a.expr() + ");"});
                usesValid = true;
                varOf.put(p.id(), pv);
                outLocal(p, "index", pv + ".bits()", "UInt", Math.max(1, 32 - Integer.numberOfLeadingZeros(a.width() - 1)), "index");
                outLocal(p, "valid", pv + ".valid()", "Bool", 1, "valid");
            }
            case "blink", "every" -> {
                double s = p.params().get("seconds") instanceof Number num ? num.doubleValue() : t.equals("blink") ? 0.5 : 1.0;
                long ms = Math.max(1, Math.round(s * 1000));
                usesClock = usesDuration = usesTiming = usesBool = true;
                local(p, "y", "Timing." + t + "(clk, Duration.ofMillis(" + ms + "))", "Bool", 1, t);
            }
            default -> problem("error", "unsupported", describe(p) + " (" + t + ") cannot be generated yet.", List.of(p.id()), List.of(), List.of());
        }
    }

    /** {@code v} as a Bool for a condition or select, or null with a problem when it is wider than one bit. */
    private String bool(Val v, Part p, String pin) {
        if (v.jtype().equals("Bool")) return v.expr();
        if (v.width() == 1) return v.expr() + ".bit(0)";
        problem("error", "not-one-bit", pinText(p.id(), pin) + " needs one bit, but receives " + v.width() + " bits.",
                List.of(p.id()), nets(p.id(), pin), List.of(
                        fix("Use any bit set (orR)", "insert", Map.of("part", p.id(), "pin", pin, "type", "reduce", "params", Map.of("op", "or"))),
                        fix("Use bit 0", "insert", Map.of("part", p.id(), "pin", pin, "type", "slice", "params", Map.of("hi", 0, "lo", 0)))));
        return null;
    }

    /** Declare a local for a part's output pin and record the net's value. */
    private void local(Part p, String pin, String expr, String jtype, int width, String hint) {
        String net = pinNet.get(p.id() + "." + pin);
        String name = net != null && netName.containsKey(net) ? netName.get(net) : p.label() != null && !p.label().isBlank() ? p.label() : hint;
        String v = claim(name);
        varOf.put(p.id(), v);
        // every value is named after its local, so the drawing's wire can show it live and in the waves
        String decl = jtype + " " + v + " = " + expr + ".named(\"" + v + "\");";
        body.add(new String[]{p.id(), decl});
        if (jtype.equals("Bool")) usesBool = true;
        setOut(p.id(), pin, new Val(v, jtype, width));
        if (net != null) netPath.put(net, v);
    }

    /** A named local for one of several output pins (unlike local, it leaves the part's Java name alone). */
    private void outLocal(Part p, String pin, String expr, String jtype, int width, String hint) {
        String net = pinNet.get(p.id() + "." + pin);
        if (net == null) return;
        String v = claim(netName.getOrDefault(net, hint));
        body.add(new String[]{p.id(), jtype + " " + v + " = " + expr + ".named(\"" + v + "\");"});
        if (jtype.equals("Bool")) usesBool = true;
        setOut(p.id(), pin, new Val(v, jtype, width));
        netPath.put(net, v);
    }

    private void setOut(String part, String pin, Val v) {
        String net = pinNet.get(part + "." + pin);
        if (net != null) netVal.put(net, v);
    }

    // ---------------------------------------------------------------- rendering

    /** A block as a public static nested module class. */
    private String renderNested(String cls) {
        var out = new ArrayList<String>();
        out.add("");
        String title = doc.get("title") instanceof String t && !t.isBlank() ? t : cls;
        out.add("    /** " + title.replace("*/", "* /") + " (a block of the drawing). */");
        out.add("    public static class " + cls + " extends Module {");
        for (var e : enums) out.add("        " + e);
        for (var f : fields) out.add("        " + f);
        if (!fields.isEmpty() || !enums.isEmpty()) out.add("");
        out.add("        public " + cls + "(" + (usesClock ? "Clock clk" : "") + ") {");
        for (var group : List.of(decls, body, sinks)) {
            if (group.isEmpty()) continue;
            if (group != decls) out.add("");
            for (var d : group) out.add("            " + d[1]);
        }
        out.add("        }");
        out.add("    }");
        return String.join("\n", out);
    }

    private record Rendered(String java, Map<String, List<Integer>> lines) {}

    private Rendered render(String cls, String pkg) {
        var out = new ArrayList<String>();
        var lines = new LinkedHashMap<String, List<Integer>>();
        out.add("package " + pkg + ";");
        out.add("");
        if (usesDuration) out.add("import java.time.Duration;");
        if (usesTable) out.add("import java.util.Map;");
        if (usesBool) out.add("import org.eninehq.tessera.core.Bool;");
        if (usesDomain) out.add("import org.eninehq.tessera.core.ClockDomain;");
        if (usesHw) out.add("import org.eninehq.tessera.core.Hw;");
        if (usesEnum) out.add("import org.eninehq.tessera.core.HwEnum;");
        if (usesMem) out.add("import org.eninehq.tessera.core.Mem;");
        out.add("import org.eninehq.tessera.core.Module;");
        out.add("import org.eninehq.tessera.core.UInt;");
        if (usesClock) out.add("import org.eninehq.tessera.stdlib.Clock;");
        if (usesTiming) out.add("import org.eninehq.tessera.stdlib.Timing;");
        if (usesTable) out.add("import org.eninehq.tessera.stdlib.TruthTable;");
        if (usesUtil) out.add("import org.eninehq.tessera.stdlib.Util;");
        for (String i : extraImports) out.add("import " + i + ";");
        if (codeImports) for (String i : Modules.CODE_IMPORTS) if (!i.endsWith(".Module") && !out.contains("import " + i + ";")) out.add("import " + i + ";");
        if (usesValid) out.add("import org.eninehq.tessera.stdlib.Valid;");
        out.add("");
        String title = doc.get("title") instanceof String s && !s.isBlank() ? s : cls;
        out.add("/** " + title.replace("*/", "* /") + " (drawn in Tessera Studio). */");
        out.add("public class " + cls + " extends Module {");
        for (var e : enums) out.add("    " + e);
        if (!enums.isEmpty()) out.add("");
        out.add("    public " + cls + "(" + (usesClock ? "Clock clk" : "") + ") {");
        for (var group : List.of(decls, body, sinks)) {
            if (group.isEmpty()) continue;
            if (group != decls) out.add("");
            for (var d : group) {
                lines.computeIfAbsent(d[0], k -> new ArrayList<>()).add(out.size() + 1);
                out.add("        " + d[1]);
            }
        }
        out.add("    }");
        for (var nc : nestedClasses) out.addAll(List.of(nc.split("\n", -1)));
        out.add("}");
        out.add(MARK + embed(doc));
        return new Rendered(String.join("\n", out) + "\n", lines);
    }

    // ---------------------------------------------------------------- helpers

    private int width(Part p) {
        int w = Parts.intParam(p.params(), "width", 1);
        if (w < 1 || w > Parts.MAX_WIDTH) {
            problem("error", "bad-width", describe(p) + " has width " + w + "; widths go from 1 to " + Parts.MAX_WIDTH + ".", List.of(p.id()), List.of(), List.of());
            return 1;
        }
        return w;
    }

    private static String typeExpr(int w) {
        return w == 1 ? "Bool.TYPE" : "UInt.w(" + w + ")";
    }

    private static String litText(BigInteger v) {
        return v.bitLength() > 31 ? v + "L" : v.toString();
    }

    private BigInteger literal(Part p, String text, String what) {
        String t = text.trim().replace("_", "");
        try {
            if (t.startsWith("0x") || t.startsWith("0X")) return new BigInteger(t.substring(2), 16);
            if (t.startsWith("0b") || t.startsWith("0B")) return new BigInteger(t.substring(2), 2);
            var v = new BigInteger(t);
            if (v.signum() < 0) throw new NumberFormatException();
            return v;
        } catch (NumberFormatException | StringIndexOutOfBoundsException e) {
            problem("error", "bad-number", describe(p) + " has the " + what + " '" + text + "', which is not a non-negative number (decimal, 0x… or 0b…).",
                    List.of(p.id()), List.of(), List.of());
            return null;
        }
    }

    /** A type name: upper-case first, unique among the names used. */
    private String claimType(String wanted) {
        String base = javaName(wanted, "T");
        String v = base;
        for (int k = 1; usedNames.contains(v); k++) v = base + k;
        usedNames.add(v);
        return v;
    }

    /** A free Java identifier based on {@code wanted}. */
    private String claim(String wanted) {
        String base = javaName(wanted, "v");
        // locals start lower-case unless the name is an acronym (CLK, A0)
        if (Character.isUpperCase(base.charAt(0)) && (base.length() == 1 || Character.isLowerCase(base.charAt(1))))
            base = Character.toLowerCase(base.charAt(0)) + base.substring(1);
        String v = base;
        for (int k = 1; usedNames.contains(v); k++) v = base + k;
        usedNames.add(v);
        return v;
    }

    static String javaName(String s, String dflt) {
        String t = s.replaceAll("[^A-Za-z0-9_]", "_");
        if (t.isEmpty()) t = dflt;
        if (Character.isDigit(t.charAt(0))) t = "_" + t;
        return t;
    }

    /**
     * The streams of a module part: X for every X_valid, X_ready and X_bits among its pins, shown as one pin
     * when the part has {@code bundle} set, unless it lists X in {@code unbundle}. Its direction is that of
     * X_valid (out: the module sends).
     */
    static Map<String, String> streams(Map<String, Object> params) {
        // only parts placed with streams bundled (a drawing saved before keeps its separate pins)
        if (!Boolean.TRUE.equals(params.get("bundle")) && !"true".equals(String.valueOf(params.get("bundle")))) return Map.of();
        var dir = new HashMap<String, String>();
        for (var q : list(params.get("pins"))) dir.put(String.valueOf(q.get("name")), String.valueOf(q.get("dir")));
        var unb = new HashSet<String>();
        if (params.get("unbundle") instanceof List<?> l) for (Object o : l) unb.add(String.valueOf(o));
        var out = new LinkedHashMap<String, String>();
        for (var e : dir.entrySet()) {
            if (!e.getKey().endsWith("_valid")) continue;
            String x = e.getKey().substring(0, e.getKey().length() - 6), v = e.getValue();
            String r = dir.get(x + "_ready"), b = dir.get(x + "_bits");
            if (r == null || b == null || unb.contains(x) || r.equals(v) || !b.equals(v)) continue;   // valid and bits one way, ready the other
            out.put(x, v);
        }
        return out;
    }

    private List<Parts.Pin> pinsOf(Part p) {
        if (p.type().equals("module") || p.type().equals("code")) {
            var l = new ArrayList<Parts.Pin>();
            var st = streams(p.params());
            for (var q : list(p.params().get("pins"))) {
                String n = String.valueOf(q.get("name"));
                String base = n.replaceFirst("_(valid|ready|bits)$", "");
                if (!base.equals(n) && st.containsKey(base)) {
                    if (n.endsWith("_valid")) l.add("in".equals(st.get(base)) ? Parts.Pin.in(base, base) : Parts.Pin.out(base, base));
                    continue;
                }
                l.add("in".equals(q.get("dir")) ? Parts.Pin.in(n, n) : Parts.Pin.out(n, n));
            }
            return l;
        }
        if (p.type().equals("block")) {
            var b = blocks.get(Parts.strParam(p.params(), "block", ""));
            return b == null ? List.of() : blockPins(b);
        }
        return Parts.pins(p.type(), p.params());
    }

    private Parts.Pin pinOf(String part, String pin) {
        var p = parts.get(part);
        if (p == null) return null;
        for (var s : pinsOf(p)) if (s.name().equals(pin)) return s;
        return null;
    }

    private String describe(Part p) {
        var t = Parts.type(p.type());
        String n = Set.of("in", "out", "reg").contains(p.type()) ? " '" + Parts.strParam(p.params(), "name", "") + "'"
                : p.label() != null && !p.label().isBlank() ? " '" + p.label() + "'" : " " + p.id();
        return (t == null ? p.type() : t.title()) + n;
    }

    private String pinText(String part, String pin) {
        var p = parts.get(part);
        return p == null ? part + "." + pin : describe(p) + " pin " + pin;
    }

    private String pinList(List<String[]> pins) {
        var l = new ArrayList<String>();
        for (var pp : pins) l.add(pinText(pp[0], pp[1]));
        return String.join(", ", l);
    }

    private String netLabel(String net) {
        return netName.containsKey(net) ? "'" + netName.get(net) + "'" : net;
    }

    private static List<String> partsOf(List<String[]> pins) {
        var s = new LinkedHashSet<String>();
        for (var pp : pins) s.add(pp[0]);
        return new ArrayList<>(s);
    }

    private List<String> nets(String part, String pin) {
        String n = pinNet.get(part + "." + pin);
        return n == null ? List.of() : List.of(n);
    }

    private void problem(String sev, String code, String msg, List<String> ps, List<String> ns, List<Map<String, Object>> fixes) {
        problems.add(new Problem(sev, code, msg, ps, ns, fixes));
        for (var p : ps) blame.computeIfAbsent(p, k -> new HashSet<>()).add(code);
    }

    private static Map<String, Object> fix(String label, String op, Map<String, Object> args) {
        var m = new LinkedHashMap<String, Object>();
        m.put("label", label);
        m.put("op", op);
        m.putAll(args);
        return m;
    }

    // ---------------------------------------------------------------- the embedded drawing

    /** The drawing compressed into one comment-safe line (raw deflate, then base64url). */
    public static String embed(Map<String, Object> doc) {
        byte[] in = Json.write(doc).getBytes(StandardCharsets.UTF_8);
        var d = new Deflater(Deflater.BEST_COMPRESSION, true);
        d.setInput(in);
        d.finish();
        var out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        while (!d.finished()) out.write(buf, 0, d.deflate(buf));
        d.end();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(out.toByteArray());
    }

    /** The drawing embedded in a Java source by {@link #generate}, or null when there is none. */
    public static Map<String, Object> embedded(String java) {
        int at = java.lastIndexOf(MARK);
        if (at < 0) return null;
        int end = java.indexOf('\n', at);
        String b64 = java.substring(at + MARK.length(), end < 0 ? java.length() : end).trim();
        try {
            byte[] z = Base64.getUrlDecoder().decode(b64);
            var inf = new Inflater(true);
            inf.setInput(z);
            var out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            while (!inf.finished()) {
                int n = inf.inflate(buf);
                if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break;
                out.write(buf, 0, n);
            }
            inf.end();
            return Json.object(out.toString(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalArgumentException("the drawing in this file is damaged: " + e.getMessage());
        }
    }
}
