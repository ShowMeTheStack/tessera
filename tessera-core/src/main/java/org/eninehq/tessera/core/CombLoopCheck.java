package org.eninehq.tessera.core;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.eninehq.tessera.ir.Circuit;
import org.eninehq.tessera.ir.Expr;
import org.eninehq.tessera.ir.Signal;

/** Detects combinational loops across the whole design (registers and memory writes break loops). */
final class CombLoopCheck {
    private CombLoopCheck() {}

    static void check(Circuit c) {
        Map<Signal, List<Signal>> deps = new IdentityHashMap<>();
        for (var m : c.modulesBottomUp()) {
            for (var s : m.allSignals()) {
                if (s.kind() == Signal.Kind.REG) continue;
                Expr driver = s.driver();
                if (s.kind() == Signal.Kind.INPUT) {
                    var inst = m.instantiatedAs();
                    driver = inst == null ? null : inst.inputs().get(s);
                }
                if (driver != null) deps.put(s, collect(driver));
            }
        }
        Map<Signal, Integer> color = new IdentityHashMap<>();
        for (var s : deps.keySet()) {
            if (color.getOrDefault(s, 0) == 0) dfs(s, deps, color, new ArrayList<>());
        }
    }

    private static List<Signal> collect(Expr root) {
        var out = new ArrayList<Signal>();
        Set<Expr> seen = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        var stack = new ArrayList<Expr>();
        stack.add(root);
        while (!stack.isEmpty()) {
            Expr e = stack.removeLast();
            if (!seen.add(e)) continue;
            switch (e) {
                case Expr.Lit l -> {}
                case Expr.Ref r -> { if (r.signal().kind() != Signal.Kind.REG) out.add(r.signal()); }
                case Expr.InstRef ir -> out.add(ir.port());
                case Expr.MemRead mr -> stack.add(mr.addr());
                case Expr.Op op -> stack.addAll(op.args());
            }
        }
        return out;
    }

    private static void dfs(Signal start, Map<Signal, List<Signal>> deps, Map<Signal, Integer> color, List<Signal> path) {
        // iterative DFS to survive deep designs
        record Frame(Signal s, int next) {}
        var stack = new ArrayList<Frame>();
        stack.add(new Frame(start, 0));
        color.put(start, 1);
        path.add(start);
        while (!stack.isEmpty()) {
            var f = stack.removeLast();
            var ds = deps.getOrDefault(f.s(), List.of());
            if (f.next() < ds.size()) {
                stack.add(new Frame(f.s(), f.next() + 1));
                var d = ds.get(f.next());
                int col = color.getOrDefault(d, 0);
                if (col == 1) {
                    var cycle = new ArrayList<String>();
                    for (int i = path.indexOf(d); i < path.size(); i++) cycle.add(path.get(i).toString());
                    cycle.add(d.toString());
                    throw new TesseraException(d.loc(), "combinational loop: " + String.join(" -> ", cycle));
                }
                if (col == 0) {
                    color.put(d, 1);
                    path.add(d);
                    stack.add(new Frame(d, 0));
                }
            } else {
                color.put(f.s(), 2);
                path.removeLast();
            }
        }
    }
}
