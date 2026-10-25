package org.eninehq.tessera.core;

import java.math.BigInteger;
import org.eninehq.tessera.ir.Expr;
import org.eninehq.tessera.ir.OpKind;
import org.eninehq.tessera.ir.Ops;

/** A hardware value holding one constant of the Java enum {@code E}, encoded by ordinal. */
public final class HwEnum<E extends Enum<E>> extends Data {

    public record Type<E extends Enum<E>>(Class<E> enumClass) implements HwType<HwEnum<E>> {
        @Override public int width() { return widthOf(enumClass); }
        @Override public boolean signed() { return false; }
        @Override public HwEnum<E> wrap(Init init) { return new HwEnum<>(init, enumClass); }
        @Override public String toString() { return enumClass.getSimpleName(); }
    }

    public static <E extends Enum<E>> Type<E> of(Class<E> enumClass) {
        return new Type<>(enumClass);
    }

    static int widthOf(Class<?> enumClass) {
        int n = enumClass.getEnumConstants().length;
        return Math.max(1, 32 - Integer.numberOfLeadingZeros(n - 1));
    }

    private final Class<E> enumClass;

    HwEnum(Init init, Class<E> enumClass) {
        super(init);
        this.enumClass = enumClass;
    }

    @Override
    protected Data rewrap(Init init) {
        return new HwEnum<>(init, enumClass);
    }

    public Class<E> enumClass() { return enumClass; }

    /** A constant. */
    public static <E extends Enum<E>> HwEnum<E> lit(E value) {
        Class<E> cls = value.getDeclaringClass();
        var e = new Expr.Lit(BigInteger.valueOf(value.ordinal()), widthOf(cls), false);
        return new HwEnum<>(Elab.init(e), cls);
    }

    public Bool is(E value) {
        return UInt.mkBool(Ops.op(OpKind.EQ, 1, false, Elab.read(this), Elab.read(lit(value))));
    }

    public Bool eq(HwEnum<E> other) {
        check(other);
        return UInt.mkBool(Ops.op(OpKind.EQ, 1, false, Elab.read(this), Elab.read(other)));
    }

    public Bool ne(HwEnum<E> other) {
        return eq(other).not();
    }

    /** The raw encoding. */
    public UInt asUInt() {
        return UInt.mk(Ops.op(OpKind.CAST, width(), false, Elab.read(this)));
    }

    public HwEnum<E> named(String name) {
        @SuppressWarnings("unchecked")
        var r = (HwEnum<E>) namedData(name);
        return r;
    }

    public void set(E value) {
        Elab.assign(this, lit(value));
    }

    public void set(HwEnum<E> value) {
        check(value);
        Elab.assign(this, value);
    }

    private void check(HwEnum<?> other) {
        if (other.enumClass != enumClass)
            throw TesseraException.here("enum type mismatch: " + enumClass.getSimpleName() + " vs "
                    + other.enumClass.getSimpleName());
    }
}
