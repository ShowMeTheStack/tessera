package org.eninehq.tessera.core;

import java.math.BigInteger;
import org.eninehq.tessera.ir.Expr;
import org.eninehq.tessera.ir.OpKind;
import org.eninehq.tessera.ir.Ops;

/**
 * An unsigned bit vector.
 *
 * <p>Width rules: {@code add}/{@code sub} grow by one bit, {@code mul} sums the widths,
 * bitwise operations need equal widths, and nothing ever truncates silently. Use
 * {@link #trunc}, {@link #addWrap} or {@link #subWrap} to narrow explicitly.
 */
public class UInt extends Data {

    public record Type(int width) implements HwType<UInt> {
        public Type {
            if (width < 1) throw TesseraException.here("UInt width must be >= 1, got " + width);
        }
        @Override public boolean signed() { return false; }
        @Override public UInt wrap(Init init) { return new UInt(init); }
        @Override public String toString() { return "UInt<" + width + ">"; }
    }

    /** The type of {@code width}-bit unsigned values. */
    public static Type w(int width) {
        return new Type(width);
    }

    protected UInt(Init init) {
        super(init);
    }

    @Override
    protected Data rewrap(Init init) {
        return new UInt(init);
    }

    // ------------------------------------------------------------ helpers

    static UInt mk(Expr e) {
        return e.width() == 1 && !e.signed() ? new Bool(Elab.init(e)) : new UInt(Elab.init(e));
    }

    static UInt mkWide(Expr e) {
        return new UInt(Elab.init(e));
    }

    static Bool mkBool(Expr e) {
        return new Bool(Elab.init(e));
    }

    static Expr rd(Data d) {
        return Elab.read(d);
    }

    /** A literal of minimal width (at least 1). */
    public static UInt lit(long value) {
        if (value < 0) throw TesseraException.here("UInt literal must be non-negative, got " + value);
        int w = Math.max(1, 64 - Long.numberOfLeadingZeros(value));
        return lit(value, w);
    }

    public static UInt lit(long value, int width) {
        BigInteger v = ModuleBuilder.checkFits(value, width, false);
        Expr e = new Expr.Lit(v, width, false);
        return width == 1 ? new Bool(Elab.init(e)) : new UInt(Elab.init(e));
    }

    private UInt litSized(long value) {
        if (value < 0) throw TesseraException.here("UInt operand must be non-negative, got " + value);
        int need = Math.max(1, 64 - Long.numberOfLeadingZeros(value));
        if (need > width())
            throw TesseraException.here("constant " + value + " does not fit in the " + width() + "-bit operand");
        return lit(value, width());
    }

    private Expr sameWidth(UInt b, String op) {
        Expr be = rd(b);
        if (be.width() != width()) {
            if (be instanceof Expr.Lit && be.width() < width()) return Ops.ext(be, width());
            throw be.width() < width()
                    ? TesseraException.here(op + ": operand widths differ (" + width() + " vs " + be.width()
                            + "); extend with .zext(n) or narrow with .trunc(n) explicitly",
                            new TesseraException.Fix("zero-extend the operand to " + width() + " bits",
                                    "\\." + op + "\\(([A-Za-z_][A-Za-z0-9_]*)\\)", "." + op + "($1.zext(" + width() + "))"))
                    : TesseraException.here(op + ": operand widths differ (" + width() + " vs " + be.width()
                            + "); extend with .zext(n) or narrow with .trunc(n) explicitly",
                            new TesseraException.Fix("truncate the operand to " + width() + " bits",
                                    "\\." + op + "\\(([A-Za-z_][A-Za-z0-9_]*)\\)", "." + op + "($1.trunc(" + width() + "))"));
        }
        return be;
    }

    private Expr bin(OpKind k, UInt b, int w) {
        return Ops.op(k, w, false, rd(this), rd(b));
    }

    // ------------------------------------------------------------ arithmetic

    /** Sum, one bit wider than the wider operand: never overflows. */
    @Operator("+") public UInt add(UInt b) { return mkWide(bin(OpKind.ADD, b, Math.max(width(), b.width()) + 1)); }
    @Operator("+") public UInt add(long b) { return add(lit(b)); }

    /** Difference modulo 2^(max width + 1); the top bit is set when b > a. */
    @Operator("-") public UInt sub(UInt b) { return mkWide(bin(OpKind.SUB, b, Math.max(width(), b.width()) + 1)); }
    @Operator("-") public UInt sub(long b) { return sub(lit(b)); }

    /** Sum wrapped to the wider operand's width. */
    public UInt addWrap(UInt b) { return mk(bin(OpKind.ADD, b, Math.max(width(), b.width()))); }
    public UInt addWrap(long b) { return addWrap(litSized(b)); }

    /** Difference wrapped to the wider operand's width. */
    public UInt subWrap(UInt b) { return mk(bin(OpKind.SUB, b, Math.max(width(), b.width()))); }
    public UInt subWrap(long b) { return subWrap(litSized(b)); }

    /** Product; width is the sum of the operand widths. */
    @Operator("*") public UInt mul(UInt b) { return mkWide(bin(OpKind.MUL, b, width() + b.width())); }
    @Operator("*") public UInt mul(long b) { return mul(lit(b)); }

    // ------------------------------------------------------------ bitwise

    @Operator("&") public UInt and(UInt b) { return mk(Ops.op(OpKind.AND, width(), false, rd(this), sameWidth(b, "and"))); }
    @Operator("&") public UInt and(long b) { return and(litSized(b)); }
    @Operator("|") public UInt or(UInt b) { return mk(Ops.op(OpKind.OR, width(), false, rd(this), sameWidth(b, "or"))); }
    @Operator("|") public UInt or(long b) { return or(litSized(b)); }
    @Operator("^") public UInt xor(UInt b) { return mk(Ops.op(OpKind.XOR, width(), false, rd(this), sameWidth(b, "xor"))); }
    @Operator("^") public UInt xor(long b) { return xor(litSized(b)); }
    @Operator("~") public UInt not() { return mk(Ops.op(OpKind.NOT, width(), false, rd(this))); }

    public Bool orR() { return mkBool(Ops.op(OpKind.ORR, 1, false, rd(this))); }
    public Bool andR() { return mkBool(Ops.op(OpKind.ANDR, 1, false, rd(this))); }
    public Bool xorR() { return mkBool(Ops.op(OpKind.XORR, 1, false, rd(this))); }

    // ------------------------------------------------------------ shifts

    /** Shift left by a constant; the result grows by {@code n} bits. */
    @Operator("<<") public UInt shl(int n) {
        if (n < 0) throw TesseraException.here("shift amount must be >= 0");
        if (n == 0) return this;
        return mkWide(new Expr.Op(OpKind.SHL, java.util.List.of(rd(this)), width() + n, false, n, 0));
    }

    /** Logical shift right by a constant; width is unchanged. */
    @Operator(">>") public UInt shr(int n) {
        if (n < 0) throw TesseraException.here("shift amount must be >= 0");
        if (n == 0) return this;
        return mk(new Expr.Op(OpKind.SHR, java.util.List.of(rd(this)), width(), false, n, 0));
    }

    /** {@code a << b} with a hardware shift amount: same as {@link #dshl}. */
    @Operator("<<") public UInt shl(UInt amount) { return dshl(amount); }

    /** {@code a >> b} with a hardware shift amount: same as {@link #dshr}. */
    @Operator(">>") public UInt shr(UInt amount) { return dshr(amount); }

    /** Dynamic logical shift left; width is unchanged (bits shifted out are dropped). */
    public UInt dshl(UInt amount) { return mk(Ops.op(OpKind.DSHL, width(), false, rd(this), rd(amount))); }

    /** Dynamic logical shift right; width is unchanged. */
    public UInt dshr(UInt amount) { return mk(Ops.op(OpKind.DSHR, width(), false, rd(this), rd(amount))); }

    // ------------------------------------------------------------ comparison

    private Bool cmp(OpKind k, UInt b) { return mkBool(Ops.op(k, 1, false, rd(this), rd(b))); }

    public Bool eq(UInt b) { return cmp(OpKind.EQ, b); }
    public Bool eq(long b) { return eq(lit(b)); }
    public Bool ne(UInt b) { return cmp(OpKind.NE, b); }
    public Bool ne(long b) { return ne(lit(b)); }
    @Operator("<") public Bool lt(UInt b) { return cmp(OpKind.LT, b); }
    @Operator("<") public Bool lt(long b) { return lt(lit(b)); }
    @Operator("<=") public Bool le(UInt b) { return cmp(OpKind.LE, b); }
    @Operator("<=") public Bool le(long b) { return le(lit(b)); }
    @Operator(">") public Bool gt(UInt b) { return cmp(OpKind.GT, b); }
    @Operator(">") public Bool gt(long b) { return gt(lit(b)); }
    @Operator(">=") public Bool ge(UInt b) { return cmp(OpKind.GE, b); }
    @Operator(">=") public Bool ge(long b) { return ge(lit(b)); }

    // ------------------------------------------------------------ bits

    @Operator("[]") public Bool bit(int i) {
        if (i < 0 || i >= width()) throw TesseraException.here("bit " + i + " out of range for width " + width());
        return mkBool(new Expr.Op(OpKind.SLICE, java.util.List.of(rd(this)), 1, false, i, i));
    }

    /** Bits {@code [hi:lo]}, inclusive. */
    @Operator("[:]") public UInt bits(int hi, int lo) {
        if (lo < 0 || hi >= width() || hi < lo)
            throw TesseraException.here("bits(" + hi + ", " + lo + ") out of range for width " + width());
        if (hi == width() - 1 && lo == 0) return this;
        return mk(new Expr.Op(OpKind.SLICE, java.util.List.of(rd(this)), hi - lo + 1, false, hi, lo));
    }

    /** Concatenate: {@code this} becomes the most significant part. */
    @Operator("{}") public UInt cat(UInt... rest) {
        var args = new java.util.ArrayList<Expr>(rest.length + 1);
        args.add(rd(this));
        int w = width();
        for (var r : rest) {
            args.add(rd(r));
            w += r.width();
        }
        return mk(new Expr.Op(OpKind.CAT, args, w, false));
    }

    /** Keep the low {@code n} bits. This is the explicit way to narrow. */
    public UInt trunc(int n) {
        if (n < 1 || n > width()) throw TesseraException.here("trunc(" + n + ") invalid for width " + width());
        return n == width() ? this : bits(n - 1, 0);
    }

    /** Zero-extend to {@code n} bits. */
    public UInt zext(int n) {
        if (n < width()) throw TesseraException.here("zext(" + n + ") would narrow a " + width() + "-bit value; use trunc",
                new TesseraException.Fix("truncate instead", "\\.zext\\(" + n + "\\)", ".trunc(" + n + ")"));
        return n == width() ? this : mkWide(Ops.ext(rd(this), n));
    }

    /** Reinterpret the bits as a signed value of the same width. */
    public SInt asSInt() {
        return new SInt(Elab.init(Ops.op(OpKind.CAST, width(), true, rd(this))));
    }

    /** The same bits as a raw {@link Bits} vector. */
    public Bits asBits() {
        return Bits.mk(Ops.op(OpKind.CAST, width(), false, rd(this)));
    }

    /** Convert to a signed value that keeps the numeric value (one bit wider). */
    public SInt toSInt() {
        return zext(width() + 1).asSInt();
    }

    // ------------------------------------------------------------ naming and assignment

    /** Name this value in the generated hardware. */
    public UInt named(String name) {
        return (UInt) namedData(name);
    }

    /** Connect a value to this port, wire or register (last assignment wins). */
    public void set(UInt value) {
        Elab.assign(this, value);
    }

    public void set(long value) {
        Elab.assign(this, lit(value, width()));
    }
}
