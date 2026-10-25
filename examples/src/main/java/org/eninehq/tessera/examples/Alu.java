package org.eninehq.tessera.examples;

import org.eninehq.tessera.core.Hw;
import org.eninehq.tessera.core.HwEnum;
import org.eninehq.tessera.core.Module;
import org.eninehq.tessera.core.UInt;

/** A RISC-V style ALU. The operation multiplexer comes from an exhaustive Java switch. */
public class Alu extends Module {
    public enum Op { ADD, SUB, AND, OR, XOR, SLL, SRL, SRA, SLT, SLTU }

    public final HwEnum<Op> op = input("op", HwEnum.of(Op.class));
    public final UInt a, b, y;

    public Alu(int width) {
        a = input("a", UInt.w(width));
        b = input("b", UInt.w(width));
        y = output("y", UInt.w(width));
        int shBits = 32 - Integer.numberOfLeadingZeros(width - 1);
        UInt shamt = b.trunc(shBits);
        y.set(Hw.select(op, o -> switch (o) {
            case ADD -> a.addWrap(b);
            case SUB -> a.subWrap(b);
            case AND -> a.and(b);
            case OR -> a.or(b);
            case XOR -> a.xor(b);
            case SLL -> a.dshl(shamt);
            case SRL -> a.dshr(shamt);
            case SRA -> a.asSInt().dsra(shamt).asUInt();
            case SLT -> a.asSInt().lt(b.asSInt()).zext(width);
            case SLTU -> a.lt(b).zext(width);
        }));
    }
}
