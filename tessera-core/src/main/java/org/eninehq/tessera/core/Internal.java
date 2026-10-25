package org.eninehq.tessera.core;

import java.math.BigInteger;
import java.util.List;
import org.eninehq.tessera.ir.Expr;
import org.eninehq.tessera.ir.ModuleDef;
import org.eninehq.tessera.ir.Signal;

/**
 * Internal interface for libraries that extend the elaborator (the pipeline library).
 * Not intended for designs.
 */
public final class Internal {
    private Internal() {}

    public static ModuleDef currentModule() {
        return Elab.cur().def;
    }

    /** Install a stage hook on the current module, returning the previous one. */
    public static StageHook setHook(StageHook hook) {
        var b = Elab.cur();
        var prev = b.hook;
        b.hook = hook;
        return prev;
    }

    public static StageHook hook() {
        return Elab.cur().hook;
    }

    /** Resolve {@code d} in the current module without stage adaptation. */
    public static Expr resolveRaw(Data d) {
        return Elab.resolve(Elab.cur(), d);
    }

    public static Signal newReg(String name, int width, boolean signed, BigInteger reset) {
        var b = Elab.cur();
        var s = b.newRegSignal(name, false, width, signed, SourceLoc.here());
        if (reset != null) s.setResetValue(reset);
        b.def.signals().add(s);
        return s;
    }

    public static Signal newWire(String name, int width, boolean signed) {
        var b = Elab.cur();
        var s = new Signal(Signal.Kind.WIRE, name, false, width, signed, b.def, SourceLoc.here());
        b.def.signals().add(s);
        b.declLocs.put(s, s.loc());
        return s;
    }

    /** A new input port of the current module. */
    public static Signal newInput(String name, int width) {
        var b = Elab.cur();
        var s = new Signal(Signal.Kind.INPUT, name, false, width, false, b.def, SourceLoc.here());
        b.def.ports().add(s);
        b.declLocs.put(s, s.loc());
        return s;
    }

    /** Drive input {@code port} of child instance {@code inst} with {@code v} (an expression of the current module). */
    public static void connectChildInput(org.eninehq.tessera.ir.Instance inst, Signal port, Expr v) {
        var b = Elab.cur();
        if (inst.parent() != b.def) throw TesseraException.here("instance " + inst.name() + " is not a child of " + b.def.name());
        b.assigns.computeIfAbsent(new ModuleBuilder.InstPort(inst, port), k -> new java.util.ArrayList<>())
                .add(new ModuleBuilder.Asg(List.of(), Elab.fit(v, port.width(), false, "'" + port.name() + "'", SourceLoc.here()), SourceLoc.here()));
    }

    /** An unconditional assignment, outside any when block. */
    public static void assignRaw(Signal s, Expr v) {
        var b = Elab.cur();
        b.assigns.computeIfAbsent(s, k -> new java.util.ArrayList<>())
                .add(new ModuleBuilder.Asg(List.of(), Elab.fit(v, s.width(), s.signed(), "'" + s.name() + "'", SourceLoc.here()), SourceLoc.here()));
    }

    /** Wrap an expression of the current module as a value of {@code type}, tagged with a stage. */
    public static <T extends Data> T wrap(HwType<T> type, Expr e, Signal signal, int stage, Object domain) {
        return type.wrap(new Data.Init(e, Elab.cur().def, signal, stage, domain));
    }

    /** The hardware type of an existing value. */
    public static <T extends Data> HwType<T> typeOf(T d) {
        return Module.typeOf(d);
    }

    /** Instantiate a child module in the current module. */
    public static <M extends Module> M instantiate(String name, java.util.function.Supplier<M> ctor) {
        return Elab.cur().module.instantiate(name, name == null, ctor);
    }

    /** True while inside a when/elseWhen/otherwise block. */
    public static boolean inWhen() {
        return !Elab.cur().scopes.isEmpty();
    }

    /** The source location of the innermost user frame. */
    public static SourceLoc here() {
        return SourceLoc.here();
    }
}
