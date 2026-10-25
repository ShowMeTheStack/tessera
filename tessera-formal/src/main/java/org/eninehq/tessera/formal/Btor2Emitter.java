package org.eninehq.tessera.formal;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.eninehq.tessera.ir.Assertion;
import org.eninehq.tessera.ir.OpKind;
import org.eninehq.tessera.sim.BitVec;
import org.eninehq.tessera.sim.Netlist;

/**
 * Emits a design as a BTOR2 model (the word-level format of the hardware model checking
 * competition, read by btormc, pono, AVR, ...). Assertions become {@code bad} properties and
 * assumptions {@code constraint}s. An extra state {@code tessera_first} is 1 in the first
 * cycle only; it holds the reset inputs active in that cycle, as {@link Formal#bmc} does.
 */
public final class Btor2Emitter {
    private static final OpKind[] KINDS = OpKind.values();

    private final Netlist net;
    private final StringBuilder out = new StringBuilder();
    private int next = 1;
    private final Map<Integer, Integer> bvSorts = new HashMap<>();
    private final Map<Integer, Integer> slotNode = new HashMap<>();
    private final Map<Integer, Integer> memNode = new HashMap<>();
    private final Map<Integer, Integer> memSort = new HashMap<>();
    private final Map<Integer, Integer> stateSlots = new LinkedHashMap<>();
    /** Inputs in declaration order (for witnesses): path to BTOR2 input index. */
    private final List<String> inputOrder = new ArrayList<>();
    private final List<Integer> inputWidths = new ArrayList<>();
    private final List<String> stateOrder = new ArrayList<>();
    private final List<Integer> stateWidths = new ArrayList<>();

    private Btor2Emitter(Netlist net) {
        this.net = net;
    }

    /** The BTOR2 text and the order of its inputs (for writing witnesses). */
    public record Model(String text, List<String> inputs, List<Integer> inputWidths, List<String> states, List<Integer> stateWidths) {}

    public static Model emit(Netlist net) {
        var e = new Btor2Emitter(net);
        e.run();
        return new Model(e.out.toString(), List.copyOf(e.inputOrder), List.copyOf(e.inputWidths),
                java.util.Collections.unmodifiableList(new ArrayList<>(e.stateOrder)), List.copyOf(e.stateWidths));
    }

    private int line(String s) {
        int id = next++;
        out.append(id).append(' ').append(s).append('\n');
        return id;
    }

    private int sort(int w) {
        return bvSorts.computeIfAbsent(w, k -> line("sort bitvec " + k));
    }

    private int constant(BigInteger v, int w) {
        String bits = v.toString(2);
        return line("const " + sort(w) + " " + "0".repeat(Math.max(0, w - bits.length())) + bits);
    }

    private int addrWidth(Netlist.Mem m) {
        return Math.max(1, 64 - Long.numberOfLeadingZeros(m.depth() - 1L));
    }

    private int node(int slot) {
        Integer n = slotNode.get(slot);
        if (n != null) return n;
        int w = net.slotWidth(slot);
        if (net.isConstant(slot)) {
            n = constant(BitVec.toBig(net.initialValues(), slot, w), w);
        } else {
            n = line("input " + sort(w) + " tessera_undriven_" + slot);
            inputOrder.add("<undriven " + slot + ">");
            inputWidths.add(w);
        }
        slotNode.put(slot, n);
        return n;
    }

    private void run() {
        // inputs
        for (var e : net.inputSlots().entrySet()) {
            int w = net.slotWidth(e.getValue());
            slotNode.put(e.getValue(), line("input " + sort(w) + " " + e.getKey()));
            inputOrder.add(e.getKey());
            inputWidths.add(w);
        }
        // constants used as initial values must precede the states
        int one = constant(BigInteger.ONE, 1), zero = constant(BigInteger.ZERO, 1);
        var memZero = new HashMap<Integer, Integer>();
        for (int m = 0; m < net.mems().size(); m++) memZero.put(m, constant(BigInteger.ZERO, net.mems().get(m).width()));
        // states
        int first = line("state " + sort(1) + " tessera_first");
        stateOrder.add("tessera_first");
        stateWidths.add(1);
        for (var r : net.regs()) {
            int s = line("state " + sort(r.width()) + " " + (r.path() == null ? "r" + r.slot() : r.path()));
            slotNode.put(r.slot(), s);
            stateOrder.add(r.path());
            stateWidths.add(r.width());
        }
        for (int m = 0; m < net.mems().size(); m++) {
            var mem = net.mems().get(m);
            int as = line("sort array " + sort(addrWidth(mem)) + " " + sort(mem.width()));
            memSort.put(m, as);
            memNode.put(m, line("state " + as + " " + mem.path()));
            stateOrder.add(null);
            stateWidths.add(0);
        }
        // combinational logic
        for (var in : net.insns()) slotNode.put(in.dst(), insn(in));
        // initial values: the first-cycle flag, zero/initialised memories
        line("init " + sort(1) + " " + first + " " + one);
        line("next " + sort(1) + " " + first + " " + zero);
        for (int m = 0; m < net.mems().size(); m++) {
            var mem = net.mems().get(m);
            int v = memZero.get(m);
            if (mem.init() == null) {
                line("init " + memSort.get(m) + " " + memNode.get(m) + " " + v);
            } else {
                // contents: a constant array updated with the initial words, fixed in the first cycle
                int arr = line("state " + memSort.get(m) + " " + mem.path() + "_init");
                stateOrder.add(null);
                stateWidths.add(0);
                line("init " + memSort.get(m) + " " + arr + " " + v);
                line("next " + memSort.get(m) + " " + arr + " " + arr);
                int img = arr;
                int aw = addrWidth(mem);
                for (int k = 0; k < mem.init().length; k++)
                    img = line("write " + memSort.get(m) + " " + img + " " + constant(BigInteger.valueOf(k), aw) + " "
                            + constant(BigInteger.valueOf(mem.init()[k]).and(BigInteger.ONE.shiftLeft(mem.width()).subtract(BigInteger.ONE)), mem.width()));
                int same = line("eq " + sort(1) + " " + memNode.get(m) + " " + img);
                line("constraint " + line("or " + sort(1) + " -" + first + " " + same));
            }
        }
        // next state
        for (var r : net.regs()) {
            int nx = node(r.next());
            if (r.hasReset()) {
                int active = line("eq " + sort(1) + " " + node(r.resetSlot()) + " " + constant(BigInteger.valueOf(r.resetActive()), 1));
                nx = line("ite " + sort(r.width()) + " " + active + " "
                        + constant(BitVec.toBig(r.resetWords(), 0, r.width()), r.width()) + " " + nx);
            }
            line("next " + sort(r.width()) + " " + slotNode.get(r.slot()) + " " + nx);
        }
        for (int m = 0; m < net.mems().size(); m++) {
            var mem = net.mems().get(m);
            int aw = addrWidth(mem);
            int v = memNode.get(m);
            for (var w : net.memWrites()) {
                if (w.mem() != m) continue;
                int addr = fit(node(w.addr()), net.slotWidth(w.addr()), aw, false);
                int st = line("write " + memSort.get(m) + " " + v + " " + addr + " " + node(w.data()));
                int en = node(w.en());
                int inRange = inRange(node(w.addr()), net.slotWidth(w.addr()), mem.depth());
                if (inRange > 0) en = line("and " + sort(1) + " " + en + " " + inRange);
                v = line("ite " + memSort.get(m) + " " + en + " " + st + " " + v);
            }
            line("next " + memSort.get(m) + " " + memNode.get(m) + " " + v);
        }
        // resets: active in the first cycle, inactive afterwards
        var resets = new LinkedHashMap<Integer, Long>();
        for (var r : net.regs()) if (r.hasReset()) resets.put(r.resetSlot(), r.resetActive());
        for (var e : resets.entrySet()) {
            if (!net.inputSlots().containsValue(e.getKey())) continue;
            int lvl = line("eq " + sort(1) + " " + node(e.getKey()) + " " + constant(BigInteger.valueOf(e.getValue()), 1));
            // first ? active : !active
            int c = line("ite " + sort(1) + " " + first + " " + lvl + " -" + lvl);
            line("constraint " + c);
        }
        // properties
        for (var ch : net.checks()) {
            int en = node(ch.en()), cond = node(ch.cond());
            switch (ch.kind()) {
                case ASSERT -> {
                    int bad = line("and " + sort(1) + " " + en + " -" + cond);
                    line("bad " + bad + " " + ch.name());
                }
                case ASSUME -> line("constraint " + line("or " + sort(1) + " -" + en + " " + cond));
                case COVER -> {}
            }
        }
        for (var ch : net.checks()) if (ch.kind() == Assertion.Kind.COVER) out.append("; cover ").append(ch.name()).append('\n');
    }

    /** {@code n} (width {@code from}) resized to {@code to} bits. */
    private int fit(int n, int from, int to, boolean signed) {
        if (from == to) return n;
        if (from < to) return line((signed ? "sext " : "uext ") + sort(to) + " " + n + " " + (to - from));
        return line("slice " + sort(to) + " " + n + " " + (to - 1) + " 0");
    }

    private int inRange(int a, int w, int depth) {
        if (BigInteger.valueOf(depth).compareTo(BigInteger.ONE.shiftLeft(w)) >= 0) return -1;
        return line("ult " + sort(1) + " " + a + " " + constant(BigInteger.valueOf(depth), w));
    }

    private int arg(Netlist.Insn in, int k, int w) {
        return fit(node(in.args()[k]), in.argW()[k], w, in.argS()[k]);
    }

    private int insn(Netlist.Insn in) {
        int w = in.width();
        int s = sort(w);
        if (in.op() == Netlist.MEMREAD) {
            var mem = net.mems().get(in.p0());
            int aw = addrWidth(mem);
            int a = node(in.args()[0]);
            int rd = line("read " + s + " " + memNode.get(in.p0()) + " " + fit(a, in.argW()[0], aw, false));
            int range = inRange(a, in.argW()[0], mem.depth());
            return range < 0 ? rd : line("ite " + s + " " + range + " " + rd + " " + constant(BigInteger.ZERO, w));
        }
        return switch (KINDS[in.op()]) {
            case ADD -> line("add " + s + " " + arg(in, 0, w) + " " + arg(in, 1, w));
            case SUB -> line("sub " + s + " " + arg(in, 0, w) + " " + arg(in, 1, w));
            case MUL -> line("mul " + s + " " + arg(in, 0, w) + " " + arg(in, 1, w));
            case AND -> line("and " + s + " " + arg(in, 0, w) + " " + arg(in, 1, w));
            case OR -> line("or " + s + " " + arg(in, 0, w) + " " + arg(in, 1, w));
            case XOR -> line("xor " + s + " " + arg(in, 0, w) + " " + arg(in, 1, w));
            case NOT -> line("not " + s + " " + arg(in, 0, w));
            case SHL -> line("concat " + s + " " + node(in.args()[0]) + " " + constant(BigInteger.ZERO, in.p0()));
            case SHR -> in.p0() >= w ? constant(BigInteger.ZERO, w)
                    : line("srl " + s + " " + node(in.args()[0]) + " " + constant(BigInteger.valueOf(in.p0()), w));
            case SRA -> line("sra " + s + " " + node(in.args()[0]) + " " + constant(BigInteger.valueOf(Math.min(in.p0(), w - 1)), w));
            case DSHL, DSHR, DSRA -> {
                int aw = in.argW()[0], sw = in.argW()[1], m = Math.max(aw, sw);
                boolean sv = KINDS[in.op()] == OpKind.DSRA;
                int a = fit(node(in.args()[0]), aw, m, sv);
                int sh = fit(node(in.args()[1]), sw, m, false);
                String op = switch (KINDS[in.op()]) {
                    case DSHL -> "sll";
                    case DSHR -> "srl";
                    default -> "sra";
                };
                int r = line(op + " " + sort(m) + " " + a + " " + sh);
                yield m == w ? r : fit(r, m, w, false);
            }
            case EQ, NE, LT, LE, GT, GE -> {
                int m = Math.max(in.argW()[0], in.argW()[1]);
                int a = arg(in, 0, m), b = arg(in, 1, m);
                boolean sg = in.argS()[0];
                String op = switch (KINDS[in.op()]) {
                    case EQ -> "eq";
                    case NE -> "neq";
                    case LT -> sg ? "slt" : "ult";
                    case LE -> sg ? "slte" : "ulte";
                    case GT -> sg ? "sgt" : "ugt";
                    default -> sg ? "sgte" : "ugte";
                };
                yield line(op + " " + sort(1) + " " + a + " " + b);
            }
            case MUX -> line("ite " + s + " " + node(in.args()[0]) + " " + arg(in, 1, w) + " " + arg(in, 2, w));
            case CAT -> {
                int acc = node(in.args()[0]);
                int accW = in.argW()[0];
                for (int k = 1; k < in.args().length; k++) {
                    accW += in.argW()[k];
                    acc = line("concat " + sort(accW) + " " + acc + " " + node(in.args()[k]));
                }
                yield acc;
            }
            case SLICE -> line("slice " + s + " " + node(in.args()[0]) + " " + in.p0() + " " + in.p1());
            case EXT -> arg(in, 0, w);
            case CAST -> node(in.args()[0]);
            case ANDR -> line("redand " + sort(1) + " " + node(in.args()[0]));
            case ORR -> line("redor " + sort(1) + " " + node(in.args()[0]));
            case XORR -> line("redxor " + sort(1) + " " + node(in.args()[0]));
        };
    }

    /** A BTOR2 witness for a counterexample found by {@link Formal}, for btorsim or other tools. */
    public static String witness(Model model, Formal.Result r, int badIndex) {
        var sb = new StringBuilder("sat\nb").append(badIndex).append('\n');
        sb.append("#0\n");
        for (int i = 0; i < model.states().size(); i++) {
            String path = model.states().get(i);
            if (i == 0) {
                sb.append(i).append(" 1 tessera_first@0\n");
                continue;
            }
            var v = path == null ? null : r.initialState().get(path);
            if (v == null) continue;
            sb.append(i).append(' ').append(bits(v, model.stateWidths().get(i))).append(' ').append(path).append("@0\n");
        }
        for (int t = 0; t < r.counterexample().size(); t++) {
            sb.append('@').append(t).append('\n');
            var row = r.counterexample().get(t);
            for (int i = 0; i < model.inputs().size(); i++) {
                var name = model.inputs().get(i);
                var v = row.getOrDefault(name, BigInteger.ZERO);
                sb.append(i).append(' ').append(bits(v, model.inputWidths().get(i))).append(' ').append(name).append('@').append(t).append('\n');
            }
        }
        return sb.append(".\n").toString();
    }

    private static String bits(BigInteger v, int w) {
        String b = v.toString(2);
        return "0".repeat(Math.max(0, w - b.length())) + b;
    }
}
