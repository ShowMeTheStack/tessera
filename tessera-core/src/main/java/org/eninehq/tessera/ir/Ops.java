package org.eninehq.tessera.ir;

import java.math.BigInteger;
import java.util.List;

/** Factory helpers for building well-formed IR expressions. */
public final class Ops {
    private Ops() {}

    public static Expr.Lit lit(long value, int width) {
        return lit(BigInteger.valueOf(value), width, false);
    }

    /** A literal; negative values are encoded in two's complement at the given width. */
    public static Expr.Lit lit(BigInteger value, int width, boolean signed) {
        if (value.signum() < 0) value = value.add(BigInteger.ONE.shiftLeft(width));
        return new Expr.Lit(value, width, signed);
    }

    public static final Expr.Lit TRUE = lit(1, 1);
    public static final Expr.Lit FALSE = lit(0, 1);

    public static Expr op(OpKind k, int width, boolean signed, Expr... args) {
        return new Expr.Op(k, List.of(args), width, signed);
    }

    /** Extend (or keep) {@code e} to {@code width} bits by its own signedness. */
    public static Expr ext(Expr e, int width) {
        if (e.width() == width) return e;
        if (e.width() > width) throw new IllegalArgumentException("cannot extend " + e.width() + " bits to " + width);
        if (e instanceof Expr.Lit l) {
            BigInteger v = l.value();
            if (l.signed() && v.testBit(l.width() - 1)) v = v.subtract(BigInteger.ONE.shiftLeft(l.width()));
            return lit(v, width, l.signed());
        }
        return new Expr.Op(OpKind.EXT, List.of(e), width, e.signed());
    }

    public static Expr not(Expr a) {
        if (a instanceof Expr.Lit l && a.width() == 1) return l.value().signum() == 0 ? TRUE : FALSE;
        return op(OpKind.NOT, a.width(), a.signed(), a);
    }

    public static Expr and(Expr a, Expr b) {
        if (a.width() == 1 && b.width() == 1) {
            if (isConst(a, 1)) return b;
            if (isConst(b, 1)) return a;
            if (isConst(a, 0) || isConst(b, 0)) return FALSE;
        }
        return op(OpKind.AND, Math.max(a.width(), b.width()), a.signed(), a, b);
    }

    public static Expr or(Expr a, Expr b) {
        if (a.width() == 1 && b.width() == 1) {
            if (isConst(a, 0)) return b;
            if (isConst(b, 0)) return a;
            if (isConst(a, 1) || isConst(b, 1)) return TRUE;
        }
        return op(OpKind.OR, Math.max(a.width(), b.width()), a.signed(), a, b);
    }

    public static Expr mux(Expr c, Expr t, Expr f) {
        if (isConst(c, 1)) return t;
        if (isConst(c, 0)) return f;
        if (t == f) return t;
        int w = Math.max(t.width(), f.width());
        return op(OpKind.MUX, w, t.signed(), c, t, f);
    }

    public static boolean isConst(Expr e, long v) {
        return e instanceof Expr.Lit l && l.value().equals(BigInteger.valueOf(v));
    }
}
