package org.eninehq.tessera.formal;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.eninehq.tessera.ir.OpKind;
import org.eninehq.tessera.sim.BitVec;
import org.eninehq.tessera.sim.Netlist;

/**
 * Translates a flattened netlist into SMT-LIB 2 (QF_ABV), one copy of the combinational
 * logic per time step.
 *
 * <p><b>Clocks.</b> A design with one clock domain ticks at every step, as in
 * {@code Simulator.step()}. With several domains, every step has one free bit {@code k<t>_<d>}
 * per domain, as in {@code Simulator.stepClocks}: a register, memory write port or property of
 * domain {@code d} acts at step {@code t} only when that bit is 1; otherwise the register keeps
 * its value. Nothing relates the domains unless {@link Formal} adds clock assumptions.
 *
 * <p><b>Asynchronous resets.</b> For a register with an asynchronous reset, the step's free or
 * defined state is its stored value {@code q<t>_<slot>}; the value the logic sees,
 * {@code s<t>_<slot>}, is the reset value whenever the reset is active, without waiting for an
 * edge, and the reset value is stored, so it holds after release until the next edge (the
 * simulator's {@code settle()}). Synchronous resets are applied at the edge, as before.
 */
final class SmtUnroller {
    private static final OpKind[] KINDS = OpKind.values();

    private final Netlist net;
    private final Set<Integer> regSlots = new HashSet<>();
    private final Set<Integer> inputSlots = new HashSet<>();
    private final Map<Integer, Netlist.Insn> producer = new HashMap<>();
    private final long[] init;
    final boolean arbitraryMemory;
    /** Registers with an asynchronous reset, by slot. */
    private final Map<Integer, Netlist.Reg> asyncRegs = new LinkedHashMap<>();
    /** Number of clock domains; more than one makes the ticks free. */
    final int domains;
    /**
     * Definition order of instructions ({@link Netlist.Insn}) and visible values of
     * asynchronous-reset registers ({@link Netlist.Reg}); null when there are no asynchronous resets
     * (then it is the netlist's own instruction order).
     */
    private List<Object> order;

    /** Initial memory contents as constant arrays ({@code (as const ...)}); yices lacks them. */
    boolean constArrays = true;

    SmtUnroller(Netlist net, boolean arbitraryMemory) {
        this.net = net;
        this.arbitraryMemory = arbitraryMemory;
        for (var r : net.regs()) regSlots.add(r.slot());
        inputSlots.addAll(net.inputSlots().values());
        for (var in : net.insns()) producer.put(in.dst(), in);
        this.init = net.initialValues();
        for (var r : net.regs()) if (r.hasReset() && r.asyncReset()) asyncRegs.put(r.slot(), r);
        this.domains = net.clockPaths().size();
        if (!asyncRegs.isEmpty()) computeOrder();
    }

    /** True when the domains tick independently (more than one clock). */
    boolean multiClock() {
        return domains > 1;
    }

    boolean isAsync(int slot) {
        return asyncRegs.containsKey(slot);
    }

    /** Topological order of the instructions and the asynchronous-reset registers' visible values. */
    private void computeOrder() {
        order = new ArrayList<>();
        var done = new HashSet<Integer>();
        var busy = new HashSet<Integer>();
        for (var in : net.insns()) ensure(in.dst(), done, busy);
        for (var r : asyncRegs.values()) ensure(r.slot(), done, busy);
    }

    private void ensure(int slot, Set<Integer> done, Set<Integer> busy) {
        if (done.contains(slot)) return;
        var r = asyncRegs.get(slot);
        var in = r == null ? producer.get(slot) : null;
        if (r == null && in == null) return;
        if (!busy.add(slot))
            throw new IllegalStateException("the asynchronous reset of register " + (r != null ? r.path() : "slot " + slot)
                    + " depends combinationally on its own value; the formal back end does not support that");
        if (r != null) ensure(r.resetSlot(), done, busy);
        else for (int a : in.args()) ensure(a, done, busy);
        busy.remove(slot);
        done.add(slot);
        order.add(r != null ? r : in);
    }

    static String bv(BigInteger v, int w) {
        var s = v.toString(2);
        return "#b" + "0".repeat(Math.max(0, w - s.length())) + s;
    }

    static String sort(int w) {
        return "(_ BitVec " + w + ")";
    }

    String name(int t, int slot) {
        return "s" + t + "_" + slot;
    }

    String memName(int t, int m) {
        return "m" + t + "_" + m;
    }

    /** The stored value of a register at step {@code t} (differs from the visible value only with an asynchronous reset). */
    String stateName(int t, int slot) {
        return asyncRegs.containsKey(slot) ? "q" + t + "_" + slot : name(t, slot);
    }

    /** The tick bit (width 1) of domain {@code d} at step {@code t}; only with several domains. */
    String tickName(int t, int d) {
        return "k" + t + "_" + d;
    }

    /** The condition that domain {@code d} has an edge at step {@code t}, or null when it always has one. */
    String tick(int t, int d) {
        return multiClock() ? "(= " + tickName(t, d) + " #b1)" : null;
    }

    /** {@code cond} gated by the tick of domain {@code d} at step {@code t}. */
    String gated(int t, int d, String cond) {
        String k = tick(t, d);
        return k == null ? cond : "(and " + k + " " + cond + ")";
    }

    /** True at step {@code t} when property {@code ch} is checked: its domain ticks and it is enabled. */
    String enabled(int t, Netlist.Check ch) {
        return gated(t, ch.domain(), "(= " + term(t, ch.en(), 1) + " #b1)");
    }

    /** True when register {@code r}'s reset is active at step {@code t}. */
    String resetActive(int t, Netlist.Reg r) {
        return "(= " + term(t, r.resetSlot(), 1) + " " + bv(BigInteger.valueOf(r.resetActive()), 1) + ")";
    }

    private int addrWidth(Netlist.Mem m) {
        return Math.max(1, 64 - Long.numberOfLeadingZeros(m.depth() - 1L));
    }

    /** The term for slot {@code slot} (of width {@code w}) at step {@code t}. */
    String term(int t, int slot, int w) {
        if (net.isConstant(slot)) return bv(BitVec.toBig(init, slot, w), w);
        return name(t, slot);
    }

    /** Declarations for step {@code t}: inputs, state (step 0 only, or when {@code freeState}), and every instruction. */
    void declareStep(StringBuilder out, int t, boolean freeState) {
        for (var e : net.inputSlots().entrySet()) {
            int slot = e.getValue();
            out.append("(declare-const ").append(name(t, slot)).append(' ').append(sort(net.slotWidth(slot))).append(")\n");
        }
        if (multiClock())
            for (int d = 0; d < domains; d++) out.append("(declare-const ").append(tickName(t, d)).append(" (_ BitVec 1))\n");
        if (t == 0 || freeState) {
            for (var r : net.regs())
                out.append("(declare-const ").append(stateName(t, r.slot())).append(' ').append(sort(r.width())).append(")\n");
            for (int m = 0; m < net.mems().size(); m++) {
                var mem = net.mems().get(m);
                String arr = "(Array " + sort(addrWidth(mem)) + " " + sort(mem.width()) + ")";
                if (arbitraryMemory || freeState) {
                    out.append("(declare-const ").append(memName(t, m)).append(' ').append(arr).append(")\n");
                } else if (!constArrays) {
                    // no constant arrays: pin every word of a small memory; a large one stays arbitrary
                    out.append("(declare-const ").append(memName(t, m)).append(' ').append(arr).append(")\n");
                    int aw = addrWidth(mem);
                    if (aw <= 12)
                        for (int k = 0; k < (1 << aw); k++) {
                            long v = mem.init() != null && k < mem.init().length ? mem.init()[k] : 0;
                            out.append("(assert (= (select ").append(memName(t, m)).append(' ').append(bv(BigInteger.valueOf(k), aw))
                                    .append(") ").append(bv(BigInteger.valueOf(v).and(mask(mem.width())), mem.width())).append("))\n");
                        }
                } else {
                    String v = "((as const " + arr + ") " + bv(BigInteger.ZERO, mem.width()) + ")";
                    if (mem.init() != null)
                        for (int k = 0; k < mem.init().length; k++)
                            v = "(store " + v + " " + bv(BigInteger.valueOf(k), addrWidth(mem)) + " "
                                    + bv(BigInteger.valueOf(mem.init()[k]).and(mask(mem.width())), mem.width()) + ")";
                    out.append("(define-fun ").append(memName(t, m)).append(" () ").append(arr).append(' ').append(v).append(")\n");
                }
            }
        }
        // slots that are neither inputs, registers, constants nor produced: free (undriven)
        for (int slot = 0; slot < net.slotCount(); slot++) {
            int w = net.slotWidth(slot);
            if (w == 0 || net.isConstant(slot) || inputSlots.contains(slot) || regSlots.contains(slot) || producer.containsKey(slot))
                continue;
            out.append("(declare-const ").append(name(t, slot)).append(' ').append(sort(w)).append(")\n");
        }
        if (order == null) {
            for (var in : net.insns()) defineInsn(out, t, in);
            return;
        }
        for (var o : order) {
            if (o instanceof Netlist.Insn in) {
                defineInsn(out, t, in);
            } else {
                // an asynchronous reset overrides the stored value at once
                var r = (Netlist.Reg) o;
                out.append("(define-fun ").append(name(t, r.slot())).append(" () ").append(sort(r.width())).append(" (ite ")
                        .append(resetActive(t, r)).append(' ').append(bv(BitVec.toBig(r.resetWords(), 0, r.width()), r.width()))
                        .append(' ').append(stateName(t, r.slot())).append("))\n");
            }
        }
    }

    private void defineInsn(StringBuilder out, int t, Netlist.Insn in) {
        out.append("(define-fun ").append(name(t, in.dst())).append(" () ").append(sort(in.width())).append(' ')
                .append(expr(t, in)).append(")\n");
    }

    static BigInteger mask(int w) {
        return BigInteger.ONE.shiftLeft(w).subtract(BigInteger.ONE);
    }

    /** Next state: define registers and memories of step {@code t + 1} from step {@code t}. */
    void transition(StringBuilder out, int t) {
        for (var r : net.regs()) {
            String next = term(t, r.next(), r.width());
            String v = next;
            if (r.hasReset())
                v = "(ite " + resetActive(t, r) + " " + bv(BitVec.toBig(r.resetWords(), 0, r.width()), r.width()) + " " + next + ")";
            // no edge: keep the value (the visible one, so that a reset applied asynchronously stays)
            String k = tick(t, r.domain());
            if (k != null) v = "(ite " + k + " " + v + " " + term(t, r.slot(), r.width()) + ")";
            out.append("(define-fun ").append(stateName(t + 1, r.slot())).append(" () ").append(sort(r.width())).append(' ')
                    .append(v).append(")\n");
        }
        for (int m = 0; m < net.mems().size(); m++) {
            var mem = net.mems().get(m);
            int aw = addrWidth(mem);
            String arr = "(Array " + sort(aw) + " " + sort(mem.width()) + ")";
            String v = memName(t, m);
            for (var w : net.memWrites()) {
                if (w.mem() != m) continue;
                int awIn = net.slotWidth(w.addr());
                String addr = fitAddr(term(t, w.addr(), awIn), awIn, aw);
                String inRange = inRange(term(t, w.addr(), awIn), awIn, mem.depth());
                String en = gated(t, w.domain(), "(and (= " + term(t, w.en(), 1) + " #b1) " + inRange + ")");
                v = "(ite " + en + " (store " + v + " " + addr + " " + term(t, w.data(), mem.width()) + ") " + v + ")";
            }
            out.append("(define-fun ").append(memName(t + 1, m)).append(" () ").append(arr).append(' ').append(v).append(")\n");
        }
    }

    private static String fitAddr(String a, int from, int to) {
        if (from == to) return a;
        if (from < to) return "((_ zero_extend " + (to - from) + ") " + a + ")";
        return "((_ extract " + (to - 1) + " 0) " + a + ")";
    }

    private static String inRange(String a, int w, int depth) {
        if (BigInteger.valueOf(depth).compareTo(BigInteger.ONE.shiftLeft(w)) >= 0) return "true";
        return "(bvult " + a + " " + bv(BigInteger.valueOf(depth), w) + ")";
    }

    /** Operand {@code k} extended (by its signedness) to {@code w} bits. */
    private String arg(int t, Netlist.Insn in, int k, int w) {
        int aw = in.argW()[k];
        String a = term(t, in.args()[k], aw);
        if (aw == w) return a;
        if (aw > w) return "((_ extract " + (w - 1) + " 0) " + a + ")";
        return "((_ " + (in.argS()[k] ? "sign_extend " : "zero_extend ") + (w - aw) + ") " + a + ")";
    }

    private static String bool(String cond) {
        return "(ite " + cond + " #b1 #b0)";
    }

    String expr(int t, Netlist.Insn in) {
        int w = in.width();
        if (in.op() == Netlist.MEMREAD) {
            var mem = net.mems().get(in.p0());
            int aw = addrWidth(mem), awIn = in.argW()[0];
            String a = term(t, in.args()[0], awIn);
            String sel = "(select " + memName(t, in.p0()) + " " + fitAddr(a, awIn, aw) + ")";
            String range = inRange(a, awIn, mem.depth());
            return range.equals("true") ? sel : "(ite " + range + " " + sel + " " + bv(BigInteger.ZERO, w) + ")";
        }
        return switch (KINDS[in.op()]) {
            case ADD -> "(bvadd " + arg(t, in, 0, w) + " " + arg(t, in, 1, w) + ")";
            case SUB -> "(bvsub " + arg(t, in, 0, w) + " " + arg(t, in, 1, w) + ")";
            case MUL -> "(bvmul " + arg(t, in, 0, w) + " " + arg(t, in, 1, w) + ")";
            case AND -> "(bvand " + arg(t, in, 0, w) + " " + arg(t, in, 1, w) + ")";
            case OR -> "(bvor " + arg(t, in, 0, w) + " " + arg(t, in, 1, w) + ")";
            case XOR -> "(bvxor " + arg(t, in, 0, w) + " " + arg(t, in, 1, w) + ")";
            case NOT -> "(bvnot " + arg(t, in, 0, w) + ")";
            case SHL -> "(concat " + term(t, in.args()[0], in.argW()[0]) + " " + bv(BigInteger.ZERO, in.p0()) + ")";
            case SHR -> in.p0() >= w ? bv(BigInteger.ZERO, w)
                    : "(bvlshr " + term(t, in.args()[0], w) + " " + bv(BigInteger.valueOf(in.p0()), w) + ")";
            case SRA -> "(bvashr " + term(t, in.args()[0], w) + " " + bv(BigInteger.valueOf(Math.min(in.p0(), w - 1)), w) + ")";
            case DSHL, DSHR, DSRA -> {
                int aw = in.argW()[0], sw = in.argW()[1];
                int m = Math.max(aw, sw);
                boolean signedVal = KINDS[in.op()] == OpKind.DSRA;
                String a = term(t, in.args()[0], aw);
                if (m > aw) a = "((_ " + (signedVal ? "sign_extend " : "zero_extend ") + (m - aw) + ") " + a + ")";
                String s = term(t, in.args()[1], sw);
                if (m > sw) s = "((_ zero_extend " + (m - sw) + ") " + s + ")";
                String op = switch (KINDS[in.op()]) {
                    case DSHL -> "bvshl";
                    case DSHR -> "bvlshr";
                    default -> "bvashr";
                };
                String r = "(" + op + " " + a + " " + s + ")";
                yield m == w ? r : "((_ extract " + (w - 1) + " 0) " + r + ")";
            }
            case EQ, NE, LT, LE, GT, GE -> {
                int m = Math.max(in.argW()[0], in.argW()[1]);
                String a = arg(t, in, 0, m), b = arg(t, in, 1, m);
                boolean s = in.argS()[0];
                String c = switch (KINDS[in.op()]) {
                    case EQ -> "(= " + a + " " + b + ")";
                    case NE -> "(distinct " + a + " " + b + ")";
                    case LT -> "(" + (s ? "bvslt " : "bvult ") + a + " " + b + ")";
                    case LE -> "(" + (s ? "bvsle " : "bvule ") + a + " " + b + ")";
                    case GT -> "(" + (s ? "bvsgt " : "bvugt ") + a + " " + b + ")";
                    default -> "(" + (s ? "bvsge " : "bvuge ") + a + " " + b + ")";
                };
                yield bool(c);
            }
            case MUX -> "(ite (= " + term(t, in.args()[0], 1) + " #b1) " + arg(t, in, 1, w) + " " + arg(t, in, 2, w) + ")";
            case CAT -> {
                var sb = new StringBuilder("(concat");
                for (int k = 0; k < in.args().length; k++) sb.append(' ').append(term(t, in.args()[k], in.argW()[k]));
                yield in.args().length == 1 ? term(t, in.args()[0], in.argW()[0]) : sb.append(')').toString();
            }
            case SLICE -> "((_ extract " + in.p0() + " " + in.p1() + ") " + term(t, in.args()[0], in.argW()[0]) + ")";
            case EXT -> arg(t, in, 0, w);
            case CAST -> term(t, in.args()[0], in.argW()[0]);
            case ANDR -> bool("(= " + term(t, in.args()[0], in.argW()[0]) + " " + bv(mask(in.argW()[0]), in.argW()[0]) + ")");
            case ORR -> bool("(distinct " + term(t, in.args()[0], in.argW()[0]) + " " + bv(BigInteger.ZERO, in.argW()[0]) + ")");
            case XORR -> {
                int aw = in.argW()[0];
                String a = term(t, in.args()[0], aw);
                var parts = new ArrayList<String>();
                for (int i = 0; i < aw; i++) parts.add("((_ extract " + i + " " + i + ") " + a + ")");
                String acc = parts.getFirst();
                for (int i = 1; i < parts.size(); i++) acc = "(bvxor " + acc + " " + parts.get(i) + ")";
                yield acc;
            }
        };
    }

    /** Tick names of step {@code t}, in domain order (empty with one domain). */
    List<String> tickNames(int t) {
        var out = new ArrayList<String>();
        if (multiClock()) for (int d = 0; d < domains; d++) out.add(tickName(t, d));
        return out;
    }

    List<String> inputNames(int t) {
        var out = new ArrayList<String>();
        for (int slot : net.inputSlots().values()) out.add(name(t, slot));
        return out;
    }
}
