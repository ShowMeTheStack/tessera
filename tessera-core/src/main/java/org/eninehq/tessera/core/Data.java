package org.eninehq.tessera.core;

import org.eninehq.tessera.ir.Expr;
import org.eninehq.tessera.ir.ModuleDef;
import org.eninehq.tessera.ir.Signal;

/**
 * A hardware value: a port, wire, register, or the result of an operation.
 *
 * <p>Data objects are created while a module is being elaborated and belong to that
 * module; literals belong to no module and can be used anywhere.
 */
public abstract class Data {

    /** Internal: the fields every Data carries. */
    public record Init(Expr expr, ModuleDef owner, Signal signal, int stage, Object stageDomain) {}

    final Expr expr;
    final ModuleDef owner;
    final Signal signal;
    final int stage;
    final Object stageDomain;

    protected Data(Init init) {
        this.expr = init.expr();
        this.owner = init.owner();
        this.signal = init.signal();
        this.stage = init.stage();
        this.stageDomain = init.stageDomain();
    }

    public int width() { return expr.width(); }

    public boolean signed() { return expr.signed(); }

    /** The named signal behind this value, or null for operation results and literals. */
    public Signal signal() { return signal; }

    /** The module definition this value belongs to, or null for literals. */
    public ModuleDef owner() { return owner; }

    /** Internal: the expression in the owner module's scope. */
    public Expr expr() { return expr; }

    /** Pipeline stage this value belongs to, or -1 when not in a pipeline. */
    public int stage() { return stage; }

    /** Internal: the pipeline this value's stage refers to. */
    public Object stageDomain() { return stageDomain; }

    /** Internal: a value of the same class wrapping {@code init}. */
    protected abstract Data rewrap(Init init);

    /** Internal: same value, re-tagged with a pipeline stage. */
    public Data withStage(int stage, Object domain) {
        return rewrap(new Init(expr, owner, signal, stage, domain));
    }

    /** Internal: a new value of the same class for an expression in the current module. */
    public Data derive(Expr e) {
        return rewrap(Elab.init(e));
    }

    /**
     * Give this value a name in the generated hardware. For operation results this creates
     * a named node; use the returned value.
     */
    public Data namedData(String name) {
        return rewrap(Elab.name(this, name));
    }

    /**
     * Name {@code value} after the local variable it is stored in. The Tessera javac calls this
     * for every hardware local ({@code UInt sum = a + b;} becomes a wire named {@code sum});
     * outside elaboration, and for constants and ports, it returns {@code value} unchanged.
     */
    @Operator("name")
    @SuppressWarnings("unchecked")
    public static <T> T nameLocal(T value, String name) {
        if (!(value instanceof Data d) || d.owner == null || Elab.curOrNull() == null || Elab.curOrNull().def != d.owner)
            return value;
        if (d.signal != null && d.signal.isPort()) return value;
        return (T) d.namedData(name);
    }

    String describe() {
        return signal != null ? "'" + signal.name() + "'" : "expression";
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "<" + width() + ">" + (signal != null ? " " + signal.name() : "");
    }
}
