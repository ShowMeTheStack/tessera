package org.eninehq.tessera.studio;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import org.eninehq.tessera.ir.ModuleDef;
import org.eninehq.tessera.ir.Signal;

/**
 * The reorder-buffer diagram of an out-of-order core: which instruction held which reorder-buffer entry in each cycle,
 * and where it was: waiting in a reservation station, executing, in the load queue, done (waiting to commit) or, for a
 * store, done with its data in the store queue. The last cell says whether it committed or was flushed.
 *
 * <p>As {@link PipeView}, it is read from signals the cores name already: the hand-written ones (C7, C11:
 * {@code rob<i>_valid}, {@code rob<i>_done}, {@code rob<i>_pc}, {@code rob<i>_inst}) and the generated ones (C7g and its
 * options, under the generator's prefix: {@code core_rob<i>_valid}, {@code core_rob_pc_<i>}, ...), with
 * {@code rob_head}, {@code commit}, and where there are any, the reservation stations ({@code rs<j>_valid},
 * {@code rs<j>_tag}), load and store queues ({@code lq<k>_*}, {@code sq<k>_*}) and branch checkpoints
 * ({@code cp<k>_valid}).
 *
 * <p><b>Following items.</b> An entry holds one instruction from the cycle it turns valid to the last cycle it is
 * valid with the same pc. It committed if, in that last cycle, {@code commit} was set with the head on it; otherwise it
 * was flushed (a wrong guess, a trap or an interrupt).
 */
final class OooView {
    private OooView() {}

    /** The places an instruction can be in; the diagram's cells name them. */
    static final List<String> STATES = List.of("RS", "EX", "LQ", "SQ", "OK");
    static final int RS = 0, EX = 1, LQ = 2, SQ = 3, OK = 4;

    /** An out-of-order core's signals: the prefix and, per entry, its signal names (pc and inst may be null). */
    record Rob(String prefix, int entries, List<String> valid, List<String> done, List<String> pc, List<String> inst,
               String head, String commit, List<String[]> rs, List<String[]> lq, List<String[]> sq, List<String> cp) {
        Set<String> paths() {
            var s = new LinkedHashSet<String>();
            s.addAll(valid);
            s.addAll(done);
            for (var p : pc) if (p != null) s.add(p);
            for (var p : inst) if (p != null) s.add(p);
            s.add(head);
            s.add(commit);
            for (var l : List.of(rs, lq, sq)) for (var a : l) s.addAll(List.of(a));
            s.addAll(cp);
            return s;
        }
    }

    /** The reorder buffers of a module (none, or one), from its signals' names. */
    static List<Rob> find(ModuleDef top) {
        var names = new LinkedHashMap<String, Signal>();
        for (var s : top.signals()) names.put(s.name(), s);
        var out = new ArrayList<Rob>();
        for (String p : List.of("", "core_")) {
            if (!names.containsKey(p + "rob_head") || !names.containsKey(p + "rob0_valid") || !names.containsKey(p + "rob0_done")
                    || !names.containsKey(p + "commit")) continue;
            int n = 0;
            while (names.containsKey(p + "rob" + n + "_valid") && names.containsKey(p + "rob" + n + "_done")) n++;
            var valid = new ArrayList<String>();
            var done = new ArrayList<String>();
            var pc = new ArrayList<String>();
            var inst = new ArrayList<String>();
            for (int i = 0; i < n; i++) {
                valid.add(p + "rob" + i + "_valid");
                done.add(p + "rob" + i + "_done");
                pc.add(first(names, p + "rob" + i + "_pc", p + "rob_pc_" + i));
                inst.add(first(names, p + "rob" + i + "_inst", p + "rob_inst_" + i));
            }
            out.add(new Rob(p, n, valid, done, pc, inst, p + "rob_head", p + "commit",
                    queue(names, p + "rs", "_tag"), queue(names, p + "lq", "_tag"), queue(names, p + "sq", "_tag"), cps(names, p)));
            break;
        }
        return out;
    }

    private static String first(Map<String, Signal> names, String... c) {
        for (var x : c) if (names.containsKey(x)) return x;
        return null;
    }

    /** Entries {@code <q><k>_valid} with {@code <q><k>_tag}: [valid, tag] each. */
    private static List<String[]> queue(Map<String, Signal> names, String q, String tag) {
        var l = new ArrayList<String[]>();
        for (int k = 0; names.containsKey(q + k + "_valid") && names.containsKey(q + k + tag); k++) l.add(new String[]{q + k + "_valid", q + k + tag});
        return l;
    }

    private static List<String> cps(Map<String, Signal> names, String p) {
        var l = new ArrayList<String>();
        for (int k = 0; names.containsKey(p + "cp" + k + "_valid"); k++) l.add(p + "cp" + k + "_valid");
        return l;
    }

    /** An instruction's time in one entry. */
    static final class Item {
        final int slot;
        final long pc, inst, start;
        final boolean early;
        final List<int[]> states = new ArrayList<>();   // per cycle from start: the state
        String end = "inflight";
        long endCycle = -1;
        int age;                                        // its distance from the head when it turned up, for program order

        Item(int slot, long pc, long inst, long start, boolean early) { this.slot = slot; this.pc = pc; this.inst = inst; this.start = start; this.early = early; }
    }

    static final int MAX_ITEMS = 2000;

    /** The items over cycles {@code from..to}, oldest first, and the occupancy of each structure per cycle. */
    record Result(List<Item> items, Map<String, List<Integer>> occupancy) {}

    static Result follow(Rob rob, long from, long to, BiFunction<String, Long, BigInteger> value) {
        var items = new ArrayList<Item>();
        Item[] cur = new Item[rob.entries()];
        var occ = new LinkedHashMap<String, List<Integer>>();
        occ.put("ROB", new ArrayList<>());
        if (!rob.rs().isEmpty()) occ.put("RS", new ArrayList<>());
        if (!rob.lq().isEmpty()) occ.put("LQ", new ArrayList<>());
        if (!rob.sq().isEmpty()) occ.put("SQ", new ArrayList<>());
        if (!rob.cp().isEmpty()) occ.put("checkpoints", new ArrayList<>());
        for (long t = from; t <= to && items.size() < MAX_ITEMS; t++) {
            final long now = t;
            int head = (int) num(rob.head(), now, value);
            boolean commit = num(rob.commit(), now, value) != 0;
            var inRs = tags(rob.rs(), now, value);
            var inLq = tags(rob.lq(), now, value);
            var inSq = tags(rob.sq(), now, value);
            int used = 0;
            for (int i = 0; i < rob.entries(); i++) {
                boolean v = num(rob.valid().get(i), now, value) != 0;
                if (!v) { cur[i] = null; continue; }
                used++;
                long pc = rob.pc().get(i) == null ? -1 : num(rob.pc().get(i), now, value);
                if (cur[i] != null && cur[i].pc != pc) cur[i] = null;   // a new instruction in the entry
                if (cur[i] == null) {
                    long inst = rob.inst().get(i) == null ? -1 : num(rob.inst().get(i), now, value);
                    cur[i] = new Item(i, pc, inst, now, now == from);
                    cur[i].age = Math.floorMod(i - head, rob.entries());
                    items.add(cur[i]);
                }
                boolean done = num(rob.done().get(i), now, value) != 0;
                int st = inRs.contains(i) ? RS : !done ? (inLq.contains(i) ? LQ : EX) : inSq.contains(i) ? SQ : OK;
                cur[i].states.add(new int[]{st});
                if (commit && head == i) { cur[i].end = "retired"; cur[i].endCycle = now; cur[i] = null; }
            }
            // what was valid and is not, or was replaced, the cycle after: flushed
            for (var it : items) if (it.end.equals("inflight") && cur[it.slot] != it) { it.end = "flushed"; it.endCycle = it.start + it.states.size() - 1; }
            occ.get("ROB").add(used);
            if (occ.containsKey("RS")) occ.get("RS").add(count(rob.rs(), now, value));
            if (occ.containsKey("LQ")) occ.get("LQ").add(count(rob.lq(), now, value));
            if (occ.containsKey("SQ")) occ.get("SQ").add(count(rob.sq(), now, value));
            if (occ.containsKey("checkpoints")) {
                int c = 0;
                for (var s : rob.cp()) if (num(s, now, value) != 0) c++;
                occ.get("checkpoints").add(c);
            }
        }
        items.sort((a, b) -> a.start != b.start ? Long.compare(a.start, b.start) : Integer.compare(a.age, b.age));
        return new Result(items, occ);
    }

    private static Set<Integer> tags(List<String[]> q, long t, BiFunction<String, Long, BigInteger> value) {
        var s = new java.util.HashSet<Integer>();
        for (var e : q) if (num(e[0], t, value) != 0) s.add((int) num(e[1], t, value));
        return s;
    }

    private static int count(List<String[]> q, long t, BiFunction<String, Long, BigInteger> value) {
        int c = 0;
        for (var e : q) if (num(e[0], t, value) != 0) c++;
        return c;
    }

    private static long num(String sig, long t, BiFunction<String, Long, BigInteger> value) {
        BigInteger v = value.apply(sig, t);
        return v == null ? 0 : v.longValue();
    }

    /**
     * The diagram as JSON, in the shape of {@link PipeView#json} (so the browser draws it the same way): "stages" are
     * the states, a cell is [cycle, state, entry, 0]; plus "entries" and "occupancy" ({structure: [count per cycle]}).
     */
    static Map<String, Object> json(Rob rob, Result r, java.util.function.LongFunction<String> textOfPc) {
        var m = new LinkedHashMap<String, Object>();
        m.put("name", rob.prefix().isEmpty() ? "reorder buffer" : rob.prefix().replaceAll("_$", "") + " reorder buffer");
        m.put("stages", STATES);
        m.put("lanes", 1);
        m.put("entries", rob.entries());
        var l = new ArrayList<Object>();
        int id = 0;
        for (var it : r.items()) {
            var o = new LinkedHashMap<String, Object>();
            o.put("id", id++);
            o.put("pc", it.pc < 0 ? null : Long.toHexString(it.pc));
            o.put("text", it.inst >= 0 ? Rv32Disasm.of((int) it.inst) : it.pc < 0 ? null : textOfPc.apply(it.pc));
            o.put("slot", it.slot);
            var cells = new ArrayList<Object>();
            for (int k = 0; k < it.states.size(); k++) cells.add(List.of(it.start + k, it.states.get(k)[0], it.slot, 0));
            o.put("cells", cells);
            o.put("end", it.end);
            o.put("endCycle", it.endCycle);
            o.put("early", it.early);
            l.add(o);
        }
        m.put("items", l);
        m.put("truncated", r.items().size() >= MAX_ITEMS);
        m.put("occupancy", r.occupancy());
        return m;
    }
}
