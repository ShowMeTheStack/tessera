package org.eninehq.tessera.core;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.eninehq.tessera.ir.ClockDomainDef;
import org.eninehq.tessera.ir.Expr;
import org.eninehq.tessera.ir.Instance;
import org.eninehq.tessera.ir.MemDef;
import org.eninehq.tessera.ir.ModuleDef;
import org.eninehq.tessera.ir.Signal;

/**
 * Base class of all hardware modules. The constructor of a subclass describes the hardware.
 *
 * <p>Create the top module with {@link Tessera#elaborate} and children with
 * {@link #instance}. Ports, registers, memories and child instances stored in fields are
 * named after the field.
 */
public abstract class Module {
    private final ModuleBuilder builder;

    protected Module() {
        var s = Elab.session();
        if (s == null || !s.constructing)
            throw new TesseraException(SourceLoc.callerOfConstructor(getClass()),
                    "create modules with Tessera.elaborate(...) or instance(...), not with new");
        s.constructing = false;
        String name = getClass().getSimpleName();
        if (name.isEmpty()) name = "Module";
        builder = new ModuleBuilder(this, name);
        s.stack.push(builder);
    }

    ModuleBuilder builder() {
        return builder;
    }

    /** Internal: the IR definition of this module. */
    public final ModuleDef def() {
        return builder.def;
    }

    private ModuleBuilder here() {
        var cur = Elab.cur();
        if (cur != builder)
            throw TesseraException.here("module " + builder.def.name() + " can only be changed while it is being elaborated");
        return cur;
    }

    private Signal newSignal(Signal.Kind kind, String name, boolean auto, HwType<?> t) {
        return newSignal(kind, name, auto, t, null, null);
    }

    private Signal newSignal(Signal.Kind kind, String name, boolean auto, HwType<?> t, String bundle, String field) {
        var b = here();
        var loc = SourceLoc.here();
        var s = kind == Signal.Kind.REG ? b.newRegSignal(name, auto, t.width(), t.signed(), loc)
                : new Signal(kind, name, auto, t.width(), t.signed(), b.def, loc);
        s.setTypeInfo(typeInfo(t, bundle, field));
        if (s.isPort()) b.def.ports().add(s);
        else b.def.signals().add(s);
        b.declLocs.put(s, loc);
        return s;
    }

    /** Create one signal per leaf of {@code shape} and assemble them into a value. */
    private <T> T aggregate(Signal.Kind kind, String name, boolean auto, Shape<T> shape) {
        var leaves = shape.leaves();
        var vals = new ArrayList<Data>(leaves.size());
        for (var l : leaves) {
            Signal.Kind k = kind;
            if (l.flipped()) {
                if (kind == Signal.Kind.INPUT) k = Signal.Kind.OUTPUT;
                else if (kind == Signal.Kind.OUTPUT) k = Signal.Kind.INPUT;
            }
            var s = newSignal(k, name + l.path(), auto, l.type(), shape.toString(), l.path().isEmpty() ? null : l.path().substring(1));
            boolean plain = k == Signal.Kind.INPUT || k == Signal.Kind.OUTPUT || k == Signal.Kind.REG;
            vals.add(l.type().wrap(plain ? Elab.plainInit(s) : Elab.signalInit(s)));
        }
        return shape.assemble(vals);
    }

    // ---------------------------------------------------------------- ports

    protected final <T extends Data> T input(String name, HwType<T> type) {
        return type.wrap(Elab.plainInit(newSignal(Signal.Kind.INPUT, name, false, type)));
    }

    protected final <T extends Data> T input(HwType<T> type) {
        return type.wrap(Elab.plainInit(newSignal(Signal.Kind.INPUT, builder.autoName("in"), true, type)));
    }

    /** Input ports for every leaf of a bundle or Vec; flipped leaves become outputs. */
    protected final <T> T input(String name, Shape<T> shape) {
        return aggregate(Signal.Kind.INPUT, name, false, shape);
    }

    protected final <T> T input(Shape<T> shape) {
        return aggregate(Signal.Kind.INPUT, builder.autoName("in"), true, shape);
    }

    protected final <T extends Data> T output(String name, HwType<T> type) {
        return type.wrap(Elab.plainInit(newSignal(Signal.Kind.OUTPUT, name, false, type)));
    }

    protected final <T extends Data> T output(HwType<T> type) {
        return type.wrap(Elab.plainInit(newSignal(Signal.Kind.OUTPUT, builder.autoName("out"), true, type)));
    }

    /** Output ports for every leaf of a bundle or Vec; flipped leaves become inputs. */
    protected final <T> T output(String name, Shape<T> shape) {
        return aggregate(Signal.Kind.OUTPUT, name, false, shape);
    }

    protected final <T> T output(Shape<T> shape) {
        return aggregate(Signal.Kind.OUTPUT, builder.autoName("out"), true, shape);
    }

    // ---------------------------------------------------------------- wires

    protected final <T extends Data> T wire(String name, HwType<T> type) {
        return type.wrap(Elab.signalInit(newSignal(Signal.Kind.WIRE, name, false, type)));
    }

    protected final <T extends Data> T wire(HwType<T> type) {
        return type.wrap(Elab.signalInit(newSignal(Signal.Kind.WIRE, builder.autoName("_w"), true, type)));
    }

    protected final <T> T wire(String name, Shape<T> shape) {
        return aggregate(Signal.Kind.WIRE, name, false, shape);
    }

    protected final <T> T wire(Shape<T> shape) {
        return aggregate(Signal.Kind.WIRE, builder.autoName("_w"), true, shape);
    }

    /** A wire with a default value, overridable by later assignments. */
    protected final <T extends Data> T wireDefault(HwType<T> type, T dflt) {
        T w = wire(type);
        Elab.assign(w, dflt);
        return w;
    }

    protected final <T extends Data> T wireDefault(HwType<T> type, long dflt) {
        T w = wire(type);
        Elab.assign(w, constant(type, dflt));
        return w;
    }

    // ---------------------------------------------------------------- registers

    /** A register without reset. Holds its value unless assigned. */
    protected final <T extends Data> T reg(String name, HwType<T> type) {
        return type.wrap(Elab.plainInit(newSignal(Signal.Kind.REG, name, false, type)));
    }

    protected final <T extends Data> T reg(HwType<T> type) {
        return type.wrap(Elab.plainInit(newSignal(Signal.Kind.REG, builder.autoName("_r"), true, type)));
    }

    /** Same as {@link #reg(String, HwType)}: states explicitly that the register has no reset. */
    protected final <T extends Data> T regNoReset(String name, HwType<T> type) {
        return reg(name, type);
    }

    /** Registers for every leaf of a bundle or Vec (no reset). */
    protected final <T> T reg(String name, Shape<T> shape) {
        return aggregate(Signal.Kind.REG, name, false, shape);
    }

    protected final <T> T reg(Shape<T> shape) {
        return aggregate(Signal.Kind.REG, builder.autoName("_r"), true, shape);
    }

    /** A register with a reset to {@code init} (synchronous unless the domain says otherwise). */
    protected final <T extends Data> T reg(String name, HwType<T> type, long init) {
        T r = reg(name, type);
        r.signal().setResetValue(ModuleBuilder.checkFits(init, type.width(), type.signed()));
        return r;
    }

    protected final <T extends Data> T reg(HwType<T> type, long init) {
        T r = reg(type);
        r.signal().setResetValue(ModuleBuilder.checkFits(init, type.width(), type.signed()));
        return r;
    }

    /** Registers shaped like {@code init}, reset to its (constant) leaves. */
    protected final <T> T regInit(String name, T init) {
        var leaves = Agg.leaves(init);
        var regs = new ArrayList<Data>(leaves.size());
        for (int i = 0; i < leaves.size(); i++) {
            var l = leaves.get(i);
            if (!(l.expr() instanceof Expr.Lit lit))
                throw TesseraException.here("regInit: reset values must be constants");
            Data r = reg(name + (leaves.size() == 1 ? "" : "_" + i), typeOf(l));
            r.signal().setResetValue(lit.value());
            regs.add(r);
        }
        return Agg.rebuild(init, regs);
    }

    /** A register that takes {@code next} every cycle. */
    @SuppressWarnings("unchecked")
    protected final <T extends Data> T regNext(T next) {
        Data r = reg(typeOf(next));
        Elab.assign(r, next);
        return (T) r;
    }

    @SuppressWarnings("unchecked")
    protected final <T extends Data> T regNext(T next, long init) {
        Data r = reg(typeOf(next), init);
        Elab.assign(r, next);
        return (T) r;
    }

    /** Registers that take every leaf of an aggregate {@code next} each cycle. */
    protected final <T> T regNextAll(T next) {
        var leaves = Agg.leaves(next);
        var regs = new ArrayList<Data>(leaves.size());
        for (var l : leaves) regs.add(regNext(l));
        return Agg.rebuild(next, regs);
    }

    /** The implicit reset of this module (active high unless {@link #useReset} changed it). */
    protected final Bool reset() {
        var b = here();
        b.ensureClock();
        return new Bool(Elab.plainInit(b.def.reset()));
    }

    // ---------------------------------------------------------------- clock domains

    /**
     * Choose the reset behaviour of the implicit clock domain. Must be called before any
     * register is created.
     */
    protected final void useReset(ClockDomainDef.ResetKind kind) {
        var b = here();
        if (b.def.clock() != null)
            throw TesseraException.here("useReset(...) must be called before the first register or memory port");
        b.defaultResetKind = kind;
    }

    /**
     * Name the implicit clock and reset ports (for example {@code aclk}/{@code aresetn}, so
     * vendor tools recognise an AXI interface). Must be called before the first register.
     */
    protected final void useClockNames(String clock, String reset) {
        var b = here();
        if (b.def.clock() != null)
            throw TesseraException.here("useClockNames(...) must be called before the first register or memory port");
        b.clockName = clock;
        b.resetName = reset;
    }

    /** The implicit clock domain ({@code clock} and {@code reset} ports). */
    protected final ClockDomain defaultDomain() {
        var b = here();
        b.ensureClock();
        return new ClockDomain(b.def.defaultDomain());
    }

    /** Declare another clock domain with input ports {@code <name>_clock} and {@code <name>_reset}. */
    protected final ClockDomain clockDomain(String name, ClockDomainDef.ResetKind kind) {
        var b = here();
        var loc = SourceLoc.here();
        var clock = new Signal(Signal.Kind.INPUT, name + "_clock", false, 1, false, b.def, loc);
        var reset = new Signal(Signal.Kind.INPUT, name + "_reset", false, 1, false, b.def, loc);
        clock.markClockOrReset();
        reset.markClockOrReset();
        b.def.ports().add(clock);
        b.def.ports().add(reset);
        var d = new ClockDomainDef(name, clock, reset, kind, b.def);
        b.def.domains().add(d);
        return new ClockDomain(d);
    }

    protected final ClockDomain clockDomain(String name) {
        return clockDomain(name, ClockDomainDef.ResetKind.SYNC_HIGH);
    }

    /**
     * A domain on the current clock whose (synchronous, active-high) reset is {@code reset}, a
     * value computed by this module: for example a core held in reset until software starts it.
     */
    protected final ClockDomain derivedDomain(String name, Bool reset) {
        var b = here();
        var base = b.currentDomain();
        var r = new Signal(Signal.Kind.WIRE, name + "_reset", false, 1, false, b.def, SourceLoc.here());
        b.def.signals().add(r);
        b.declLocs.put(r, r.loc());
        Elab.assign(new Bool(Elab.signalInit(r)), reset);
        var d = new ClockDomainDef(name, base.clock(), r, ClockDomainDef.ResetKind.SYNC_HIGH, b.def);
        b.def.domains().add(d);
        return new ClockDomain(d);
    }

    /** Run {@code body} with {@code domain} as the domain for new registers, memory ports and child instances. */
    protected final void withClock(ClockDomain domain, Runnable body) {
        var b = here();
        if (domain.def.owner() != b.def)
            throw TesseraException.here("clock domain " + domain + " belongs to another module");
        b.domainStack.push(domain.def);
        try {
            body.run();
        } finally {
            b.domainStack.pop();
        }
    }

    protected final <T> T withClock(ClockDomain domain, Supplier<T> body) {
        var r = new ArrayList<T>(1);
        withClock(domain, () -> { r.add(body.get()); });
        return r.getFirst();
    }

    /** Drive a child instance's named clock domain from one of this module's domains. */
    protected final void connectClock(ClockDomain childDomain, ClockDomain from) {
        var b = here();
        var cd = childDomain.def;
        var inst = cd.owner().instantiatedAs();
        if (inst == null || inst.parent() != b.def)
            throw TesseraException.here("connectClock: " + childDomain + " is not a domain of a direct child instance");
        if (from.def.owner() != b.def) throw TesseraException.here("connectClock: " + from + " belongs to another module");
        checkPolarity(cd.resetKind(), from.def.resetKind(), cd.toString());
        inst.inputs().put(cd.clock(), new Expr.Ref(from.def.clock()));
        inst.inputs().put(cd.reset(), new Expr.Ref(from.def.reset()));
    }

    private static void checkPolarity(ClockDomainDef.ResetKind child, ClockDomainDef.ResetKind parent, String what) {
        if (child.activeHigh() != parent.activeHigh())
            throw TesseraException.here(what + " expects an active-" + (child.activeHigh() ? "high" : "low")
                    + " reset but is driven by an active-" + (parent.activeHigh() ? "high" : "low") + " one");
    }

    // ---------------------------------------------------------------- memories and instances

    protected final <T extends Data> Mem<T> mem(String name, HwType<T> type, int depth) {
        var b = here();
        if (depth < 1) throw TesseraException.here("memory depth must be >= 1");
        var md = new MemDef(name, type.width(), type.signed(), depth, b.def, SourceLoc.here());
        b.def.mems().add(md);
        b.effects++;
        return new Mem<>(md, type);
    }

    protected final <T extends Data> Mem<T> mem(HwType<T> type, int depth) {
        return mem(builder.autoName("_mem"), type, depth);
    }

    /** Instantiate a child module. */
    protected final <M extends Module> M instance(String name, Supplier<M> ctor) {
        return instantiate(name, false, ctor);
    }

    protected final <M extends Module> M instance(Supplier<M> ctor) {
        return instantiate(null, true, ctor);
    }

    <M extends Module> M instantiate(String name, boolean auto, Supplier<M> ctor) {
        var parent = here();
        var loc = SourceLoc.here();
        var s = Elab.session();
        s.constructing = true;
        M child;
        try {
            child = ctor.get();
        } catch (RuntimeException ex) {
            while (s.stack.peek() != parent) s.stack.pop();
            throw ex;
        } finally {
            s.constructing = false;
        }
        if (child == null || s.stack.peek() != child.builder())
            throw new TesseraException(loc, "instance(...) must be given a supplier that creates a new module");
        s.stack.pop();
        child.builder().finish();
        var inst = new Instance(name != null ? name : "u_" + child.builder().def.name(), child.builder().def, parent.def, loc);
        child.builder().def.setInstantiatedAs(inst);
        child.builder().autoInstanceName = auto;
        parent.def.instances().add(inst);
        parent.effects++;
        var cd = child.builder().def;
        if (cd.clock() != null) {
            var pd = parent.currentDomain();
            checkPolarity(cd.defaultDomain().resetKind(), pd.resetKind(), "instance '" + inst.name() + "'");
            inst.inputs().put(cd.clock(), new Expr.Ref(pd.clock()));
            inst.inputs().put(cd.reset(), new Expr.Ref(pd.reset()));
        }
        return child;
    }

    // ---------------------------------------------------------------- helpers

    static Signal.TypeInfo typeInfo(HwType<?> t, String bundle, String field) {
        List<String> literals = null;
        if (t instanceof HwEnum.Type<?> et) {
            literals = new ArrayList<>();
            for (var c : et.enumClass().getEnumConstants()) literals.add(c.name());
        } else if (t instanceof HwUnion.Type<?> ut) {
            literals = new ArrayList<>();
            for (var v : ut.variants()) literals.add(v.cls().getSimpleName());
        }
        return new Signal.TypeInfo(t.toString(), literals, bundle, field);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    static <T extends Data> HwType<T> typeOf(T d) {
        return switch (d) {
            case Bool b -> (HwType<T>) Bool.TYPE;
            case UInt u -> (HwType<T>) UInt.w(u.width());
            case SInt si -> (HwType<T>) SInt.w(si.width());
            case Bits bi -> (HwType<T>) Bits.w(bi.width());
            case HwEnum e -> (HwType<T>) HwEnum.of(e.enumClass());
            case HwUnion u -> (HwType<T>) u.type();
            default -> throw TesseraException.here("unsupported data type " + d.getClass().getSimpleName());
        };
    }

    @SuppressWarnings("unchecked")
    static <T extends Data> T constant(HwType<T> type, long v) {
        var bits = ModuleBuilder.checkFits(v, type.width(), type.signed());
        return type.wrap(Elab.init(new Expr.Lit(bits, type.width(), type.signed())));
    }

    static List<Data> leavesOf(Object v) {
        return Agg.leaves(v);
    }
}
