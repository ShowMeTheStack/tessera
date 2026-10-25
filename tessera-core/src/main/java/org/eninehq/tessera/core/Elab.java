package org.eninehq.tessera.core;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import org.eninehq.tessera.ir.Expr;
import org.eninehq.tessera.ir.Ops;
import org.eninehq.tessera.ir.Signal;

/** Thread-local elaboration session: the stack of modules under construction. */
final class Elab {
    private Elab() {}

    static final class Session {
        final Deque<ModuleBuilder> stack = new ArrayDeque<>();
        boolean constructing;
        String pendingName;
    }

    private static final ThreadLocal<Session> SESSION = new ThreadLocal<>();

    static Session session() {
        return SESSION.get();
    }

    static Session openSession() {
        if (SESSION.get() != null) throw TesseraException.here("Tessera.elaborate(...) cannot be nested; use instance(...) for child modules");
        var s = new Session();
        SESSION.set(s);
        return s;
    }

    static void closeSession() {
        SESSION.remove();
    }

    static ModuleBuilder cur() {
        var s = SESSION.get();
        if (s == null || s.stack.isEmpty())
            throw TesseraException.here("hardware can only be built while a module is being elaborated "
                    + "(inside a Module constructor, via Tessera.elaborate or instance)");
        return s.stack.peek();
    }

    static ModuleBuilder curOrNull() {
        var s = SESSION.get();
        return s == null ? null : s.stack.peek();
    }

    /** Init for a new value computed in the current module. */
    static Data.Init init(Expr e) {
        if (e instanceof Expr.Lit) return new Data.Init(e, null, null, -1, null);
        var b = cur();
        OpLocations.note(e);
        return b.hook != null
                ? new Data.Init(e, b.def, null, b.hook.currentStage(), b.hook.domain())
                : new Data.Init(e, b.def, null, -1, null);
    }

    static Data.Init signalInit(Signal s) {
        var b = cur();
        return b.hook != null
                ? new Data.Init(new Expr.Ref(s), b.def, s, b.hook.currentStage(), b.hook.domain())
                : new Data.Init(new Expr.Ref(s), b.def, s, -1, null);
    }

    /** Init for ports, registers and memories: never tagged with a pipeline stage. */
    static Data.Init plainInit(Signal s) {
        return new Data.Init(new Expr.Ref(s), cur().def, s, -1, null);
    }

    /** Resolve {@code d} for use in the current module, applying the stage hook. */
    static Expr read(Data d) {
        var b = cur();
        Expr e = resolve(b, d);
        if (b.hook != null) return b.hook.adapt(d, e);
        if (d.stage >= 0)
            throw TesseraException.here("value from pipeline stage " + d.stage + " used outside its pipeline");
        return e;
    }

    /** Resolve without stage adaptation. */
    static Expr resolve(ModuleBuilder b, Data d) {
        if (d.owner == null || d.owner == b.def) return d.expr;
        Signal s = d.signal;
        if (s != null && s.isPort()) {
            var inst = s.owner().instantiatedAs();
            if (inst != null && inst.parent() == b.def) {
                if (s.kind() == Signal.Kind.OUTPUT) return new Expr.InstRef(inst, s);
                throw TesseraException.here("cannot read input port '" + s.name() + "' of child instance '"
                        + inst.name() + "'; use the value you drive it with instead");
            }
        }
        throw TesseraException.here("value " + d.describe() + " belongs to module " + d.owner.name()
                + " but is used in module " + b.def.name()
                + " (only a direct child's ports can be used across modules)");
    }

    static void assign(Data target, Data value) {
        var b = cur();
        var loc = SourceLoc.here();
        Signal ts = target.signal;
        if (ts == null) throw new TesseraException(loc, "cannot assign to an expression; only ports, wires and registers can be assigned");
        Expr v = read(value);
        if (b.hook != null && target.stage >= 0 && target.stage != b.hook.currentStage())
            throw new TesseraException(loc, "cannot assign to " + target.describe() + " (stage " + target.stage
                    + ") from stage " + b.hook.currentStage());
        Object key;
        if (ts.owner() == b.def) {
            key = switch (ts.kind()) {
                case INPUT -> throw new TesseraException(loc, "cannot assign to input port '" + ts.name() + "'");
                case NODE -> throw new TesseraException(loc, "cannot assign to named value '" + ts.name() + "'");
                default -> ts;
            };
        } else {
            var inst = ts.owner().instantiatedAs();
            if (inst == null || inst.parent() != b.def)
                throw new TesseraException(loc, "cannot assign to " + target.describe() + " of module "
                        + ts.owner().name() + " from module " + b.def.name());
            if (ts.kind() != Signal.Kind.INPUT)
                throw new TesseraException(loc, "cannot assign to output port '" + ts.name() + "' of child instance '"
                        + inst.name() + "'");
            key = new ModuleBuilder.InstPort(inst, ts);
        }
        b.addAssign(key, fit(v, ts.width(), ts.signed(), target.describe(), loc), loc);
    }

    /** Extend {@code v} to {@code width}, rejecting narrowing and signedness mismatches. */
    static Expr fit(Expr v, int width, boolean signed, String what, SourceLoc loc) {
        if (v.signed() != signed)
            throw new TesseraException(loc, "cannot assign " + (v.signed() ? "SInt" : "UInt") + " value to "
                    + (signed ? "SInt" : "UInt") + " " + what + "; convert with asSInt()/asUInt()");
        if (v.width() > width)
            throw new TesseraException(loc, "value is " + v.width() + " bits wide but " + what + " is " + width
                    + " bits; narrow it explicitly with .trunc(" + width + ") or use a wrapping operation", List.of(
                    new TesseraException.Fix("use a wrapping add", "\\.add\\(", ".addWrap("),
                    new TesseraException.Fix("use a wrapping subtract", "\\.sub\\(", ".subWrap("),
                    new TesseraException.Fix("truncate to " + width + " bits", "\\.set\\((.*)\\);", ".set($1.trunc(" + width + "));")));
        return Ops.ext(v, width);
    }

    /** Create a named node holding the value of {@code d}, or rename {@code d}'s signal if it has an automatic name. */
    static Data.Init name(Data d, String name) {
        var b = cur();
        if (d.signal != null && d.signal.owner() == b.def) {
            if (d.signal.kind() != Signal.Kind.INPUT && d.signal.kind() != Signal.Kind.OUTPUT) d.signal.rename(name, false);
            return new Data.Init(d.expr, d.owner, d.signal, d.stage, d.stageDomain);
        }
        Expr e = read(d);
        var s = new Signal(Signal.Kind.NODE, name, false, e.width(), e.signed(), b.def, SourceLoc.here());
        s.setDriver(e);
        b.def.signals().add(s);
        return signalInit(s);
    }
}
