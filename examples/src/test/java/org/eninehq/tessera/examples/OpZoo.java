package org.eninehq.tessera.examples;

import org.eninehq.tessera.core.Hw;
import org.eninehq.tessera.core.Module;
import org.eninehq.tessera.core.SInt;
import org.eninehq.tessera.core.UInt;

/** Exercises every IR operation at awkward widths, for backend cross-checks. */
class OpZoo extends Module {
    OpZoo() {
        UInt a = input("a", UInt.w(13));
        UInt b = input("b", UInt.w(7));
        UInt c = input("c", UInt.w(13));
        SInt s = input("s", SInt.w(9));
        SInt t = input("t", SInt.w(5));
        UInt sh = input("sh", UInt.w(6));
        UInt wide = input("wide", UInt.w(64));

        output("add", UInt.w(14)).set(a.add(b));
        output("sub", UInt.w(14)).set(a.sub(b));
        output("addw", UInt.w(13)).set(a.addWrap(c));
        output("mul", UInt.w(20)).set(a.mul(b));
        output("and", UInt.w(13)).set(a.and(c));
        output("or", UInt.w(13)).set(a.or(c));
        output("xor", UInt.w(13)).set(a.xor(c));
        output("not", UInt.w(13)).set(a.not());
        output("shl", UInt.w(16)).set(a.shl(3));
        output("shr", UInt.w(13)).set(a.shr(5));
        output("dshl", UInt.w(13)).set(a.dshl(sh));
        output("dshr", UInt.w(13)).set(a.dshr(sh));
        output("cmp", UInt.w(6)).set(a.eq(b).cat(a.ne(c), a.lt(b), a.le(c), a.gt(b), a.ge(c)));
        output("scmp", UInt.w(6)).set(s.eq(t).cat(s.ne(t), s.lt(t), s.le(t), s.gt(t), s.ge(t)));
        output("sadd", SInt.w(10)).set(s.add(t));
        output("ssub", SInt.w(10)).set(s.sub(t));
        output("smul", SInt.w(14)).set(s.mul(t));
        output("sneg", SInt.w(10)).set(s.neg());
        output("sra", SInt.w(9)).set(s.sra(3));
        output("dsra", SInt.w(9)).set(s.dsra(sh));
        output("sext", SInt.w(16)).set(t.sext(16));
        output("mux", UInt.w(13)).set(Hw.mux(b.bit(0), a, b));
        output("smux", SInt.w(9)).set(Hw.mux(b.bit(1), s, t));
        output("slice", UInt.w(5)).set(a.bits(9, 5));
        output("red", UInt.w(3)).set(a.andR().cat(a.orR(), a.xorR()));
        output("redb", UInt.w(3)).set(b.bits(2, 0).andR().cat(UInt.lit(0, 7).orR(), b.xorR()));
        output("wadd", UInt.w(64)).set(wide.addWrap(wide.shr(1)));
        output("wmul", UInt.w(64)).set(wide.trunc(32).mul(wide.bits(63, 32)));
        output("wsra", UInt.w(64)).set(wide.asSInt().dsra(sh).asUInt());
        output("wlt", UInt.w(2)).set(wide.lt(a).cat(wide.asSInt().lt(SInt.lit(0, 64))));
        output("cast", UInt.w(9)).set(s.asUInt());
        output("tosint", SInt.w(14)).set(a.toSInt());
    }
}
