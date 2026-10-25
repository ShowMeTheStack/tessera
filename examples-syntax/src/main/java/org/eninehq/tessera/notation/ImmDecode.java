package org.eninehq.tessera.notation;

import org.eninehq.tessera.core.Module;
import org.eninehq.tessera.core.UInt;

/** Tessera-javac notation: the five RISC-V immediate formats. */
public class ImmDecode extends Module {
    public ImmDecode() {
        UInt inst = input("inst", UInt.w(32));
        UInt<12> i = inst[31:20];
        UInt<12> s = {inst[31:25], inst[11:7]};
        UInt<13> b = {inst[31:31], inst[7:7], inst[30:25], inst[11:8], UInt.lit(0, 1)};
        UInt<21> j = {inst[31:31], inst[19:12], inst[20:20], inst[30:21], UInt.lit(0, 1)};
        output("i", UInt.w(32)).set(i.asSInt().sext(32).asUInt());
        output("s", UInt.w(32)).set(s.asSInt().sext(32).asUInt());
        output("b", UInt.w(32)).set(b.asSInt().sext(32).asUInt());
        output("u", UInt.w(32)).set({inst[31:12], UInt.lit(0, 12)});
        output("j", UInt.w(32)).set(j.asSInt().sext(32).asUInt());
    }
}
