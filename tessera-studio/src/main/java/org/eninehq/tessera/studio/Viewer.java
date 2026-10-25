package org.eninehq.tessera.studio;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.eninehq.tessera.core.Elaborated;
import org.eninehq.tessera.ir.Expr;
import org.eninehq.tessera.ir.Instance;
import org.eninehq.tessera.ir.MemDef;
import org.eninehq.tessera.ir.ModuleDef;
import org.eninehq.tessera.ir.OpKind;
import org.eninehq.tessera.ir.Signal;

/**
 * Draws an elaborated design that was written in Java, at the level of its signals: every port,
 * register, named wire, memory and child instance is a part; the logic that drives a signal is one
 * box titled with its expression, whose inputs are the signals it reads. Signals the code did not
 * name and that only one expression reads are folded into that expression. Every wire of the
 * drawing is a signal of the simulated design, so the whole drawing shows live values.
 */
public final class Viewer {
    private final ModuleDef m;
    private final Map<String, Object> doc = new LinkedHashMap<>();
    private final List<Object> parts = new ArrayList<>();
    private final Map<String, List<List<String>>> nets = new LinkedHashMap<>();
    private final Map<String, String> netPaths = new LinkedHashMap<>();
    private final Map<String, Integer> netWidths = new LinkedHashMap<>();
    private final Map<Signal, Integer> readers = new IdentityHashMap<>();
    private final Set<Signal> inlined = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
    private final String prefix;
    private int next = 1;

    private Viewer(ModuleDef m, String prefix) {
        this.m = m;
        this.prefix = prefix;
    }

    /** The drawing of {@code e}'s top module: {@code {doc, netPaths, netWidths}}. */
    public static Map<String, Object> view(Elaborated<?> e, String title) {
        var v = new Viewer(e.circuit().top(), "");
        return v.build(title);
    }

    private Map<String, Object> build(String title) {
        countReaders();
        for (var s : m.allSignals()) {
            if (s.isClockOrReset()) continue;
            switch (s.kind()) {
                case INPUT -> port("in", s);
                case OUTPUT -> { String id = port("out", s); feed(s.driver(), id, "a", s); }
                case REG -> {
                    String id = part("reg", Map.of("name", s.name(), "width", s.width(), "init", s.resetValue() == null ? "0" : s.resetValue().toString()),
                            List.of(pin("d", "in", "l", "D"), pin("q", "out", "r", "Q")));
                    connect(netOf(s), id, "q");
                    feed(s.driver(), id, "d", s);
                }
                case WIRE, NODE -> {
                    if (inlined.contains(s) || s.driver() == null) continue;
                    exprBox(s.driver(), netOf(s), s.name());
                }
            }
        }
        for (var mem : m.mems()) memBox(mem);
        for (var inst : m.instances()) instBox(inst);
        doc.put("tessera_studio", 1);
        doc.put("view", true);
        doc.put("name", m.name());
        doc.put("title", title);
        doc.put("parts", parts);
        var nl = new ArrayList<Object>();
        for (var e : nets.entrySet()) {
            if (e.getValue().size() < 2) continue;
            var n = new LinkedHashMap<String, Object>();
            n.put("id", e.getKey());
            n.put("pins", e.getValue());
            nl.add(n);
        }
        doc.put("nets", nl);
        doc.put("notes", List.of());
        var out = new LinkedHashMap<String, Object>();
        out.put("doc", doc);
        out.put("netPaths", netPaths);
        out.put("netWidths", netWidths);
        return out;
    }

    /** How often each signal is read, to decide which unnamed ones to fold into their reader. */
    private void countReaders() {
        var seen = java.util.Collections.newSetFromMap(new IdentityHashMap<Expr, Boolean>());
        var visit = new java.util.function.Consumer<Expr>() {
            @Override public void accept(Expr e) {
                if (e == null || !seen.add(e)) return;
                switch (e) {
                    case Expr.Ref r -> readers.merge(r.signal(), 1, Integer::sum);
                    case Expr.Op o -> o.args().forEach(this);
                    case Expr.MemRead mr -> accept(mr.addr());
                    default -> { }
                }
            }
        };
        for (var s : m.allSignals()) visit.accept(s.driver());
        for (var mem : m.mems()) for (var w : mem.writes()) { visit.accept(w.addr()); visit.accept(w.data()); visit.accept(w.en()); }
        for (var inst : m.instances()) inst.inputs().values().forEach(visit);
        for (var s : m.allSignals())
            if ((s.kind() == Signal.Kind.NODE || s.kind() == Signal.Kind.WIRE) && s.autoNamed() && readers.getOrDefault(s, 0) <= 1 && s.driver() != null)
                inlined.add(s);
    }

    // ---------------------------------------------------------------- parts

    private String port(String type, Signal s) {
        String id = part(type, Map.of("name", s.name(), "width", s.width()),
                List.of(type.equals("in") ? pin("y", "out", "r", "") : pin("a", "in", "l", "")));
        if (type.equals("in")) connect(netOf(s), id, "y");
        return id;
    }

    /** Wire the value of {@code e} into pin {@code pin} of part {@code id}: directly for a signal, else through an expression box. */
    private void feed(Expr e, String id, String pin, Signal target) {
        if (e == null) return;
        if (e instanceof Expr.Ref r && !inlined.contains(r.signal())) { connect(netOf(r.signal()), id, pin); return; }
        String net = "x" + next++;
        netWidths.put(net, e.width());
        exprBox(e, net, target == null ? null : target.name() + " next");
        connect(net, id, pin);
    }

    private void exprBox(Expr e, String outNet, String name) {
        var ins = new LinkedHashSet<Signal>();
        var mems = new LinkedHashSet<String>();
        collect(e, ins, mems);
        left = EXPR_CHARS;
        String text = print(e, 0);
        var pins = new ArrayList<Map<String, Object>>();
        int k = 0;
        var pinNames = new ArrayList<String>();
        for (var s : ins) { String pn = "i" + k++; pins.add(pin(pn, "in", "l", short_(s.name()))); pinNames.add(pn); }
        pins.add(pin("y", "out", "r", ""));
        String title = text.length() > 30 ? text.substring(0, 28) + "…" : text;
        var params = new LinkedHashMap<String, Object>();
        params.put("title", title);
        params.put("expr", text);
        if (name != null) params.put("signal", name);
        params.put("width", e.width());
        String id = part("expr", params, pins);
        k = 0;
        for (var s : ins) connect(netOf(s), id, pinNames.get(k++));
        connect(outNet, id, "y");
    }

    private void memBox(MemDef mem) {
        var pins = new ArrayList<Map<String, Object>>();
        var feeds = new ArrayList<Object[]>();
        int k = 0;
        for (var w : mem.writes()) {
            pins.add(pin("wa" + k, "in", "l", "addr" + (mem.writes().size() > 1 ? k : "")));
            pins.add(pin("wd" + k, "in", "l", "data" + (mem.writes().size() > 1 ? k : "")));
            pins.add(pin("we" + k, "in", "l", "we" + (mem.writes().size() > 1 ? k : "")));
            feeds.add(new Object[]{w.addr(), "wa" + k});
            feeds.add(new Object[]{w.data(), "wd" + k});
            feeds.add(new Object[]{w.en(), "we" + k});
            k++;
        }
        String id = part("mem", Map.of("title", mem.name() + " " + mem.depth() + "×" + mem.width(), "name", mem.name(), "depth", mem.depth(), "width", mem.width()), pins);
        for (var f : feeds) feed((Expr) f[0], id, (String) f[1], null);
    }

    private void instBox(Instance inst) {
        var pins = new ArrayList<Map<String, Object>>();
        for (var p : inst.def().ports())
            if (!p.isClockOrReset()) pins.add(pin(p.name(), p.kind() == Signal.Kind.INPUT ? "in" : "out", p.kind() == Signal.Kind.INPUT ? "l" : "r", p.name()));
        String id = part("inst", Map.of("title", inst.name() + ": " + inst.def().name(), "name", inst.name()), pins);
        for (var e : inst.inputs().entrySet()) feed(e.getValue(), id, e.getKey().name(), null);
        for (var p : inst.def().ports())
            if (p.kind() == Signal.Kind.OUTPUT) {
                String net = "inst:" + inst.name() + "." + p.name();
                netPaths.put(net, prefix + inst.name() + "." + p.name());
                netWidths.put(net, p.width());
                connect(net, id, p.name());
            }
    }

    private String part(String type, Map<String, Object> params, List<Map<String, Object>> pins) {
        String id = "v" + next++;
        var p = new LinkedHashMap<String, Object>();
        p.put("id", id);
        p.put("type", type);
        p.put("x", 0);
        p.put("y", 0);
        p.put("rot", 0);
        p.put("mir", false);
        p.put("params", new LinkedHashMap<>(params));
        p.put("pins", pins);
        p.put("ro", true);
        parts.add(p);
        return id;
    }

    private static Map<String, Object> pin(String name, String dir, String side, String label) {
        var m = new LinkedHashMap<String, Object>();
        m.put("name", name);
        m.put("dir", dir);
        m.put("side", side);
        m.put("label", label);
        return m;
    }

    private String netOf(Signal s) {
        String id = "s:" + s.name();
        if (!nets.containsKey(id)) {
            nets.put(id, new ArrayList<>());
            netPaths.put(id, prefix + s.name());
            netWidths.put(id, s.width());
        }
        return id;
    }

    private void connect(String net, String part, String pin) {
        nets.computeIfAbsent(net, k -> new ArrayList<>()).add(List.of(part, pin));
    }

    // ---------------------------------------------------------------- expressions

    private void collect(Expr e, Set<Signal> ins, Set<String> mems) {
        switch (e) {
            case Expr.Ref r -> { if (inlined.contains(r.signal())) collect(r.signal().driver(), ins, mems); else ins.add(r.signal()); }
            case Expr.Op o -> o.args().forEach(a -> collect(a, ins, mems));
            case Expr.MemRead mr -> { mems.add(mr.mem().name()); collect(mr.addr(), ins, mems); }
            default -> { }
        }
    }

    private static String short_(String name) {
        return name.length() > 14 ? name.substring(0, 13) + "…" : name;
    }

    /**
     * A compact Java-like rendering of an expression, for box titles and the inspector. Shared sub-expressions of a large DAG would repeat without end
     * when printed as a tree, so each expression gets a budget of characters; past it, the rest is "…".
     */
    static final int EXPR_CHARS = 4000;
    private int left = EXPR_CHARS;

    String print(Expr e, int depth) {
        if (depth > 40 || left <= 0) return "…";
        return switch (e) {
            case Expr.Lit l -> spend(l.value().compareTo(BigInteger.valueOf(9)) <= 0 ? l.value().toString() : "0x" + l.value().toString(16));
            case Expr.Ref r -> inlined.contains(r.signal()) ? print(r.signal().driver(), depth + 1) : spend(r.signal().name());
            case Expr.InstRef ir -> spend(ir.instance().name() + "." + ir.port().name());
            case Expr.MemRead mr -> mr.mem().name() + "[" + print(mr.addr(), depth + 1) + "]";
            case Expr.Op o -> { left -= 3; yield op(o, depth); }
        };
    }

    private String spend(String s) { left -= s.length(); return s; }

    private String op(Expr.Op o, int d) {
        var a = o.args();
        java.util.function.IntFunction<String> arg = i -> {
            Expr x = a.get(i);
            String s = print(x, d + 1);
            return x instanceof Expr.Op xo && xo.kind() != OpKind.SLICE && xo.kind() != OpKind.CAT && xo.kind() != OpKind.EXT && xo.kind() != OpKind.CAST ? "(" + s + ")" : s;
        };
        return switch (o.kind()) {
            case ADD -> arg.apply(0) + " + " + arg.apply(1);
            case SUB -> arg.apply(0) + " - " + arg.apply(1);
            case MUL -> arg.apply(0) + " * " + arg.apply(1);
            case AND, OR, XOR -> {
                String sym = o.kind() == OpKind.AND ? " & " : o.kind() == OpKind.OR ? " | " : " ^ ";
                var parts = new ArrayList<String>();
                for (int i = 0; i < a.size(); i++) parts.add(arg.apply(i));
                yield String.join(sym, parts);
            }
            case NOT -> "~" + arg.apply(0);
            case SHL -> arg.apply(0) + " << " + o.p0();
            case SHR -> arg.apply(0) + " >> " + o.p0();
            case SRA -> arg.apply(0) + " >>> " + o.p0();
            case DSHL -> arg.apply(0) + " << " + arg.apply(1);
            case DSHR -> arg.apply(0) + " >> " + arg.apply(1);
            case DSRA -> arg.apply(0) + " >>> " + arg.apply(1);
            case EQ -> arg.apply(0) + " == " + arg.apply(1);
            case NE -> arg.apply(0) + " != " + arg.apply(1);
            case LT -> arg.apply(0) + " < " + arg.apply(1);
            case LE -> arg.apply(0) + " <= " + arg.apply(1);
            case GT -> arg.apply(0) + " > " + arg.apply(1);
            case GE -> arg.apply(0) + " >= " + arg.apply(1);
            case MUX -> arg.apply(0) + " ? " + arg.apply(1) + " : " + arg.apply(2);
            case CAT -> {
                var parts = new ArrayList<String>();
                for (int i = 0; i < a.size(); i++) parts.add(print(a.get(i), d + 1));
                yield "{" + String.join(", ", parts) + "}";
            }
            case SLICE -> arg.apply(0) + (o.p0() == o.p1() ? "[" + o.p0() + "]" : "[" + o.p0() + ":" + o.p1() + "]");
            case EXT -> print(a.get(0), d + 1);
            case CAST -> print(a.get(0), d + 1);
            case ANDR -> "&" + arg.apply(0);
            case ORR -> "|" + arg.apply(0);
            case XORR -> "^" + arg.apply(0);
        };
    }
}
