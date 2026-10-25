package org.eninehq.tessera.sim;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.eninehq.tessera.ir.Circuit;
import org.eninehq.tessera.ir.Expr;
import org.eninehq.tessera.ir.MemDef;
import org.eninehq.tessera.ir.ModuleDef;
import org.eninehq.tessera.ir.OpKind;
import org.eninehq.tessera.ir.Signal;

/**
 * A flattened, topologically ordered form of a circuit, shared by all simulator backends.
 * Every value lives in a slot of {@code long} words holding its bit pattern: one word up to
 * 64 bits, {@code ceil(width / 64)} consecutive words (little-endian) for wider values.
 * Slot numbers are word offsets.
 */
public final class Netlist {

    /** Instruction opcodes: {@link OpKind} ordinals, plus MEMREAD. */
    public static final int MEMREAD = OpKind.values().length;

    /**
     * One combinational instruction. {@code args} are slots; {@code argW}/{@code argS} their widths and
     * signedness. {@code wide} is set when the result or an operand is wider than 64 bits.
     */
    public record Insn(int op, int dst, int width, int[] args, int[] argW, boolean[] argS, int p0, int p1, boolean wide) {}

    /**
     * A register. {@code domain} indexes {@link #clocks()}; the reset is active when
     * {@code v[resetSlot]} equals {@code resetActive}.
     */
    public record Reg(int slot, int next, int width, int resetSlot, long resetValue, boolean hasReset, String path,
                      int domain, boolean asyncReset, long resetActive, long[] resetWords) {
        public int words() { return BitVec.words(width); }
    }

    public record MemWrite(int mem, int addr, int data, int en, int domain) {}

    /** A formal property checked by the simulator on the clock edges of {@code domain}. */
    public record Check(org.eninehq.tessera.ir.Assertion.Kind kind, String name, int cond, int en, int domain, String loc) {}

    /** A memory; wide elements take {@code words()} consecutive entries each. */
    public record Mem(String path, int width, int depth, long[] init) {
        public int words() { return BitVec.words(width); }
    }

    final List<Insn> insns = new ArrayList<>();
    final List<Reg> regs = new ArrayList<>();
    final List<MemWrite> memWrites = new ArrayList<>();
    final List<Check> checks = new ArrayList<>();
    final List<Signal> clocks = new ArrayList<>();
    final List<String> clockPaths = new ArrayList<>();
    final List<Mem> mems = new ArrayList<>();
    final List<Integer> slotWidths = new ArrayList<>();
    final List<Long> constants = new ArrayList<>();
    final java.util.Set<Integer> constantSlots = new java.util.LinkedHashSet<>();
    private final List<Integer> constantOrder = new ArrayList<>();
    final Map<Signal, Integer> signalSlot = new IdentityHashMap<>();
    final Map<MemDef, Integer> memIndex = new IdentityHashMap<>();
    final Map<String, Signal> byPath = new LinkedHashMap<>();
    final Map<Signal, String> pathOf = new IdentityHashMap<>();
    final Map<String, Integer> memByPath = new LinkedHashMap<>();
    final Circuit circuit;

    private final Map<Expr, Integer> exprSlot = new IdentityHashMap<>();

    private Netlist(Circuit circuit) {
        this.circuit = circuit;
    }

    public static Netlist build(Circuit c) {
        var n = new Netlist(c);
        var err = new RuntimeException[1];
        // deep designs recurse deeply; use a thread with a large stack
        var t = new Thread(null, () -> {
            try {
                n.run();
            } catch (RuntimeException e) {
                err[0] = e;
            }
        }, "tessera-netlist", 512L << 20);
        t.start();
        try {
            t.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        if (err[0] != null) throw err[0];
        return n;
    }

    public List<Insn> insns() { return insns; }
    public List<Reg> regs() { return regs; }
    public List<MemWrite> memWrites() { return memWrites; }
    public List<Check> checks() { return checks; }

    /** Hierarchical paths of the top-level clock inputs, in domain-index order. */
    public List<String> clockPaths() { return clockPaths; }
    public List<Mem> mems() { return mems; }
    /** Total number of words. */
    public int slotCount() { return slotWidths.size(); }

    /** Width of the value starting at word {@code slot} (0 for continuation words). */
    public int slotWidth(int slot) { return slotWidths.get(slot); }
    public Circuit circuit() { return circuit; }

    /** Slot of a signal, or -1 if it was optimised away. */
    public int slotOf(Signal s) {
        return signalSlot.getOrDefault(s, -1);
    }

    public int memIndexOf(MemDef m) {
        Integer i = memIndex.get(m);
        if (i == null) throw new IllegalArgumentException("memory " + m.name() + " is not part of this design");
        return i;
    }

    /** All named signals by hierarchical path ("u_core.pc"), in a stable order. */
    public Map<String, Signal> signalsByPath() { return byPath; }

    public String pathOf(Signal s) { return pathOf.get(s); }

    public Map<String, Integer> memsByPath() { return memByPath; }

    /** True when word {@code slot} holds a constant (its value is in {@link #initialValues()}). */
    public boolean isConstant(int slot) {
        return constantSlots.contains(slot);
    }

    /** True for a top-level clock input. */
    public boolean isClock(Signal s) {
        for (var c : clocks) if (c == s) return true;
        return s.isClockOrReset() && s.name().equals("clock");
    }

    /** Slots of the top-level inputs, by path (clock excluded). */
    public java.util.Map<String, Integer> inputSlots() {
        var out = new LinkedHashMap<String, Integer>();
        for (var s : circuit.top().ports())
            if (s.kind() == Signal.Kind.INPUT && !isClock(s) && slotOf(s) >= 0)
                out.put(pathOf(s), slotOf(s));
        return out;
    }

    /** Initial slot values (constants filled in). */
    public long[] initialValues() {
        var v = new long[slotCount()];
        for (int i = 0; i < constantOrder.size(); i++) v[constantOrder.get(i)] = constants.get(i);
        return v;
    }

    // ------------------------------------------------------------ building

    private void run() {
        collectPaths(circuit.top(), "");
        for (var e : byPath.values()) visitSignal(e);
        for (var m : circuit.modulesBottomUp()) {
            for (var s : m.signals()) {
                if (s.kind() != Signal.Kind.REG) continue;
                int slot = visitSignal(s);
                int next = s.driver() == null ? slot : visitExpr(s.driver());
                var d = s.domain();
                int rs = s.resetValue() != null ? visitSignal(d.reset()) : -1;
                long[] rw = new long[BitVec.words(s.width())];
                if (s.resetValue() != null) BitVec.fromBig(s.resetValue(), rw, 0, s.width());
                regs.add(new Reg(slot, next, s.width(), rs, rw[0],
                        s.resetValue() != null, pathOf.get(s), domainIndex(d), d.resetKind().async(),
                        d.resetKind().activeHigh() ? 1 : 0, rw));
            }
            for (var mem : m.mems()) {
                for (var w : mem.writes())
                    memWrites.add(new MemWrite(memIndex.get(mem), visitExpr(w.addr()), visitExpr(w.data()), visitExpr(w.en()),
                            domainIndex(w.domain())));
            }
            for (var a : m.assertions())
                checks.add(new Check(a.kind(), a.name(), visitExpr(a.cond()), visitExpr(a.enable()), domainIndex(a.domain()),
                        a.loc().toString()));
        }
    }

    private int domainIndex(org.eninehq.tessera.ir.ClockDomainDef d) {
        Signal root = org.eninehq.tessera.ir.ClockRoots.rootOf(d);
        for (int i = 0; i < clocks.size(); i++) if (clocks.get(i) == root) return i;
        clocks.add(root);
        clockPaths.add(pathOf.get(root));
        return clocks.size() - 1;
    }

    private void collectPaths(ModuleDef m, String prefix) {
        for (var s : m.allSignals()) {
            if (m.instantiatedAs() != null && s.isClockOrReset()) continue;
            String p = prefix + s.name();
            byPath.put(p, s);
            pathOf.put(s, p);
        }
        for (var mem : m.mems()) {
            int idx = mems.size();
            mems.add(new Mem(prefix + mem.name(), mem.width(), mem.depth(), mem.init()));
            memIndex.put(mem, idx);
            memByPath.put(prefix + mem.name(), idx);
        }
        for (var i : m.instances()) collectPaths(i.def(), prefix + i.name() + ".");
    }

    private int newSlot(int width) {
        int base = slotWidths.size();
        slotWidths.add(width);
        for (int k = 1; k < BitVec.words(width); k++) slotWidths.add(0);
        return base;
    }

    private int visitSignal(Signal s) {
        Integer have = signalSlot.get(s);
        if (have != null) return have;
        int slot;
        switch (s.kind()) {
            case REG -> slot = newSlot(s.width());
            case INPUT -> {
                var inst = s.owner().instantiatedAs();
                if (inst == null) slot = newSlot(s.width());
                else {
                    Expr d = inst.inputs().get(s);
                    slot = d == null ? newSlot(s.width()) : visitExpr(d);
                }
            }
            default -> slot = s.driver() == null ? newSlot(s.width()) : visitExpr(s.driver());
        }
        signalSlot.put(s, slot);
        return slot;
    }

    private int visitExpr(Expr e) {
        Integer have = exprSlot.get(e);
        if (have != null) return have;
        int slot = switch (e) {
            case Expr.Lit l -> {
                int sl = newSlot(l.width());
                long[] words = new long[BitVec.words(l.width())];
                BitVec.fromBig(l.value(), words, 0, l.width());
                for (int k = 0; k < words.length; k++) {
                    constantSlots.add(sl + k);
                    constantOrder.add(sl + k);
                    constants.add(words[k]);
                }
                yield sl;
            }
            case Expr.Ref r -> visitSignal(r.signal());
            case Expr.InstRef ir -> visitSignal(ir.port());
            case Expr.MemRead mr -> {
                int a = visitExpr(mr.addr());
                int dst = newSlot(mr.width());
                insns.add(new Insn(MEMREAD, dst, mr.width(), new int[]{a}, new int[]{mr.addr().width()},
                        new boolean[]{false}, memIndex.get(mr.mem()), 0, mr.width() > 64 || mr.addr().width() > 64));
                yield dst;
            }
            case Expr.Op op -> {
                int n = op.args().size();
                int[] args = new int[n], aw = new int[n];
                boolean[] as = new boolean[n];
                for (int k = 0; k < n; k++) {
                    args[k] = visitExpr(op.arg(k));
                    aw[k] = op.arg(k).width();
                    as[k] = op.arg(k).signed();
                }
                int dst = newSlot(op.width());
                boolean wide = op.width() > 64;
                for (int w : aw) wide |= w > 64;
                insns.add(new Insn(op.kind().ordinal(), dst, op.width(), args, aw, as, op.p0(), op.p1(), wide));
                yield dst;
            }
        };
        exprSlot.put(e, slot);
        return slot;
    }
}
