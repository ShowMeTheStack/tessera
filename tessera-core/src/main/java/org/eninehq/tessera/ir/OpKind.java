package org.eninehq.tessera.ir;

/**
 * Primitive operations of the Tessera IR.
 *
 * <p>Unless noted otherwise, operands are first extended (zero- or sign-extended
 * according to their own signedness) to the result width, the operation is computed,
 * and the result is truncated to the result width. Comparisons extend both operands
 * to the wider of the two operand widths instead.
 */
public enum OpKind {
    ADD, SUB, MUL,
    AND, OR, XOR,
    /** Bitwise complement; operand width equals result width. */
    NOT,
    /** Shift left by constant {@code p0}; result width = operand width + p0. */
    SHL,
    /** Logical shift right by constant {@code p0}; result width = operand width. */
    SHR,
    /** Arithmetic shift right by constant {@code p0}; result width = operand width. */
    SRA,
    /** Dynamic shifts: args (value, amount); result width = value width. */
    DSHL, DSHR, DSRA,
    EQ, NE, LT, LE, GT, GE,
    /** args (cond, then, else). */
    MUX,
    /** Concatenation; first argument is most significant. */
    CAT,
    /** Bit slice {@code [p0:p1]} (hi:lo, inclusive). */
    SLICE,
    /** Extension to the result width, by the operand's signedness. */
    EXT,
    /** Reinterpret signedness; same width. */
    CAST,
    ANDR, ORR, XORR;

    public boolean isComparison() {
        return switch (this) {
            case EQ, NE, LT, LE, GT, GE -> true;
            default -> false;
        };
    }
}
