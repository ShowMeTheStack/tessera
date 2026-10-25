package org.eninehq.tessera.notation;

import org.eninehq.tessera.core.Hw;
import org.eninehq.tessera.core.HwEnum;
import org.eninehq.tessera.core.Module;
import org.eninehq.tessera.core.UInt;

/** Tessera-javac notation: a 32-bit RISC-V ALU. */
public class Alu extends Module {
    public enum Op { ADD, SUB, AND, OR, XOR, SLL, SRL, SRA, SLT, SLTU }

    public Alu() {
        HwEnum<Op> op = input("op", HwEnum.of(Op.class));
        UInt a = input("a", UInt.w(32)), b = input("b", UInt.w(32));
        UInt<5> sh = b[4:0];
        output("y", UInt.w(32)).set(Hw.select(op, o -> switch (o) {
            case ADD -> a.addWrap(b);
            case SUB -> a.subWrap(b);
            case AND -> a & b;
            case OR -> a | b;
            case XOR -> a ^ b;
            case SLL -> a.dshl(sh);
            case SRL -> a.dshr(sh);
            case SRA -> a.asSInt().dsra(sh).asUInt();
            case SLT -> a.asSInt().lt(b.asSInt()).zext(32);
            case SLTU -> (a < b).zext(32);
        }));
    }
}
