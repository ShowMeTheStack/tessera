package org.eninehq.tessera.riscv;

/**
 * The operations RISC-V semantics need, over 32-bit values {@code V} and booleans {@code B}.
 *
 * <p>The semantics in {@link Rv32i} are written once against this interface. {@link SwDom}
 * runs them as ordinary Java (the golden instruction-set simulator); {@link HwDom} turns
 * the same code into hardware. Code written against a domain must be branch-free: use
 * {@link #mux} instead of {@code if}.
 */
public interface Dom<V, B> {
    /** A 32-bit constant. */
    V k(long value);

    B bk(boolean value);

    V add(V a, V b);
    V sub(V a, V b);
    V and(V a, V b);
    V or(V a, V b);
    V xor(V a, V b);

    /** Shifts by the low 5 bits of {@code shamt}. */
    V sll(V a, V shamt);
    V srl(V a, V shamt);
    V sra(V a, V shamt);

    /** Constant shift left (bits shifted out are dropped). */
    V shl(V a, int n);

    B eq(V a, V b);
    /** Signed less-than. */
    B lt(V a, V b);
    B ltu(V a, V b);

    V mux(B c, V a, V b);
    B bmux(B c, B a, B b);

    B bnot(B a);
    B band(B a, B b);
    B bor(B a, B b);

    /** Bits {@code [hi:lo]} of {@code x}, zero-extended to 32 bits. */
    V bits(V x, int hi, int lo);

    /** Sign-extend the low {@code fromWidth} bits of {@code x} to 32 bits. */
    V sext(V x, int fromWidth);

    B bit(V x, int i);

    /** 1 or 0. */
    V fromBool(B b);

    /** The full 32-bit product of the low 16 bits of {@code a} and {@code b} (unsigned). */
    V mul16(V a, V b);

    /**
     * riscv-formal's "alternative operations": when true, M instructions compute simple
     * arithmetic functions instead of products and quotients so formal checks stay tractable.
     */
    default boolean altops() { return false; }

    default B ne(V a, V b) { return bnot(eq(a, b)); }

    default B is(V x, long value) { return eq(x, k(value)); }
}
