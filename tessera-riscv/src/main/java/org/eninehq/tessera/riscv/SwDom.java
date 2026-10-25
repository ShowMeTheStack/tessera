package org.eninehq.tessera.riscv;

/** The software domain: RISC-V semantics as plain Java on {@code int}. */
public final class SwDom implements Dom<Integer, Boolean> {
    public static final SwDom INSTANCE = new SwDom();

    private SwDom() {}

    @Override public Integer k(long value) { return (int) value; }
    @Override public Boolean bk(boolean value) { return value; }
    @Override public Integer add(Integer a, Integer b) { return a + b; }
    @Override public Integer sub(Integer a, Integer b) { return a - b; }
    @Override public Integer and(Integer a, Integer b) { return a & b; }
    @Override public Integer or(Integer a, Integer b) { return a | b; }
    @Override public Integer xor(Integer a, Integer b) { return a ^ b; }
    @Override public Integer sll(Integer a, Integer s) { return a << (s & 31); }
    @Override public Integer srl(Integer a, Integer s) { return a >>> (s & 31); }
    @Override public Integer sra(Integer a, Integer s) { return a >> (s & 31); }
    @Override public Integer shl(Integer a, int n) { return n >= 32 ? 0 : a << n; }
    @Override public Boolean eq(Integer a, Integer b) { return a.intValue() == b.intValue(); }
    @Override public Boolean lt(Integer a, Integer b) { return a < b; }
    @Override public Boolean ltu(Integer a, Integer b) { return Integer.compareUnsigned(a, b) < 0; }
    @Override public Integer mux(Boolean c, Integer a, Integer b) { return c ? a : b; }
    @Override public Boolean bmux(Boolean c, Boolean a, Boolean b) { return c ? a : b; }
    @Override public Boolean bnot(Boolean a) { return !a; }
    @Override public Boolean band(Boolean a, Boolean b) { return a && b; }
    @Override public Boolean bor(Boolean a, Boolean b) { return a || b; }

    @Override public Integer bits(Integer x, int hi, int lo) {
        int w = hi - lo + 1;
        return w == 32 ? x : (x >>> lo) & ((1 << w) - 1);
    }

    @Override public Integer sext(Integer x, int fromWidth) {
        return fromWidth >= 32 ? x : (x << (32 - fromWidth)) >> (32 - fromWidth);
    }

    @Override public Boolean bit(Integer x, int i) { return ((x >>> i) & 1) != 0; }
    @Override public Integer fromBool(Boolean b) { return b ? 1 : 0; }
    @Override public Integer mul16(Integer a, Integer b) { return (a & 0xFFFF) * (b & 0xFFFF); }
}
