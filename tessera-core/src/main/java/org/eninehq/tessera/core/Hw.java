package org.eninehq.tessera.core;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.IntFunction;
import org.eninehq.tessera.ir.Expr;
import org.eninehq.tessera.ir.OpKind;
import org.eninehq.tessera.ir.Ops;
import org.eninehq.tessera.ir.Signal;

/** Static helpers for describing hardware: conditionals, multiplexers, literals. */
public final class Hw {
    private Hw() {}

    public static final Bool TRUE = Bool.of(true);
    public static final Bool FALSE = Bool.of(false);

    public static UInt lit(long v) { return UInt.lit(v); }
    public static UInt lit(long v, int width) { return UInt.lit(v, width); }
    public static SInt slit(long v, int width) { return SInt.lit(v, width); }

    /** Run {@code body} so that its assignments only take effect when {@code cond} is true. */
    public static WhenChain when(Bool cond, Runnable body) {
        return WhenChain.start(cond, body);
    }

    /**
     * {@code cond ? a : b}. Operands are extended to the wider width. Works on single values
     * and on aggregates (records of hardware values and {@link Vec}s), signal by signal.
     */
    @SuppressWarnings("unchecked")
    public static <T> T mux(Bool cond, T a, T b) {
        if (a instanceof Data da && b instanceof Data db) return (T) muxData(cond, da, db);
        Agg.checkSameShape(a, b, "mux");
        var la = Agg.leaves(a);
        var lb = Agg.leaves(b);
        var out = new ArrayList<Data>(la.size());
        for (int i = 0; i < la.size(); i++) out.add(muxData(cond, la.get(i), lb.get(i)));
        return Agg.rebuild(a, out);
    }

    private static Data muxData(Bool cond, Data a, Data b) {
        if (a.signed() != b.signed()) throw TesseraException.here("mux: cannot mix signed and unsigned operands");
        if (a instanceof HwEnum<?> ea && b instanceof HwEnum<?> eb && ea.enumClass() != eb.enumClass())
            throw TesseraException.here("mux: enum type mismatch");
        if (a instanceof HwUnion<?> ua && b instanceof HwUnion<?> ub && ua.type() != ub.type())
            throw TesseraException.here("mux: union type mismatch");
        Expr c = Elab.read(cond), ea = Elab.read(a), eb = Elab.read(b);
        int w = Math.max(ea.width(), eb.width());
        Expr m = Ops.op(OpKind.MUX, w, a.signed(), c, Ops.ext(ea, w), Ops.ext(eb, w));
        Data wider = a.width() >= b.width() ? a : b;
        return wider.derive(m);
    }

    public static UInt mux(Bool cond, UInt a, long b) { return mux(cond, a, UInt.lit(b)); }
    public static UInt mux(Bool cond, long a, UInt b) { return mux(cond, UInt.lit(a), b); }

    /** Concatenate, first argument most significant. */
    public static UInt cat(UInt first, UInt... rest) {
        return first.cat(rest);
    }

    /**
     * A multiplexer over every constant of an enum. Java checks at compile time that a
     * {@code switch} expression in {@code f} covers every constant:
     * <pre>{@code
     * UInt y = Hw.select(op, o -> switch (o) {
     *     case ADD -> a.addWrap(b);
     *     case SUB -> a.subWrap(b);
     * });
     * }</pre>
     */
    public static <E extends Enum<E>, T> T select(HwEnum<E> sel, Function<E, T> f) {
        E[] values = sel.enumClass().getEnumConstants();
        var results = new ArrayList<T>(values.length);
        for (E e : values) results.add(f.apply(e));
        T acc = results.getLast();
        for (int i = values.length - 2; i >= 0; i--) acc = mux(sel.is(values[i]), results.get(i), acc);
        return acc;
    }

    /** A multiplexer over every value of a small unsigned selector (at most 10 bits). */
    public static <T> T select(UInt sel, IntFunction<T> f) {
        if (sel.width() > 10) throw TesseraException.here("select over " + sel.width() + " bits is too large");
        int n = 1 << sel.width();
        var results = new ArrayList<T>(n);
        for (int i = 0; i < n; i++) results.add(f.apply(i));
        return index(sel, results);
    }

    /** Dynamic indexing into a list of values (a multiplexer tree); out-of-range indexes give the last item. */
    public static <T> T index(UInt idx, List<T> items) {
        if (items.isEmpty()) throw TesseraException.here("index into an empty list");
        return indexRec(idx, items, 0, items.size(), idx.width() - 1);
    }

    private static <T> T indexRec(UInt idx, List<T> items, int lo, int hi, int bit) {
        if (hi - lo == 1 || bit < 0) return items.get(lo);
        int mid = lo + (1 << bit);
        if (mid >= hi) return indexRec(idx, items, lo, hi, bit - 1);
        return mux(idx.bit(bit), indexRec(idx, items, mid, hi, bit - 1), indexRec(idx, items, lo, mid, bit - 1));
    }

    /** Run {@code body} once for each enum constant, under {@code when(sel.is(e))}. */
    public static <E extends Enum<E>> void cases(HwEnum<E> sel, java.util.function.Consumer<E> body) {
        WhenChain chain = null;
        for (E e : sel.enumClass().getEnumConstants()) {
            Runnable r = () -> body.accept(e);
            chain = chain == null ? when(sel.is(e), r) : chain.elseWhen(sel.is(e), r);
        }
    }

    // ---------------------------------------------------------------- aggregates

    /** {@code target := value}, signal by signal, for single values and aggregates. */
    public static <T> void assign(T target, T value) {
        if (target instanceof Data dt && value instanceof Data dv) {
            assignData(dt, dv);
            return;
        }
        Agg.checkSameShape(target, value, "assign");
        var lt = Agg.leaves(target);
        var lv = Agg.leaves(value);
        for (int i = 0; i < lt.size(); i++) assignData(lt.get(i), lv.get(i));
    }

    private static void assignData(Data t, Data v) {
        if (t instanceof HwEnum<?> et && v instanceof HwEnum<?> ev && et.enumClass() != ev.enumClass())
            throw TesseraException.here("enum type mismatch: " + et.enumClass().getSimpleName() + " vs " + ev.enumClass().getSimpleName());
        Elab.assign(t, v);
    }

    /**
     * Connect two aggregates in both directions (Chisel's {@code <>}): for each pair of
     * signals, the one this module may drive is driven by the other. Use it to plug a
     * bundle with flipped fields (like a ready/valid stream) into a child or a port.
     */
    public static <T> void connect(T a, T b) {
        Agg.checkSameShape(a, b, "connect");
        var la = Agg.leaves(a);
        var lb = Agg.leaves(b);
        var mod = Elab.cur().def;
        for (int i = 0; i < la.size(); i++) {
            Data x = la.get(i), y = lb.get(i);
            boolean dx = drivable(x, mod), dy = drivable(y, mod);
            if (dx && !dy) assignData(x, y);
            else if (dy && !dx) assignData(y, x);
            else if (dx) assignData(x, y);
            else throw TesseraException.here("connect: neither " + x.describe() + " nor " + y.describe()
                    + " can be driven from module " + mod.name());
        }
    }

    private static boolean drivable(Data d, org.eninehq.tessera.ir.ModuleDef mod) {
        var s = d.signal();
        if (s == null) return false;
        if (s.owner() == mod) return s.kind() == Signal.Kind.OUTPUT || s.kind() == Signal.Kind.WIRE || s.kind() == Signal.Kind.REG;
        var inst = s.owner().instantiatedAs();
        return inst != null && inst.parent() == mod && s.kind() == Signal.Kind.INPUT;
    }

    /** All signals of an aggregate, concatenated (first component most significant). */
    public static UInt pack(Object value) {
        var leaves = Agg.leaves(value);
        var parts = new ArrayList<Expr>(leaves.size());
        int w = 0;
        for (var l : leaves) {
            Expr e = Elab.read(l);
            if (e.signed()) e = Ops.op(OpKind.CAST, e.width(), false, e);
            parts.add(e);
            w += e.width();
        }
        if (parts.size() == 1) return UInt.mk(parts.getFirst());
        return UInt.mk(new Expr.Op(OpKind.CAT, parts, w, false));
    }

    // ---------------------------------------------------------------- state from library code

    /**
     * A register in the current module and clock domain, for library functions that are not
     * module constructors. {@code init} is the reset value, or null for no reset.
     */
    public static <T extends Data> T register(String name, HwType<T> type, Long init) {
        var b = Elab.cur();
        var s = b.newRegSignal(b.autoName("_" + name), true, type.width(), type.signed(), SourceLoc.here());
        if (init != null) s.setResetValue(ModuleBuilder.checkFits(init, type.width(), type.signed()));
        s.setTypeInfo(Module.typeInfo(type, null, null));
        b.def.signals().add(s);
        b.declLocs.put(s, s.loc());
        return type.wrap(Elab.plainInit(s));
    }

    // ---------------------------------------------------------------- clock domains

    /**
     * Bring a single bit from another clock domain into the current one through a chain of
     * {@code stages} registers (two is the usual minimum). This is the only way, besides an
     * asynchronous FIFO, to cross clock domains without a CDC error.
     */
    public static Bool synchronize(Bool in, int stages) {
        return (Bool) syncChain(in, stages, "sync");
    }

    /**
     * Like {@link #synchronize} for a multi-bit value that changes at most one bit at a time
     * (a Gray-coded counter). The caller is responsible for the Gray coding.
     */
    public static UInt synchronizeGray(UInt in, int stages) {
        return (UInt) syncChain(in, stages, "gsync");
    }

    private static Data syncChain(Data in, int stages, String prefix) {
        if (stages < 2) throw TesseraException.here("a synchronizer needs at least 2 stages",
                new TesseraException.Fix("use two stages", "(synchronize(?:Gray)?\\(.*,\\s*)" + stages + "\\)", "$12)"));
        var b = Elab.cur();
        Expr prev = Elab.read(in);
        Signal first = null, last = null;
        for (int k = 0; k < stages; k++) {
            var s = b.newRegSignal(b.autoName("_" + prefix) + "_" + k, true, in.width(), false, SourceLoc.here());
            b.def.signals().add(s);
            if (first == null) {
                first = s;
                s.markCdcSync();
            }
            b.addAssign(s, prev, SourceLoc.here());
            prev = new Expr.Ref(s);
            last = s;
        }
        return in instanceof Bool ? new Bool(Elab.plainInit(last)) : new UInt(Elab.plainInit(last));
    }

    // ---------------------------------------------------------------- purity and formal properties

    /**
     * Evaluate a combinational function and check that it creates no state, assignments,
     * memories or instances (the runtime half of {@link Fn}).
     */
    public static <T> T fn(java.util.function.Supplier<T> body) {
        var b = Elab.cur();
        int before = b.effects;
        T r = body.get();
        if (b.effects != before)
            throw TesseraException.here("combinational function created registers, memories, instances or assignments");
        return r;
    }

    private static void property(org.eninehq.tessera.ir.Assertion.Kind kind, Bool cond, String name) {
        var b = Elab.cur();
        var dom = b.currentDomain();
        Expr en = b.fullCondition();
        Expr rst = new Expr.Ref(dom.reset());
        Expr notReset = dom.resetKind().activeHigh() ? Ops.not(rst) : rst;
        b.def.assertions().add(new org.eninehq.tessera.ir.Assertion(kind, name, Elab.read(cond), Ops.and(en, notReset),
                dom, SourceLoc.here()));
    }

    /** Check {@code cond} on every clock edge (under the enclosing when conditions, outside reset). */
    public static void assertThat(Bool cond, String name) {
        property(org.eninehq.tessera.ir.Assertion.Kind.ASSERT, cond, name);
    }

    /** Formal verification may assume {@code cond}; simulation checks it like an assertion. */
    public static void assume(Bool cond, String name) {
        property(org.eninehq.tessera.ir.Assertion.Kind.ASSUME, cond, name);
    }

    /** Formal verification tries to reach a state where {@code cond} holds. */
    public static void cover(Bool cond, String name) {
        property(org.eninehq.tessera.ir.Assertion.Kind.COVER, cond, name);
    }

    /** The value of {@code d} {@code cycles} clock edges ago (registers without reset). */
    @SuppressWarnings("unchecked")
    public static <T extends Data> T past(T d, int cycles) {
        var b = Elab.cur();
        Expr prev = Elab.read(d);
        Signal last = null;
        for (int k = 0; k < cycles; k++) {
            var s = b.newRegSignal(b.autoName("_past"), true, d.width(), d.signed(), SourceLoc.here());
            b.def.signals().add(s);
            b.addAssign(s, prev, SourceLoc.here());
            prev = new Expr.Ref(s);
            last = s;
        }
        if (last == null) return d;
        return (T) d.rewrap(Elab.plainInit(last));
    }
}
