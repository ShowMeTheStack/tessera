package org.eninehq.tessera.core;

import org.eninehq.tessera.ir.OpKind;
import org.eninehq.tessera.ir.Ops;

/** A single bit. */
public final class Bool extends UInt {

    public static final HwType<Bool> TYPE = new HwType<>() {
        @Override public int width() { return 1; }
        @Override public boolean signed() { return false; }
        @Override public Bool wrap(Init init) { return new Bool(init); }
        @Override public String toString() { return "Bool"; }
    };

    Bool(Init init) {
        super(init);
    }

    @Override
    protected Data rewrap(Init init) {
        return new Bool(init);
    }

    public static Bool of(boolean b) {
        return (Bool) UInt.lit(b ? 1 : 0, 1);
    }

    @Operator("&") public Bool and(Bool b) { return mkBool(Ops.op(OpKind.AND, 1, false, rd(this), rd(b))); }
    @Operator("|") public Bool or(Bool b) { return mkBool(Ops.op(OpKind.OR, 1, false, rd(this), rd(b))); }
    @Operator("^") public Bool xor(Bool b) { return mkBool(Ops.op(OpKind.XOR, 1, false, rd(this), rd(b))); }
    @Operator({"~", "!"}) @Override public Bool not() { return mkBool(Ops.op(OpKind.NOT, 1, false, rd(this))); }

    public Bool and(boolean b) { return and(of(b)); }
    public Bool or(boolean b) { return or(of(b)); }

    @Override public Bool named(String name) { return (Bool) namedData(name); }

    public void set(boolean value) {
        set(value ? 1 : 0);
    }
}
