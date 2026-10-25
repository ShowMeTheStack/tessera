package org.eninehq.tessera.playground;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.eninehq.tessera.ir.Circuit;
import org.eninehq.tessera.ir.Expr;
import org.eninehq.tessera.ir.Instance;
import org.eninehq.tessera.ir.ModuleDef;
import org.eninehq.tessera.ir.Signal;

/**
 * Draws a module as a block diagram (SVG): its inputs on the left, child instances as boxes in
 * the middle, outputs on the right, and a line for every connection. Registers and memories
 * are listed under the module name. Java code, seen as hardware.
 */
public final class Diagram {
    private Diagram() {}

    private record Node(String id, String title, List<String> ins, List<String> outs, int column) {}
    private record Edge(String from, String fromPort, String to, String toPort) {}

    public static String svg(Circuit c) {
        return svg(c.top());
    }

    public static String svg(ModuleDef m) {
        var nodes = new LinkedHashMap<String, Node>();
        var edges = new ArrayList<Edge>();
        // instance columns: longest path from the module's inputs
        Map<Instance, Integer> col = new IdentityHashMap<>();
        for (var i : m.instances()) col.put(i, 1);
        for (int round = 0; round < m.instances().size() + 1; round++)
            for (var i : m.instances())
                for (var e : i.inputs().entrySet())
                    for (var src : sources(e.getValue()))
                        if (src instanceof Expr.InstRef ir && ir.instance() != i) col.put(i, Math.max(col.get(i), col.get(ir.instance()) + 1));
        int outCol = col.values().stream().max(Integer::compare).orElse(0) + 1;
        var ins = new ArrayList<String>();
        var outs = new ArrayList<String>();
        for (var p : m.ports()) {
            if (p.kind() == Signal.Kind.INPUT && !p.isClockOrReset()) ins.add(label(p));
            if (p.kind() == Signal.Kind.OUTPUT) outs.add(label(p));
        }
        nodes.put("in", new Node("in", "inputs", List.of(), ins, 0));
        for (var i : m.instances()) {
            var pi = new ArrayList<String>();
            var po = new ArrayList<String>();
            for (var p : i.def().ports()) {
                if (p.kind() == Signal.Kind.INPUT && !p.isClockOrReset()) pi.add(label(p));
                if (p.kind() == Signal.Kind.OUTPUT) po.add(label(p));
            }
            nodes.put(i.name(), new Node(i.name(), i.name() + " : " + i.def().name(), pi, po, col.get(i)));
            for (var e : i.inputs().entrySet()) {
                if (e.getKey().isClockOrReset()) continue;
                for (var src : sources(e.getValue())) edges.add(edgeFrom(src, i.name(), label(e.getKey())));
            }
        }
        nodes.put("out", new Node("out", "outputs", outs, List.of(), outCol));
        for (var p : m.ports())
            if (p.kind() == Signal.Kind.OUTPUT && p.driver() != null)
                for (var src : sources(p.driver())) edges.add(edgeFrom(src, "out", label(p)));
        edges.removeIf(e -> e.from() == null);
        return render(m, nodes, new ArrayList<>(new LinkedHashSet<>(edges)), outCol);
    }

    private static String label(Signal s) {
        return s.width() == 1 ? s.name() : s.name() + "[" + s.width() + "]";
    }

    private static Edge edgeFrom(Expr src, String to, String toPort) {
        return switch (src) {
            case Expr.Ref r when r.signal().kind() == Signal.Kind.INPUT -> new Edge("in", label(r.signal()), to, toPort);
            case Expr.InstRef ir -> new Edge(ir.instance().name(), label(ir.port()), to, toPort);
            default -> new Edge(null, null, to, toPort);
        };
    }

    /** Module inputs and child outputs that reach {@code e} through combinational logic of this module. */
    private static List<Expr> sources(Expr root) {
        var out = new ArrayList<Expr>();
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        var stack = new ArrayList<Expr>();
        stack.add(root);
        while (!stack.isEmpty()) {
            Expr e = stack.removeLast();
            if (!seen.add(e)) continue;
            switch (e) {
                case Expr.Lit l -> {}
                case Expr.InstRef ir -> out.add(ir);
                case Expr.Ref r -> {
                    Signal s = r.signal();
                    if (s.kind() == Signal.Kind.INPUT) out.add(r);
                    else if (s.driver() != null && seen.add(s)) stack.add(s.driver());
                }
                case Expr.MemRead mr -> stack.add(mr.addr());
                case Expr.Op op -> stack.addAll(op.args());
            }
        }
        return out;
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String render(ModuleDef m, Map<String, Node> nodes, List<Edge> edges, int cols) {
        int colW = 260, boxW = 180, rowH = 22, top = 70;
        Map<Integer, Integer> colY = new LinkedHashMap<>();
        Map<String, int[]> pos = new LinkedHashMap<>();
        for (var n : nodes.values()) {
            int rows = Math.max(Math.max(n.ins().size(), n.outs().size()), 1);
            int h = 34 + rows * rowH;
            int y = colY.getOrDefault(n.column(), top);
            pos.put(n.id(), new int[]{30 + n.column() * colW, y, h});
            colY.put(n.column(), y + h + 30);
        }
        int width = 60 + (cols + 1) * colW, height = colY.values().stream().max(Integer::compare).orElse(top) + 20;
        long regs = m.signals().stream().filter(s -> s.kind() == Signal.Kind.REG).count();
        var sb = new StringBuilder();
        sb.append("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 ").append(width).append(' ').append(height)
                .append("\" font-family=\"ui-sans-serif, system-ui, sans-serif\" font-size=\"12\">\n");
        sb.append("<style>.box{fill:var(--box,#f4f6fb);stroke:var(--ink,#334);}.t{fill:var(--ink,#223);}"
                + ".e{fill:none;stroke:var(--wire,#3b82f6);stroke-width:1.5;opacity:.8}"
                + "@media (prefers-color-scheme: dark){.box{fill:#1e2533;stroke:#9ab}.t{fill:#e6ecf5}}</style>\n");
        sb.append("<text class=\"t\" x=\"30\" y=\"28\" font-size=\"16\" font-weight=\"600\">").append(esc(m.name())).append("</text>\n");
        sb.append("<text class=\"t\" x=\"30\" y=\"48\">").append(regs).append(" registers, ").append(m.mems().size())
                .append(" memories, ").append(m.instances().size()).append(" instances</text>\n");
        Map<String, int[]> portXY = new LinkedHashMap<>();
        for (var n : nodes.values()) {
            int[] p = pos.get(n.id());
            int x = p[0], y = p[1], h = p[2];
            sb.append("<rect class=\"box\" rx=\"8\" x=\"").append(x).append("\" y=\"").append(y).append("\" width=\"").append(boxW)
                    .append("\" height=\"").append(h).append("\"/>\n");
            sb.append("<text class=\"t\" x=\"").append(x + 10).append("\" y=\"").append(y + 20).append("\" font-weight=\"600\">")
                    .append(esc(n.title())).append("</text>\n");
            for (int k = 0; k < n.ins().size(); k++) {
                int py = y + 34 + k * rowH + 10;
                portXY.put(n.id() + "/in/" + n.ins().get(k), new int[]{x, py});
                sb.append("<text class=\"t\" x=\"").append(x + 10).append("\" y=\"").append(py + 4).append("\">").append(esc(n.ins().get(k))).append("</text>\n");
            }
            for (int k = 0; k < n.outs().size(); k++) {
                int py = y + 34 + k * rowH + 10;
                portXY.put(n.id() + "/out/" + n.outs().get(k), new int[]{x + boxW, py});
                sb.append("<text class=\"t\" text-anchor=\"end\" x=\"").append(x + boxW - 10).append("\" y=\"").append(py + 4).append("\">")
                        .append(esc(n.outs().get(k))).append("</text>\n");
            }
        }
        for (var e : edges) {
            int[] a = portXY.get(e.from() + "/out/" + e.fromPort()), b = portXY.get(e.to() + "/in/" + e.toPort());
            if (a == null || b == null) continue;
            int mx = (a[0] + b[0]) / 2;
            sb.append("<path class=\"e\" d=\"M").append(a[0]).append(',').append(a[1]).append(" C").append(mx).append(',').append(a[1])
                    .append(' ').append(mx).append(',').append(b[1]).append(' ').append(b[0]).append(',').append(b[1]).append("\"/>\n");
        }
        return sb.append("</svg>\n").toString();
    }
}
