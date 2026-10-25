package org.eninehq.tessera.riscv;

import org.eninehq.tessera.core.Bool;
import org.eninehq.tessera.core.Hw;
import org.eninehq.tessera.core.TesseraException;
import org.eninehq.tessera.core.UInt;

/** The hardware domain: RISC-V semantics as 32-bit hardware. Use while elaborating a module. */
public final class HwDom implements Dom<UInt, Bool> {
    public static final HwDom INSTANCE = new HwDom(false);
    /** M instructions as riscv-formal's alternative operations (for formal verification only). */
    public static final HwDom ALTOPS = new HwDom(true);

    private static final ThreadLocal<HwDom> CURRENT = ThreadLocal.withInitial(() -> INSTANCE);
    private final boolean altops;

    private HwDom(boolean altops) {
        this.altops = altops;
    }

    /** The domain cores elaborate with: normally {@link #INSTANCE}; {@link #ALTOPS} inside {@link #withAltops}. */
    public static HwDom current() {
        return CURRENT.get();
    }

    /** Run {@code body} (an elaboration) with riscv-formal alternative operations for M instructions. */
    public static <T> T withAltops(java.util.function.Supplier<T> body) {
        CURRENT.set(ALTOPS);
        try {
            return body.get();
        } finally {
            CURRENT.set(INSTANCE);
        }
    }

    @Override public boolean altops() { return altops; }

    private static UInt w32(UInt x) {
        if (x.width() != 32) throw TesseraException.here("expected a 32-bit value, got " + x.width() + " bits");
        return x;
    }

    @Override public UInt k(long value) { return UInt.lit(value & 0xFFFF_FFFFL, 32); }
    @Override public Bool bk(boolean value) { return Bool.of(value); }
    @Override public UInt add(UInt a, UInt b) { return w32(a).addWrap(w32(b)); }
    @Override public UInt sub(UInt a, UInt b) { return w32(a).subWrap(w32(b)); }
    @Override public UInt and(UInt a, UInt b) { return w32(a).and(w32(b)); }
    @Override public UInt or(UInt a, UInt b) { return w32(a).or(w32(b)); }
    @Override public UInt xor(UInt a, UInt b) { return w32(a).xor(w32(b)); }
    @Override public UInt sll(UInt a, UInt s) { return w32(a).dshl(s.bits(4, 0)); }
    @Override public UInt srl(UInt a, UInt s) { return w32(a).dshr(s.bits(4, 0)); }
    @Override public UInt sra(UInt a, UInt s) { return w32(a).asSInt().dsra(s.bits(4, 0)).asUInt(); }
    @Override public UInt shl(UInt a, int n) { return n >= 32 ? k(0) : w32(a).bits(31 - n, 0).shl(n); }
    @Override public Bool eq(UInt a, UInt b) { return a.eq(b); }
    @Override public Bool lt(UInt a, UInt b) { return w32(a).asSInt().lt(w32(b).asSInt()); }
    @Override public Bool ltu(UInt a, UInt b) { return a.lt(b); }
    @Override public UInt mux(Bool c, UInt a, UInt b) { return Hw.mux(c, w32(a), w32(b)); }
    @Override public Bool bmux(Bool c, Bool a, Bool b) { return Hw.mux(c, a, b); }
    @Override public Bool bnot(Bool a) { return a.not(); }
    @Override public Bool band(Bool a, Bool b) { return a.and(b); }
    @Override public Bool bor(Bool a, Bool b) { return a.or(b); }
    @Override public UInt bits(UInt x, int hi, int lo) { return x.bits(hi, lo).zext(32); }
    @Override public UInt sext(UInt x, int fromWidth) { return x.bits(fromWidth - 1, 0).asSInt().sext(32).asUInt(); }
    @Override public Bool bit(UInt x, int i) { return x.bit(i); }
    @Override public UInt fromBool(Bool b) { return b.zext(32); }
    @Override public UInt mul16(UInt a, UInt b) { return a.bits(15, 0).mul(b.bits(15, 0)); }
}
