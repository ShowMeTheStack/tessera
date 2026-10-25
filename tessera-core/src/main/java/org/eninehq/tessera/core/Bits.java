package org.eninehq.tessera.core;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import org.eninehq.tessera.ir.Expr;
import org.eninehq.tessera.ir.OpKind;
import org.eninehq.tessera.ir.Ops;

/**
 * A raw bit vector with no arithmetic: only bitwise operations, slicing and concatenation.
 * Convert with {@link #asUInt()} / {@link #asSInt()} to compute with it.
 */
public final class Bits extends Data {

    public record Type(int width) implements HwType<Bits> {
        public Type {
            if (width < 1) throw TesseraException.here("Bits width must be >= 1, got " + width);
        }
        @Override public boolean signed() { return false; }
        @Override public Bits wrap(Init init) { return new Bits(init); }
        @Override public String toString() { return "Bits<" + width + ">"; }
    }

    public static Type w(int width) {
        return new Type(width);
    }

    Bits(Init init) {
        super(init);
    }

    @Override protected Data rewrap(Init init) { return new Bits(init); }

    static Bits mk(Expr e) {
        return new Bits(Elab.init(e));
    }

    public static Bits lit(long value, int width) {
        BigInteger v = ModuleBuilder.checkFits(value, width, false);
        return mk(new Expr.Lit(v, width, false));
    }

    private Expr same(Bits b, String op) {
        Expr be = Elab.read(b);
        if (be.width() != width())
            throw TesseraException.here(op + ": operand widths differ (" + width() + " vs " + be.width() + ")");
        return be;
    }

    @Operator("&") public Bits and(Bits b) { return mk(Ops.op(OpKind.AND, width(), false, Elab.read(this), same(b, "and"))); }
    @Operator("|") public Bits or(Bits b) { return mk(Ops.op(OpKind.OR, width(), false, Elab.read(this), same(b, "or"))); }
    @Operator("^") public Bits xor(Bits b) { return mk(Ops.op(OpKind.XOR, width(), false, Elab.read(this), same(b, "xor"))); }
    @Operator("~") public Bits not() { return mk(Ops.op(OpKind.NOT, width(), false, Elab.read(this))); }

    public Bool eq(Bits b) { return UInt.mkBool(Ops.op(OpKind.EQ, 1, false, Elab.read(this), same(b, "eq"))); }
    public Bool ne(Bits b) { return UInt.mkBool(Ops.op(OpKind.NE, 1, false, Elab.read(this), same(b, "ne"))); }

    @Operator("[]") public Bool bit(int i) {
        if (i < 0 || i >= width()) throw TesseraException.here("bit " + i + " out of range for width " + width());
        return UInt.mkBool(new Expr.Op(OpKind.SLICE, List.of(Elab.read(this)), 1, false, i, i));
    }

    @Operator("[:]") public Bits bits(int hi, int lo) {
        if (lo < 0 || hi >= width() || hi < lo)
            throw TesseraException.here("bits(" + hi + ", " + lo + ") out of range for width " + width());
        if (hi == width() - 1 && lo == 0) return this;
        return mk(new Expr.Op(OpKind.SLICE, List.of(Elab.read(this)), hi - lo + 1, false, hi, lo));
    }

    @Operator("{}") public Bits cat(Bits... rest) {
        var args = new ArrayList<Expr>(rest.length + 1);
        args.add(Elab.read(this));
        int w = width();
        for (var r : rest) {
            args.add(Elab.read(r));
            w += r.width();
        }
        return mk(new Expr.Op(OpKind.CAT, args, w, false));
    }

    public UInt asUInt() { return UInt.mk(Ops.op(OpKind.CAST, width(), false, Elab.read(this))); }

    public SInt asSInt() { return new SInt(Elab.init(Ops.op(OpKind.CAST, width(), true, Elab.read(this)))); }

    public Bits named(String name) { return (Bits) namedData(name); }

    public void set(Bits value) {
        if (value.width() != width())
            throw TesseraException.here("Bits assignment needs equal widths (" + value.width() + " into " + width() + ")");
        Elab.assign(this, value);
    }

    public void set(long value) {
        Elab.assign(this, lit(value, width()));
    }
}
