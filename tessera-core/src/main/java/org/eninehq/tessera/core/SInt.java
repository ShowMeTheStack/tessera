package org.eninehq.tessera.core;

import java.math.BigInteger;
import java.util.List;
import org.eninehq.tessera.ir.Expr;
import org.eninehq.tessera.ir.OpKind;
import org.eninehq.tessera.ir.Ops;

/** A two's-complement signed bit vector. Width rules match {@link UInt}. */
public final class SInt extends Data {

    public record Type(int width) implements HwType<SInt> {
        public Type {
            if (width < 1) throw TesseraException.here("SInt width must be >= 1, got " + width);
        }
        @Override public boolean signed() { return true; }
        @Override public SInt wrap(Init init) { return new SInt(init); }
        @Override public String toString() { return "SInt<" + width + ">"; }
    }

    public static Type w(int width) {
        return new Type(width);
    }

    SInt(Init init) {
        super(init);
    }

    @Override
    protected Data rewrap(Init init) {
        return new SInt(init);
    }

    private static SInt mk(Expr e) {
        return new SInt(Elab.init(e));
    }

    private static Expr rd(Data d) {
        return Elab.read(d);
    }

    /** A literal of minimal width. */
    public static SInt lit(long value) {
        int w = BigInteger.valueOf(value).bitLength() + 1;
        return lit(value, w);
    }

    public static SInt lit(long value, int width) {
        BigInteger v = ModuleBuilder.checkFits(value, width, true);
        return new SInt(Elab.init(new Expr.Lit(v, width, true)));
    }

    private SInt litSized(long value) {
        if (BigInteger.valueOf(value).bitLength() + 1 > width())
            throw TesseraException.here("constant " + value + " does not fit in the " + width() + "-bit operand");
        return lit(value, width());
    }

    private Expr sameWidth(SInt b, String op) {
        Expr be = rd(b);
        if (be.width() != width()) {
            if (be instanceof Expr.Lit && be.width() < width()) return Ops.ext(be, width());
            throw TesseraException.here(op + ": operand widths differ (" + width() + " vs " + be.width() + ")");
        }
        return be;
    }

    private Expr bin(OpKind k, SInt b, int w) {
        return Ops.op(k, w, true, rd(this), rd(b));
    }

    @Operator("+") public SInt add(SInt b) { return mk(bin(OpKind.ADD, b, Math.max(width(), b.width()) + 1)); }
    @Operator("+") public SInt add(long b) { return add(lit(b)); }
    @Operator("-") public SInt sub(SInt b) { return mk(bin(OpKind.SUB, b, Math.max(width(), b.width()) + 1)); }
    @Operator("-") public SInt sub(long b) { return sub(lit(b)); }
    public SInt addWrap(SInt b) { return mk(bin(OpKind.ADD, b, Math.max(width(), b.width()))); }
    public SInt addWrap(long b) { return addWrap(litSized(b)); }
    public SInt subWrap(SInt b) { return mk(bin(OpKind.SUB, b, Math.max(width(), b.width()))); }
    public SInt subWrap(long b) { return subWrap(litSized(b)); }
    @Operator("*") public SInt mul(SInt b) { return mk(bin(OpKind.MUL, b, width() + b.width())); }
    @Operator("*") public SInt mul(long b) { return mul(lit(b)); }

    /** Negation, one bit wider (so the most negative value negates correctly). */
    @Operator("-") public SInt neg() {
        return mk(Ops.op(OpKind.SUB, width() + 1, true, new Expr.Lit(BigInteger.ZERO, width(), true), rd(this)));
    }

    @Operator("&") public SInt and(SInt b) { return mk(Ops.op(OpKind.AND, width(), true, rd(this), sameWidth(b, "and"))); }
    @Operator("|") public SInt or(SInt b) { return mk(Ops.op(OpKind.OR, width(), true, rd(this), sameWidth(b, "or"))); }
    @Operator("^") public SInt xor(SInt b) { return mk(Ops.op(OpKind.XOR, width(), true, rd(this), sameWidth(b, "xor"))); }
    @Operator("~") public SInt not() { return mk(Ops.op(OpKind.NOT, width(), true, rd(this))); }

    @Operator("<<") public SInt shl(int n) {
        if (n < 0) throw TesseraException.here("shift amount must be >= 0");
        if (n == 0) return this;
        return mk(new Expr.Op(OpKind.SHL, List.of(rd(this)), width() + n, true, n, 0));
    }

    /** Arithmetic shift right by a constant; width is unchanged. */
    @Operator(">>") public SInt sra(int n) {
        if (n < 0) throw TesseraException.here("shift amount must be >= 0");
        if (n == 0) return this;
        return mk(new Expr.Op(OpKind.SRA, List.of(rd(this)), width(), true, n, 0));
    }

    public SInt dshl(UInt amount) { return mk(Ops.op(OpKind.DSHL, width(), true, rd(this), rd(amount))); }
    public SInt dsra(UInt amount) { return mk(Ops.op(OpKind.DSRA, width(), true, rd(this), rd(amount))); }

    private Bool cmp(OpKind k, SInt b) { return UInt.mkBool(Ops.op(k, 1, false, rd(this), rd(b))); }

    public Bool eq(SInt b) { return cmp(OpKind.EQ, b); }
    public Bool eq(long b) { return eq(lit(b)); }
    public Bool ne(SInt b) { return cmp(OpKind.NE, b); }
    public Bool ne(long b) { return ne(lit(b)); }
    @Operator("<") public Bool lt(SInt b) { return cmp(OpKind.LT, b); }
    @Operator("<") public Bool lt(long b) { return lt(lit(b)); }
    @Operator("<=") public Bool le(SInt b) { return cmp(OpKind.LE, b); }
    @Operator("<=") public Bool le(long b) { return le(lit(b)); }
    @Operator(">") public Bool gt(SInt b) { return cmp(OpKind.GT, b); }
    @Operator(">") public Bool gt(long b) { return gt(lit(b)); }
    @Operator(">=") public Bool ge(SInt b) { return cmp(OpKind.GE, b); }
    @Operator(">=") public Bool ge(long b) { return ge(lit(b)); }

    /** Reinterpret as unsigned bits of the same width. */
    public UInt asUInt() {
        return UInt.mk(Ops.op(OpKind.CAST, width(), false, rd(this)));
    }

    @Operator("[:]") public UInt bits(int hi, int lo) { return asUInt().bits(hi, lo); }
    @Operator("[]") public Bool bit(int i) { return asUInt().bit(i); }

    /** Keep the low {@code n} bits, as a signed value. */
    public SInt trunc(int n) {
        if (n < 1 || n > width()) throw TesseraException.here("trunc(" + n + ") invalid for width " + width());
        return n == width() ? this : asUInt().bits(n - 1, 0).asSInt();
    }

    /** Sign-extend to {@code n} bits. */
    public SInt sext(int n) {
        if (n < width()) throw TesseraException.here("sext(" + n + ") would narrow a " + width() + "-bit value; use trunc",
                new TesseraException.Fix("truncate instead", "\\.sext\\(" + n + "\\)", ".trunc(" + n + ")"));
        return n == width() ? this : mk(Ops.ext(rd(this), n));
    }

    public SInt named(String name) {
        return (SInt) namedData(name);
    }

    public void set(SInt value) {
        Elab.assign(this, value);
    }

    public void set(long value) {
        Elab.assign(this, lit(value, width()));
    }
}
