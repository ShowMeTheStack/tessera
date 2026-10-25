package org.eninehq.tessera.studio;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.regex.Pattern;
import org.eninehq.tessera.ir.ModuleDef;
import org.eninehq.tessera.ir.Signal;

/**
 * The pipeline diagram: which instruction was in which stage in each cycle, as Konata draws it.
 *
 * <p>It is read from signals that Tessera's pipelines name already, so no design has to add anything.
 * A pipeline {@code P} has a hold wire {@code P_hold_<S>} for each stage {@code S} in order, a valid
 * register {@code P_valid_<S>} for each stage after the first, and copies {@code <pc>_<S>} of the
 * program counter it takes in stage 0 ({@code P_pc}, or a register {@code pc}; see {@link #pcBase}). The first stage is valid unless
 * {@code P_stop_fetch} (the Level-2 generator's halt) is set. A stage with several issue lanes adds
 * {@code _l<k>} to those names for lane {@code k} &gt; 0, and a pipeline may name a per-item id
 * ({@code P_id_<S>}); without one, items are followed by where they flow.
 *
 * <p><b>Following items.</b> A stage that is not held passes its items to the next stage; a held one
 * keeps them. So the items expected in stage {@code k} next cycle are the ones it kept, then the ones
 * stage {@code k-1} passed (older first), and the first stage also fetches. Each valid lane next cycle
 * takes the oldest expected item; expected items left over were flushed (a flush cancels the youngest
 * items), and items passed out of the last stage retired.
 */
final class PipeView {
    private PipeView() {}

    /** One stage lane's signals: valid (null: always; "!x": when x is 0), hold, and optional pc and id. */
    record Probe(int stage, int lane, String valid, String hold, String pc, String id) {}

    record Pipe(String name, List<String> stages, int lanes, List<Probe> probes) {
        List<Probe> at(int stage) { return probes.stream().filter(p -> p.stage() == stage).sorted((a, b) -> a.lane() - b.lane()).toList(); }

        Set<String> paths() {
            var s = new LinkedHashSet<String>();
            for (var p : probes)
                for (String x : new String[]{p.valid(), p.hold(), p.pc(), p.id()})
                    if (x != null) { var m = NAME.matcher(x); while (m.find()) s.add(m.group()); }
            return s;
        }
    }

    private static final Pattern HOLD = Pattern.compile("(.+)_hold_([A-Za-z0-9]+?)(?:_l(\\d+))?");

    /**
     * The pipelines with their stages named as the schedule names them: the signals call the first stage
     * "s0", so a pipeline whose other stages match {@code names} takes its first name. Others are unchanged.
     */
    static List<Pipe> relabel(List<Pipe> pipes, List<String> names) {
        if (names == null) return pipes;
        var out = new ArrayList<Pipe>();
        for (var p : pipes) {
            boolean same = p.stages().size() == names.size() && p.stages().getFirst().equals("s0")
                    && p.stages().subList(1, names.size()).equals(names.subList(1, names.size()));
            out.add(same ? new Pipe(p.name(), List.copyOf(names), p.lanes(), p.probes()) : p);
        }
        return out;
    }

    /** The pipelines of a module, from its signals' names (empty when it has none). */
    static List<Pipe> find(ModuleDef top) {
        var names = new LinkedHashMap<String, Signal>();
        for (var s : top.signals()) names.put(s.name(), s);
        var stagesOf = new LinkedHashMap<String, List<String>>();
        var lanesOf = new LinkedHashMap<String, Integer>();
        for (var s : top.signals()) {
            var m = HOLD.matcher(s.name());
            if (!m.matches() || s.width() != 1) continue;
            String p = m.group(1), st = m.group(2);
            int lane = m.group(3) == null ? 0 : Integer.parseInt(m.group(3));
            var l = stagesOf.computeIfAbsent(p, k -> new ArrayList<>());
            if (!l.contains(st)) l.add(st);
            lanesOf.merge(p, lane + 1, Math::max);
        }
        var out = new ArrayList<Pipe>();
        for (var e : stagesOf.entrySet()) {
            String p = e.getKey();
            var stages = e.getValue();
            if (stages.size() < 2) continue;
            var probes = new ArrayList<Probe>();
            String pcBase = pcBase(p, stages, names);
            boolean ok = true;
            for (int k = 0; k < stages.size() && ok; k++) {
                for (int lane = 0; lane < lanesOf.get(p); lane++) {
                    String suf = "_" + stages.get(k) + (lane == 0 ? "" : "_l" + lane);
                    String hold = p + "_hold" + suf;
                    if (!names.containsKey(hold)) continue;
                    String valid = p + "_valid" + suf;
                    if (!names.containsKey(valid)) {
                        if (k > 0) { ok = false; break; }
                        valid = names.containsKey(p + "_stop_fetch") ? "!" + p + "_stop_fetch" : null;
                    }
                    String pc = pcBase == null ? null : k == 0 && lane == 0 ? pcBase : pcBase + suf;
                    String id = p + "_id" + suf;
                    probes.add(new Probe(k, lane, valid, hold, pc != null && names.containsKey(pc) ? pc : null, names.containsKey(id) ? id : null));
                }
            }
            if (ok) out.add(new Pipe(p, List.copyOf(stages), lanesOf.get(p), List.copyOf(probes)));
        }
        return out;
    }

    /**
     * The program counter the pipeline carries: {@code P_pc} (the Level-2 generator's), else a register
     * named {@code pc} or ending in {@code _pc}, as long as it has a copy {@code <name>_<S>} in every later
     * stage; null when there is none (items are then shown by number).
     */
    private static String pcBase(String p, List<String> stages, Map<String, Signal> names) {
        var candidates = new ArrayList<String>(List.of(p + "_pc", "pc"));
        for (var n : names.keySet()) if (n.endsWith("_pc") && !candidates.contains(n)) candidates.add(n);
        for (var c : candidates) {
            if (!names.containsKey(c)) continue;
            boolean all = true;
            for (int k = 1; k < stages.size() && all; k++) all = names.containsKey(c + "_" + stages.get(k));
            if (all) return c;
        }
        return null;
    }

    /** An item's place in one cycle. */
    record Cell(long cycle, int stage, int lane, boolean stalled) {}

    static final class Item {
        final int id;
        final long pc;
        final boolean early;            // already in flight when the window starts
        final List<Cell> cells = new ArrayList<>();
        String end = "inflight";
        long endCycle = -1;
        int lane;

        Item(int id, long pc, boolean early) { this.id = id; this.pc = pc; this.early = early; }
    }

    static final int MAX_ITEMS = 2000;

    /**
     * The items of {@code pipe} over cycles {@code from..to}, from {@code value} (a recorded signal's
     * value in a cycle; null when not recorded).
     */
    static List<Item> follow(Pipe pipe, long from, long to, BiFunction<String, Long, BigInteger> value) {
        // with a pc in every lane of every stage (the dual-issue generator's probes), items are followed by their pc
        if (pipe.lanes() > 1 && pipe.probes().stream().allMatch(p -> p.pc() != null)) return followByPc(pipe, from, to, value);
        int n = pipe.stages().size();
        var items = new ArrayList<Item>();
        List<List<Item>> cur = new ArrayList<>();
        int[] next = {0};
        // what is in flight when the window opens, oldest (deepest stage, then lane 0) first, as in program order
        for (int k = 0; k < n; k++) cur.add(new ArrayList<>());
        for (int k = n - 1; k >= 0; k--)
            for (var pr : pipe.at(k)) if (on(pr.valid(), from, value)) cur.get(k).add(place(new Item(next[0]++, pc(pr, from, value), true), k, pr.lane(), from, false, items));
        for (long t = from; t < to && items.size() < MAX_ITEMS; t++) {
            final long now = t;
            var nxt = new ArrayList<List<Item>>();
            List<Item> passed = List.of();
            for (int k = 0; k < n; k++) {
                var kept = new ArrayList<Item>();
                var moving = new ArrayList<Item>();
                for (var it : cur.get(k)) {
                    var pr = probe(pipe, k, it.lane);
                    if (pr != null && on(pr.hold(), now, value)) kept.add(it); else moving.add(it);
                }
                var expected = new ArrayList<Item>(kept);
                expected.addAll(passed);
                var slots = new ArrayList<Probe>();
                for (var pr : pipe.at(k)) if (on(pr.valid(), now + 1, value)) slots.add(pr);
                var here = new ArrayList<Item>();
                for (int i = 0; i < slots.size(); i++) {
                    var pr = slots.get(i);
                    if (i < expected.size()) {
                        var it = expected.get(i);
                        here.add(place(it, k, pr.lane(), now + 1, kept.contains(it), items));
                    } else {
                        // a fetch (or, where items cannot be followed, one that turns up)
                        here.add(place(new Item(next[0]++, pc(pr, now + 1, value), k > 0), k, pr.lane(), now + 1, false, items));
                    }
                }
                for (int i = slots.size(); i < expected.size(); i++) {
                    var it = expected.get(i);
                    it.end = "flushed";
                    it.endCycle = now;
                }
                nxt.add(here);
                passed = moving;
            }
            for (var it : passed) {   // out of the last stage
                it.end = "retired";
                it.endCycle = now;
            }
            cur = nxt;
        }
        return items;
    }

    /**
     * Following items by pc: from the last stage back, a slot in stage k takes the item with its pc that was in k
     * (it stayed: held, or left over by a split pair) or in k-1 (it moved on), preferring the one in k when k was
     * held. What stage k-1 had and no slot took was cancelled; what the last stage had and no slot took retired.
     */
    static List<Item> followByPc(Pipe pipe, long from, long to, BiFunction<String, Long, BigInteger> value) {
        int n = pipe.stages().size();
        var items = new ArrayList<Item>();
        int[] next = {0};
        List<List<Item>> cur = new ArrayList<>();
        // what is in flight when the window opens, oldest (deepest stage, then lane 0) first, as in program order
        for (int k = 0; k < n; k++) cur.add(new ArrayList<>());
        for (int k = n - 1; k >= 0; k--)
            for (var pr : pipe.at(k)) if (on(pr.valid(), from, value)) cur.get(k).add(place(new Item(next[0]++, pc(pr, from, value), true), k, pr.lane(), from, false, items));
        for (long t = from; t < to && items.size() < MAX_ITEMS; t++) {
            final long now = t;
            List<List<Item>> nxt = new ArrayList<>();
            for (int k = 0; k < n; k++) nxt.add(new ArrayList<>());
            var taken = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Item, Boolean>());
            for (int k = n - 1; k >= 0; k--) {
                var hp = pipe.at(k).isEmpty() ? null : pipe.at(k).getFirst();
                boolean held = hp != null && on(hp.hold(), now, value);
                for (var pr : pipe.at(k)) {
                    if (!on(pr.valid(), now + 1, value)) continue;
                    long pc = pc(pr, now + 1, value);
                    Item found = null;
                    List<List<Item>> order = held ? List.of(cur.get(k), k > 0 ? cur.get(k - 1) : List.of()) : List.of(k > 0 ? cur.get(k - 1) : List.of(), cur.get(k));
                    for (var pool : order) {
                        for (var it : pool) if (!taken.contains(it) && it.pc == pc) { found = it; break; }
                        if (found != null) break;
                    }
                    if (found == null) found = new Item(next[0]++, pc, k > 0);
                    boolean stayed = cur.get(k).contains(found);
                    taken.add(found);
                    nxt.get(k).add(place(found, k, pr.lane(), now + 1, stayed, items));
                }
                // what stage k had and nobody took: past the last stage it retired; elsewhere it moves on, unless the
                // next stage did not take it either, in which case it was cancelled (checked when k+1 was done)
                if (k == n - 1) for (var it : cur.get(k)) if (!taken.contains(it)) { it.end = "retired"; it.endCycle = now; }
                if (k < n - 1) for (var it : cur.get(k)) if (!taken.contains(it)) { it.end = "flushed"; it.endCycle = now; }
            }
            cur = nxt;
        }
        return items;
    }

    private static Item place(Item it, int stage, int lane, long cycle, boolean stalled, List<Item> all) {
        if (it.cells.isEmpty()) all.add(it);
        it.cells.add(new Cell(cycle, stage, lane, stalled));
        it.lane = lane;
        return it;
    }

    private static Probe probe(Pipe pipe, int stage, int lane) {
        for (var p : pipe.probes()) if (p.stage() == stage && p.lane() == lane) return p;
        return null;
    }

    private static boolean on(String sig, long cycle, BiFunction<String, Long, BigInteger> value) {
        if (sig == null) return true;
        BigInteger v = eval(sig, n -> value.apply(n, cycle));
        return v != null && v.signum() != 0;
    }

    private static long pc(Probe pr, long cycle, BiFunction<String, Long, BigInteger> value) {
        if (pr.pc() == null) return -1;
        BigInteger v = eval(pr.pc(), n -> value.apply(n, cycle));
        return v == null ? -1 : v.longValue();
    }

    private static final Pattern NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Pattern TOKEN = Pattern.compile("\\s*([A-Za-z_][A-Za-z0-9_]*|\\d+|[!&|?:+()])");

    /**
     * A probe's value: a signal's name, or a small expression over names (the dual-issue generator's probes):
     * {@code !a}, {@code a & b}, {@code a | b}, {@code c ? a : b}, {@code a + 4}, parentheses. Null when a name it
     * needs is not recorded.
     */
    static BigInteger eval(String expr, java.util.function.Function<String, BigInteger> value) {
        var toks = new ArrayList<String>();
        var m = TOKEN.matcher(expr);
        int at = 0;
        while (at < expr.length() && m.find(at) && m.start() == at) { toks.add(m.group(1)); at = m.end(); }
        if (expr.substring(at).isBlank()) {
            try {
                var p = new Parser(toks, value);
                BigInteger v = p.cond();
                if (p.i == toks.size()) return v;
            } catch (MissingValue e) {
                return null;
            }
        }
        throw new IllegalArgumentException("a probe I cannot read: " + expr);
    }

    private static final class MissingValue extends RuntimeException {
        MissingValue() { super(null, null, false, false); }
    }

    private static final class Parser {
        final List<String> t;
        final java.util.function.Function<String, BigInteger> value;
        int i;

        Parser(List<String> t, java.util.function.Function<String, BigInteger> value) { this.t = t; this.value = value; }

        boolean at(String s) { if (i < t.size() && t.get(i).equals(s)) { i++; return true; } return false; }

        BigInteger cond() {
            BigInteger c = or();
            if (!at("?")) return c;
            BigInteger a = cond();
            if (!at(":")) throw new IllegalArgumentException("? without :");
            BigInteger b = cond();
            return c.signum() != 0 ? a : b;
        }

        BigInteger or() { BigInteger v = and(); while (at("|")) { BigInteger w = and(); v = v.signum() != 0 || w.signum() != 0 ? BigInteger.ONE : BigInteger.ZERO; } return v; }

        BigInteger and() { BigInteger v = sum(); while (at("&")) { BigInteger w = sum(); v = v.signum() != 0 && w.signum() != 0 ? BigInteger.ONE : BigInteger.ZERO; } return v; }

        BigInteger sum() { BigInteger v = unary(); while (at("+")) v = v.add(unary()); return v; }

        BigInteger unary() {
            if (at("!")) return unary().signum() != 0 ? BigInteger.ZERO : BigInteger.ONE;
            if (at("(")) { BigInteger v = cond(); if (!at(")")) throw new IllegalArgumentException("( without )"); return v; }
            String w = t.get(i++);
            if (Character.isDigit(w.charAt(0))) return new BigInteger(w);
            BigInteger v = value.apply(w);
            if (v == null) throw new MissingValue();
            return v;
        }
    }

    /** The diagram as JSON: stages, lanes and, per item, its pc, its instruction, its cells and how it ended. */
    static Map<String, Object> json(Pipe pipe, List<Item> items, java.util.function.LongFunction<String> text) {
        var m = new LinkedHashMap<String, Object>();
        m.put("name", pipe.name());
        m.put("stages", pipe.stages());
        m.put("lanes", pipe.lanes());
        var l = new ArrayList<Object>();
        for (var it : items) {
            var o = new LinkedHashMap<String, Object>();
            o.put("id", it.id);
            o.put("pc", it.pc < 0 ? null : Long.toHexString(it.pc));
            o.put("text", it.pc < 0 ? null : text.apply(it.pc));
            var cells = new ArrayList<Object>();
            for (var c : it.cells) cells.add(List.of(c.cycle(), c.stage(), c.lane(), c.stalled() ? 1 : 0));
            o.put("cells", cells);
            o.put("end", it.end);
            o.put("endCycle", it.endCycle);
            o.put("early", it.early);
            l.add(o);
        }
        m.put("items", l);
        m.put("truncated", items.size() >= MAX_ITEMS);
        return m;
    }
}
