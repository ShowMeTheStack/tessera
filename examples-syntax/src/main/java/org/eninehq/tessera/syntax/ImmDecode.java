package org.eninehq.tessera.syntax;

import org.eninehq.tessera.core.Module;
import org.eninehq.tessera.core.UInt;

/** RISC-V immediates with slices and concatenation: the instruction-set manual's diagrams, almost literally. */
public class ImmDecode extends Module {
    public ImmDecode() {
        UInt inst = input("inst", UInt.w(32));
        UInt<12> immI = inst[31:20];
        UInt<12> immS = {inst[31:25], inst[11:7]};
        UInt<13> immB = {inst[31:31], inst[7:7], inst[30:25], inst[11:8], UInt.lit(0, 1)};
        UInt<20> immU = inst[31:12];
        UInt<21> immJ = {inst[31:31], inst[19:12], inst[20:20], inst[30:21], UInt.lit(0, 1)};
        output("i", UInt.w(32)).set(immI.asSInt().sext(32).asUInt());
        output("s", UInt.w(32)).set(immS.asSInt().sext(32).asUInt());
        output("b", UInt.w(32)).set(immB.asSInt().sext(32).asUInt());
        output("u", UInt.w(32)).set({immU, UInt.lit(0, 12)});
        output("j", UInt.w(32)).set(immJ.asSInt().sext(32).asUInt());
    }
}
