package org.eninehq.tessera.examples;

import org.eninehq.tessera.core.Hw;
import org.eninehq.tessera.core.Mem;
import org.eninehq.tessera.core.Module;
import org.eninehq.tessera.core.SInt;
import org.eninehq.tessera.core.UInt;

/** Every IR operation on values wider than 64 bits (and mixed wide/narrow operands). */
class WideZoo extends Module {
    WideZoo() {
        UInt a = input("a", UInt.w(100));
        UInt b = input("b", UInt.w(130));
        UInt c = input("c", UInt.w(100));
        SInt s = input("s", SInt.w(90));
        SInt t = input("t", SInt.w(70));
        UInt sh = input("sh", UInt.w(8));
        UInt n = input("n", UInt.w(20));

        output("add", UInt.w(131)).set(a.add(b));
        output("sub", UInt.w(131)).set(a.sub(b));
        output("addw", UInt.w(100)).set(a.addWrap(c));
        output("mul", UInt.w(230)).set(a.mul(b));
        output("muln", UInt.w(120)).set(a.mul(n));
        output("and", UInt.w(100)).set(a.and(c));
        output("or", UInt.w(100)).set(a.or(c));
        output("xor", UInt.w(100)).set(a.xor(c));
        output("not", UInt.w(100)).set(a.not());
        output("shl", UInt.w(167)).set(a.shl(67));
        output("shr", UInt.w(100)).set(a.shr(70));
        output("dshl", UInt.w(100)).set(a.dshl(sh));
        output("dshr", UInt.w(130)).set(b.dshr(sh));
        output("cmp", UInt.w(6)).set(a.eq(b).cat(a.ne(c), a.lt(b), a.le(c), a.gt(b), a.ge(c)));
        output("scmp", UInt.w(6)).set(s.eq(t).cat(s.ne(t), s.lt(t), s.le(t), s.gt(t), s.ge(t)));
        output("sadd", SInt.w(91)).set(s.add(t));
        output("smul", SInt.w(160)).set(s.mul(t));
        output("sneg", SInt.w(91)).set(s.neg());
        output("sra", SInt.w(90)).set(s.sra(66));
        output("dsra", SInt.w(90)).set(s.dsra(sh));
        output("sext", SInt.w(200)).set(t.sext(200));
        output("mux", UInt.w(130)).set(Hw.mux(n.bit(0), a, b));
        output("slice", UInt.w(70)).set(b.bits(129, 60));
        output("slicen", UInt.w(9)).set(b.bits(70, 62));
        output("cat", UInt.w(250)).set(n.cat(b, a));
        output("red", UInt.w(3)).set(a.andR().cat(b.orR(), c.xorR()));
        output("ext", UInt.w(190)).set(n.zext(190));
        UInt acc = reg("acc", UInt.w(130), 0);
        acc.set(acc.addWrap(b).xor(a.zext(130)));
        output("acc_o", UInt.w(130)).set(acc);
        Mem<UInt> mem = mem("m", UInt.w(96), 8);
        mem.write(n.bits(2, 0), a.trunc(96));
        output("mrd", UInt.w(96)).set(mem.read(sh.bits(2, 0)));
    }
}
