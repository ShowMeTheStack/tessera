package org.eninehq.tessera.emit;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.eninehq.tessera.core.Elaborated;
import org.eninehq.tessera.core.SourceLoc;
import org.eninehq.tessera.ir.Circuit;
import org.eninehq.tessera.ir.Expr;
import org.eninehq.tessera.ir.MemDef;
import org.eninehq.tessera.ir.ModuleDef;
import org.eninehq.tessera.ir.Signal;

/**
 * Emits synthesizable SystemVerilog. Every operation gets its own explicitly sized wire and
 * every operand is explicitly extended, so the output does not depend on Verilog's
 * context-dependent width rules. Structurally identical modules are emitted once.
 */
public final class SvEmitter {

    private static final Set<String> KEYWORDS = Set.of(
            "alias", "always", "always_comb", "always_ff", "always_latch", "and", "assert", "assign", "assume",
            "automatic", "before", "begin", "bind", "bins", "binsof", "bit", "break", "buf", "bufif0", "bufif1",
            "byte", "case", "casex", "casez", "cell", "chandle", "class", "clocking", "cmos", "config", "const",
            "constraint", "context", "continue", "cover", "covergroup", "coverpoint", "cross", "deassign",
            "default", "defparam", "design", "disable", "dist", "do", "edge", "else", "end", "endcase",
            "endclass", "endclocking", "endconfig", "endfunction", "endgenerate", "endgroup", "endinterface",
            "endmodule", "endpackage", "endprimitive", "endprogram", "endproperty", "endspecify", "endsequence",
            "endtable", "endtask", "enum", "event", "expect", "export", "extends", "extern", "final",
            "first_match", "for", "force", "foreach", "forever", "fork", "forkjoin", "function", "generate",
            "genvar", "highz0", "highz1", "if", "iff", "ifnone", "ignore_bins", "illegal_bins", "import",
            "incdir", "include", "initial", "inout", "input", "inside", "instance", "int", "integer",
            "interface", "intersect", "join", "join_any", "join_none", "large", "liblist", "library", "local",
            "localparam", "logic", "longint", "macromodule", "matches", "medium", "modport", "module", "nand",
            "negedge", "new", "nmos", "nor", "noshowcancelled", "not", "notif0", "notif1", "null", "or",
            "output", "package", "packed", "parameter", "pmos", "posedge", "primitive", "priority", "program",
            "property", "protected", "pull0", "pull1", "pulldown", "pullup", "pulsestyle_ondetect",
            "pulsestyle_onevent", "pure", "rand", "randc", "randcase", "randsequence", "rcmos", "real",
            "realtime", "ref", "reg", "release", "repeat", "return", "rnmos", "rpmos", "rtran", "rtranif0",
            "rtranif1", "scalared", "sequence", "shortint", "shortreal", "showcancelled", "signed", "small",
            "solve", "specify", "specparam", "static", "string", "strong0", "strong1", "struct", "super",
            "supply0", "supply1", "table", "tagged", "task", "this", "throughout", "time", "timeprecision",
            "timeunit", "tran", "tranif0", "tranif1", "tri", "tri0", "tri1", "triand", "trior", "trireg",
            "type", "typedef", "union", "unique", "unique0", "unsigned", "use", "uwire", "var", "vectored",
            "virtual", "void", "wait", "wait_order", "wand", "weak0", "weak1", "while", "wildcard", "wire",
            "with", "within", "wor", "xnor", "xor");

    private final Map<ModuleDef, String> finalNames = new IdentityHashMap<>();
    private final Map<String, String> bodyToName = new HashMap<>();
    private final Set<String> usedModuleNames = new HashSet<>();
    private final StringBuilder out = new StringBuilder();
    /** With {@link #names}: what each emitted name stands for, module by module (emitted modules only). */
    private List<Name> table;
    /** With {@link #names}: where each operation was written, when it was recorded ({@link org.eninehq.tessera.core.OpLocations}). */
    private Map<Expr, SourceLoc> opLocs = Map.of();

    private SvEmitter() {}

    /**
     * What an emitted name stands for, so tools that report on the SystemVerilog (a synthesis tool's
     * timing paths) can point back at the design. {@code kind} is port, reg, wire, mem, inst (with
     * {@code child}, the instantiated module) or temp (an operation's wire, with {@code op}); {@code
     * signal} is the Tessera signal the name is, or for a temp the signal, memory or instance port its
     * value feeds; {@code file} and {@code line} are where in the Java that was made.
     */
    public record Name(String module, String name, String kind, int width, String signal, String op, String child,
                       String file, int line) {}

    /**
     * The name table of {@code c}'s SystemVerilog: the same names {@link #emit(Circuit)} and {@link
     * #write} use (emitting is deterministic), for the modules that are emitted.
     */
    public static List<Name> names(Circuit c) {
        return names(c, Map.of());
    }

    /**
     * The name table, with each temp at the line its operation was written when {@code opLocs} knows it
     * (otherwise at the line of the signal it feeds).
     */
    public static List<Name> names(Circuit c, Map<Expr, SourceLoc> opLocs) {
        var em = new SvEmitter();
        em.table = new java.util.ArrayList<>();
        em.opLocs = opLocs;
        for (var m : c.modulesBottomUp()) em.module(m);
        return em.table;
    }

    /** {@link #names} as tab-separated lines: module, name, kind, width, signal, op, child, file, line. */
    public static String nameTable(Circuit c) {
        return nameTable(c, Map.of());
    }

    /** {@link #nameTable(Circuit)} with operation locations ({@link #names(Circuit, Map)}). */
    public static String nameTable(Circuit c, Map<Expr, SourceLoc> opLocs) {
        var sb = new StringBuilder("# module\tname\tkind\twidth\tsignal\top\tchild\tfile\tline\n");
        for (var n : names(c, opLocs))
            sb.append(String.join("\t", n.module(), n.name(), n.kind(), String.valueOf(n.width()), n.signal(), n.op(),
                    n.child(), n.file(), String.valueOf(n.line()))).append('\n');
        return sb.toString();
    }

    /** {@link #write} and, beside the file, its {@link #nameTable} as {@code <file>.names.tsv}. */
    public static String writeWithNames(Circuit c, Path file) throws IOException {
        return writeWithNames(c, file, Map.of());
    }

    /** {@link #writeWithNames(Circuit, Path)} with operation locations. */
    public static String writeWithNames(Circuit c, Path file, Map<Expr, SourceLoc> opLocs) throws IOException {
        String top = write(c, file);
        Files.writeString(file.resolveSibling(file.getFileName() + ".names.tsv"), nameTable(c, opLocs));
        return top;
    }

    public static String emit(Elaborated<?> e) {
        return emit(e.circuit());
    }

    public static String emit(Circuit c) {
        var em = new SvEmitter();
        em.out.append("// Generated by Tessera. Do not edit.\n");
        for (var m : c.modulesBottomUp()) em.module(m);
        return em.out.toString();
    }

    /** Write the SystemVerilog for {@code c} to {@code file}; returns the top module's name. */
    public static String write(Circuit c, Path file) throws IOException {
        var em = new SvEmitter();
        em.out.append("// Generated by Tessera. Do not edit.\n");
        for (var m : c.modulesBottomUp()) em.module(m);
        Files.createDirectories(file.toAbsolutePath().getParent());
        Files.writeString(file, em.out);
        return em.finalNames.get(c.top());
    }

    /** The emitted name of the top module (after deduplication). */
    public static String topName(Circuit c) {
        var em = new SvEmitter();
        for (var m : c.modulesBottomUp()) em.module(m);
        return em.finalNames.get(c.top());
    }

    /** The SystemVerilog identifier used for a Tessera name (keywords get a trailing underscore). */
    public static String safe(String n) {
        return KEYWORDS.contains(n) ? n + "_" : n;
    }

    private void module(ModuleDef m) {
        var w = new ModuleWriter(m);
        String body = w.write();
        String existing = bodyToName.get(body);
        if (existing != null) {
            finalNames.put(m, existing);
            return;
        }
        String name = safe(m.name());
        for (int k = 1; !usedModuleNames.add(name); k++) name = safe(m.name()) + "_" + k;
        bodyToName.put(body, name);
        finalNames.put(m, name);
        out.append('\n').append("module ").append(name).append(body).append("endmodule\n");
        if (table != null) w.table(name);
    }

    private final class ModuleWriter {
        final ModuleDef m;
        final Map<Object, String> names = new IdentityHashMap<>();
        final Map<PortKey, String> portNames = new HashMap<>();
        final Set<String> used = new HashSet<>();
        final StringBuilder decls = new StringBuilder();
        final StringBuilder logic = new StringBuilder();
        int temp;
        /** While emitting a driver: the signal (or memory, or instance port) it drives, and where that was made. */
        String feeds = "";
        SourceLoc feedsLoc;
        /** Each temp's operation, what it feeds and where, in claim order (for the name table). */
        final List<Object[]> temps = new java.util.ArrayList<>();

        ModuleWriter(ModuleDef m) {
            this.m = m;
        }

        String write() {
            for (var s : m.ports()) names.put(s, claim(safe(s.name())));
            for (var s : m.signals()) names.put(s, claim(safe(s.name())));
            for (var mem : m.mems()) names.put(mem, claim(safe(mem.name())));
            for (var i : m.instances()) {
                names.put(i, claim(safe(i.name())));
                for (var p : i.def().ports())
                    if (p.kind() == Signal.Kind.OUTPUT) portNames.put(new PortKey(i, p), claim(safe(i.name() + "_" + p.name())));
            }

            var header = new StringBuilder(" (\n");
            var ports = m.ports();
            for (int k = 0; k < ports.size(); k++) {
                var p = ports.get(k);
                header.append("  ").append(p.kind() == Signal.Kind.INPUT ? "input  " : "output ")
                        .append("logic ").append(range(p.width())).append(names.get(p))
                        .append(k + 1 < ports.size() ? ",\n" : "\n");
            }
            header.append(");\n");

            for (var s : m.signals()) decls.append("  logic ").append(range(s.width())).append(names.get(s)).append(";\n");
            for (var mem : m.mems())
                // registers with several write ports: FPGA tools infer no such RAM (see memory())
                decls.append(multiWrite(mem) ? "  (* ram_style = \"registers\" *) logic " : "  logic ").append(range(mem.width())).append(names.get(mem))
                        .append(" [0:").append(mem.depth() - 1).append("];\n");
            for (var i : m.instances())
                for (var p : i.def().ports())
                    if (p.kind() == Signal.Kind.OUTPUT)
                        decls.append("  logic ").append(range(p.width())).append(portNames.get(new PortKey(i, p))).append(";\n");

            // a true dual-port RAM's read registers are written in its port blocks (see memory())
            var portRegs = new HashSet<Signal>();
            for (var mem : m.mems()) for (var p : mem.ports()) portRegs.add(p.rdata());
            for (var s : m.allSignals()) {
                if (s.kind() == Signal.Kind.INPUT || s.driver() == null || portRegs.contains(s)) continue;
                feeds = s.name();
                feedsLoc = s.loc();
                String v = operand(s.driver());
                if (s.kind() == Signal.Kind.REG) {
                    var d = s.domain();
                    String clk = names.get(d.clock()), rst = names.get(d.reset());
                    var k = d.resetKind();
                    boolean hasReset = s.resetValue() != null;
                    logic.append("  always_ff @(posedge ").append(clk);
                    if (hasReset && k.async()) logic.append(k.activeHigh() ? " or posedge " : " or negedge ").append(rst);
                    logic.append(") begin\n");
                    if (hasReset) {
                        logic.append("    if (").append(k.activeHigh() ? rst : "!" + rst).append(") ").append(names.get(s)).append(" <= ")
                                .append(literal(s.resetValue(), s.width())).append(";\n");
                        logic.append("    else ").append(names.get(s)).append(" <= ").append(v).append(";\n");
                    } else {
                        logic.append("    ").append(names.get(s)).append(" <= ").append(v).append(";\n");
                    }
                    logic.append("  end\n");
                } else {
                    logic.append("  assign ").append(names.get(s)).append(" = ").append(v).append(";\n");
                }
            }
            for (var mem : m.mems()) memory(mem);
            properties();
            for (var i : m.instances()) {
                var conns = new LinkedHashMap<String, String>();
                for (var p : i.def().ports()) {
                    feeds = i.name() + "." + p.name();
                    feedsLoc = i.loc();
                    if (p.kind() == Signal.Kind.INPUT) conns.put(p.name(), operand(i.inputs().get(p)));
                    else conns.put(p.name(), portNames.get(new PortKey(i, p)));
                }
                logic.append("  ").append(finalNames.get(i.def())).append(' ').append(names.get(i)).append(" (\n");
                int k = 0;
                for (var e : conns.entrySet()) {
                    logic.append("    .").append(safe(e.getKey())).append('(').append(e.getValue()).append(')')
                            .append(++k < conns.size() ? ",\n" : "\n");
                }
                logic.append("  );\n");
            }
            return header.toString() + decls + logic;
        }

        private void memory(MemDef mem) {
            String n = names.get(mem);
            if (mem.init() != null) {
                logic.append("  initial begin\n");
                long[] init = mem.init();
                for (int k = 0; k < init.length; k++) {
                    logic.append("    ").append(n).append('[').append(k).append("] = ")
                            .append(literal(BigInteger.valueOf(init[k]).and(mask(mem.width())), mem.width())).append(";\n");
                }
                logic.append("  end\n");
            }
            if (multiWrite(mem)) {
                // one block, writes in order (a later write to the same address wins, as in the simulator): a memory
                // driven from two always blocks is multi-driven for synthesis tools
                logic.append("  always_ff @(posedge ").append(names.get(mem.writes().getFirst().domain().clock())).append(") begin\n");
                for (var w : mem.writes()) {
                    String en = operand(w.en()), a = operand(w.addr()), d = operand(w.data());
                    logic.append("    if (").append(en).append(") ").append(n).append('[').append(a).append("] <= ").append(d).append(";\n");
                }
                logic.append("  end\n");
                return;
            }
            if (!mem.ports().isEmpty()) {
                // a true dual-port RAM in the vendors' template: one block per port, each reading (read-first) and
                // writing its own address. Plain `always`: SystemVerilog forbids two always_ff writing one variable
                for (var p : mem.ports()) {
                    feeds = mem.name();
                    feedsLoc = p.write().loc();
                    String en = operand(p.en()), we = operand(p.we()), a = operand(p.addr()), d = operand(p.data());
                    logic.append("  always @(posedge ").append(names.get(p.write().domain().clock())).append(") begin\n")
                            .append("    if (").append(en).append(") begin\n")
                            .append("      if (").append(we).append(") ").append(n).append('[').append(a).append("] <= ").append(d).append(";\n")
                            .append("      ").append(names.get(p.rdata())).append(" <= ").append(n).append('[').append(a).append("];\n")
                            .append("    end\n")
                            .append("  end\n");
                }
                return;
            }
            for (var w : mem.writes()) {
                feeds = mem.name();
                feedsLoc = w.loc();
                String en = operand(w.en()), a = operand(w.addr()), d = operand(w.data());
                logic.append("  always_ff @(posedge ").append(names.get(w.domain().clock())).append(") begin\n")
                        .append("    if (").append(en).append(") ").append(n).append('[').append(a).append("] <= ")
                        .append(d).append(";\n")
                        .append("  end\n");
            }
        }

        /** A memory built as registers ({@code Mem.asRegisters}) with two or more write ports, all in one clock domain. */
        private static boolean multiWrite(MemDef mem) {
            if (!mem.registers() || mem.writes().size() < 2) return false;
            var clock = mem.writes().getFirst().domain();
            return mem.writes().stream().allMatch(w -> w.domain() == clock);
        }

        /** Formal properties, emitted only when {@code FORMAL} is defined. */
        private void properties() {
            if (m.assertions().isEmpty()) return;
            var sb = new StringBuilder("`ifdef FORMAL\n");
            var labels = new HashSet<String>();
            for (var a : m.assertions()) {
                feeds = a.name();
                feedsLoc = null;
                String c = operand(a.cond()), en = operand(a.enable());
                String label = a.name().replaceAll("[^A-Za-z0-9_]", "_");
                if (label.isEmpty() || !Character.isLetter(label.charAt(0))) label = "p_" + label;
                String base = label;
                for (int k = 1; !labels.add(label); k++) label = base + "_" + k;
                String kw = switch (a.kind()) {
                    case ASSERT -> "assert";
                    case ASSUME -> "assume";
                    case COVER -> "cover";
                };
                sb.append("  always @(posedge ").append(names.get(a.domain().clock())).append(") begin\n")
                        .append("    if (").append(en).append(") ").append(label).append(": ").append(kw)
                        .append(" (").append(c).append(");\n")
                        .append("  end\n");
            }
            logic.append(sb).append("`endif\n");
        }

        record PortKey(Object inst, Signal port) {}

        /** This module's entries of the name table, under its emitted name. */
        void table(String module) {
            for (var s : m.allSignals()) {
                String kind = s.isPort() ? "port" : s.kind() == Signal.Kind.REG ? "reg" : "wire";
                add(module, names.get(s), kind, s.width(), s.name(), "", "", s.loc());
            }
            for (var mem : m.mems()) add(module, names.get(mem), "mem", mem.width(), mem.name(), "", "", mem.loc());
            for (var i : m.instances()) {
                add(module, names.get(i), "inst", 0, i.name(), "", finalNames.get(i.def()), i.loc());
                for (var p : i.def().ports())
                    if (p.kind() == Signal.Kind.OUTPUT)
                        add(module, portNames.get(new PortKey(i, p)), "wire", p.width(), i.name() + "." + p.name(), "", "", i.loc());
            }
            for (var t : temps) {
                Expr e = (Expr) t[1];
                String op = switch (e) {
                    case Expr.Op o -> o.kind().name().toLowerCase();
                    case Expr.MemRead mr -> "read " + mr.mem().name();
                    default -> "literal";
                };
                SourceLoc own = opLocs.get(e);
                add(module, (String) t[0], "temp", e.width(), (String) t[2], op, "", own != null && own.line() >= 0 ? own : (SourceLoc) t[3]);
            }
        }

        private void add(String module, String name, String kind, int width, String signal, String op, String child, SourceLoc loc) {
            if (name == null) return;
            boolean known = loc != null && loc.line() >= 0;
            table.add(new Name(module, name, kind, width, signal, op, child, known ? loc.file() : "", known ? loc.line() : -1));
        }

        String claim(String base) {
            String n = base;
            for (int k = 1; !used.add(n); k++) n = base + "_" + k;
            return n;
        }

        /** A name or sized literal for {@code e}. */
        String operand(Expr e) {
            return switch (e) {
                case Expr.Lit l -> literal(l.value(), l.width());
                case Expr.Ref r -> names.get(r.signal());
                case Expr.InstRef ir -> portNames.get(new PortKey(ir.instance(), ir.port()));
                case Expr.MemRead mr -> temp(e, names.get(mr.mem()) + "[" + operand(mr.addr()) + "]");
                case Expr.Op op when op.kind() == org.eninehq.tessera.ir.OpKind.CAST -> operand(op.arg(0));
                case Expr.Op op -> {
                    String n = names.get(op);
                    yield n != null ? n : temp(op, opText(op));
                }
            };
        }

        /** A name (never a literal) for {@code e}, so it can be bit-selected. */
        String nameOf(Expr e) {
            if (e instanceof Expr.Lit l) {
                String n = names.get(l);
                return n != null ? n : temp(l, literal(l.value(), l.width()));
            }
            return operand(e);
        }

        String temp(Expr e, String text) {
            String n = names.get(e);
            if (n != null) return n;
            n = claim("_t" + temp++);
            names.put(e, n);
            temps.add(new Object[]{n, e, feeds, feedsLoc});
            decls.append("  wire ").append(range(e.width())).append(n).append(" = ").append(text).append(";\n");
            return n;
        }

        String ext(Expr e, int to) {
            int from = e.width();
            if (from == to) return operand(e);
            if (e instanceof Expr.Lit l) {
                BigInteger v = l.value();
                if (l.signed() && v.testBit(from - 1)) v = v.subtract(BigInteger.ONE.shiftLeft(from)).and(mask(to));
                return literal(v, to);
            }
            String a = nameOf(e);
            String fill = e.signed() ? (from == 1 ? a : a + "[" + (from - 1) + "]") : "1'b0";
            return "{{" + (to - from) + "{" + fill + "}}, " + a + "}";
        }

        String bit(String name, int width, int i) {
            return width == 1 ? name : name + "[" + i + "]";
        }

        String slice(String name, int width, int hi, int lo) {
            if (hi == lo) return bit(name, width, hi);
            if (hi == width - 1 && lo == 0) return name;
            return name + "[" + hi + ":" + lo + "]";
        }

        String opText(Expr.Op op) {
            int w = op.width();
            return switch (op.kind()) {
                case ADD -> ext(op.arg(0), w) + " + " + ext(op.arg(1), w);
                case SUB -> ext(op.arg(0), w) + " - " + ext(op.arg(1), w);
                case MUL -> ext(op.arg(0), w) + " * " + ext(op.arg(1), w);
                case AND -> ext(op.arg(0), w) + " & " + ext(op.arg(1), w);
                case OR -> ext(op.arg(0), w) + " | " + ext(op.arg(1), w);
                case XOR -> ext(op.arg(0), w) + " ^ " + ext(op.arg(1), w);
                case NOT -> "~" + operand(op.arg(0));
                case SHL -> "{" + operand(op.arg(0)) + ", " + op.p0() + "'b0}";
                case SHR, SRA -> {
                    var a = op.arg(0);
                    String n = nameOf(a);
                    int aw = a.width(), k = op.p0();
                    String fill = op.kind() == org.eninehq.tessera.ir.OpKind.SRA ? bit(n, aw, aw - 1) : "1'b0";
                    yield k >= aw ? "{" + w + "{" + fill + "}}"
                            : "{{" + k + "{" + fill + "}}, " + slice(n, aw, aw - 1, k) + "}";
                }
                case DSHL -> operand(op.arg(0)) + " << " + operand(op.arg(1));
                case DSHR -> operand(op.arg(0)) + " >> " + operand(op.arg(1));
                case DSRA -> "$unsigned($signed(" + operand(op.arg(0)) + ") >>> " + operand(op.arg(1)) + ")";
                case EQ, NE, LT, LE, GT, GE -> {
                    int mw = Math.max(op.arg(0).width(), op.arg(1).width());
                    String a = ext(op.arg(0), mw), b = ext(op.arg(1), mw);
                    if (op.arg(0).signed()) {
                        a = "$signed(" + a + ")";
                        b = "$signed(" + b + ")";
                    }
                    String sym = switch (op.kind()) {
                        case EQ -> "==";
                        case NE -> "!=";
                        case LT -> "<";
                        case LE -> "<=";
                        case GT -> ">";
                        default -> ">=";
                    };
                    yield a + " " + sym + " " + b;
                }
                case MUX -> operand(op.arg(0)) + " ? " + ext(op.arg(1), w) + " : " + ext(op.arg(2), w);
                case CAT -> {
                    var sb = new StringBuilder("{");
                    for (int k = 0; k < op.args().size(); k++) {
                        if (k > 0) sb.append(", ");
                        sb.append(operand(op.arg(k)));
                    }
                    yield sb.append('}').toString();
                }
                case SLICE -> slice(nameOf(op.arg(0)), op.arg(0).width(), op.p0(), op.p1());
                case EXT -> ext(op.arg(0), w);
                case CAST -> operand(op.arg(0));
                case ANDR -> "&" + operand(op.arg(0));
                case ORR -> "|" + operand(op.arg(0));
                case XORR -> "^" + operand(op.arg(0));
            };
        }
    }

    static String range(int width) {
        return width == 1 ? "" : "[" + (width - 1) + ":0] ";
    }

    static BigInteger mask(int width) {
        return BigInteger.ONE.shiftLeft(width).subtract(BigInteger.ONE);
    }

    static String literal(BigInteger v, int width) {
        return width + "'h" + v.toString(16);
    }
}
