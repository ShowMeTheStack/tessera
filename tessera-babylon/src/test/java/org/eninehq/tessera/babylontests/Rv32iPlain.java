package org.eninehq.tessera.babylontests;

import jdk.incubator.code.Reflect;

/**
 * RV32I decode and execute as plain Java (no Dom abstraction; M instructions are decoded but
 * their result comes from the separate multiply/divide unit): the same code runs as an
 * instruction-set simulator step and, through Babylon, becomes the core's datapath.
 */
public final class Rv32iPlain {
    private Rv32iPlain() {}

    public record Out(int result, boolean redirect, int target, int memAddr, boolean trap, boolean writesRd,
                      boolean load, boolean store) {}

    @Reflect
    public static Out exec(int inst, int pc, int a, int b) {
        int opcode = inst & 0x7F;
        int rd = (inst >>> 7) & 31;
        int f3 = (inst >>> 12) & 7;
        boolean alt = ((inst >>> 30) & 1) != 0;
        int immI = inst >> 20;
        int immS = ((inst >> 25) << 5) | ((inst >>> 7) & 31);
        int immB = ((inst >> 31) << 12) | (((inst >>> 7) & 1) << 11) | (((inst >>> 25) & 63) << 5) | (((inst >>> 8) & 15) << 1);
        int immU = inst & 0xFFFFF000;
        int immJ = ((inst >> 31) << 20) | (((inst >>> 12) & 255) << 12) | (((inst >>> 20) & 1) << 11) | (((inst >>> 21) & 1023) << 1);

        boolean lui = opcode == 0x37, auipc = opcode == 0x17, jal = opcode == 0x6F;
        boolean jalr = opcode == 0x67 && f3 == 0;
        boolean branch = opcode == 0x63 && f3 != 2 && f3 != 3;
        boolean load = opcode == 0x03 && f3 != 3 && f3 != 6 && f3 != 7;
        boolean store = opcode == 0x23 && f3 < 3;
        int funct7 = inst >>> 25;
        boolean mulDiv = opcode == 0x33 && funct7 == 1;
        boolean opImm = opcode == 0x13, fence = opcode == 0x0F;
        boolean op = opcode == 0x33 && (funct7 == 0 || (funct7 == 0x20 && (f3 == 0 || f3 == 5)));
        boolean halt = opcode == 0x73 && f3 == 0;
        boolean known = lui || auipc || jal || jalr || branch || load || store || opImm || op || fence || halt || mulDiv;

        int imm = lui || auipc ? immU : jal ? immJ : branch ? immB : store ? immS : immI;
        int opB = op ? b : imm;
        int sh = opB & 31;
        int alu;
        switch (f3) {
            case 0 -> alu = op && alt ? a - opB : a + opB;
            case 1 -> alu = a << sh;
            case 2 -> alu = a < opB ? 1 : 0;
            case 3 -> alu = Integer.compareUnsigned(a, opB) < 0 ? 1 : 0;
            case 4 -> alu = a ^ opB;
            case 5 -> alu = alt ? a >> sh : a >>> sh;
            case 6 -> alu = a | opB;
            default -> alu = a & opB;
        }
        int pc4 = pc + 4;
        int result = lui ? imm : auipc ? pc + imm : (jal || jalr) ? pc4 : alu;
        boolean cond;
        switch (f3) {
            case 0 -> cond = a == b;
            case 1 -> cond = a != b;
            case 4 -> cond = a < b;
            case 5 -> cond = a >= b;
            case 6 -> cond = Integer.compareUnsigned(a, b) < 0;
            default -> cond = Integer.compareUnsigned(a, b) >= 0;
        }
        boolean redirect = jal || jalr || (branch && cond);
        int target = jalr ? (a + imm) & ~1 : pc + imm;
        int memAddr = a + imm;
        int size = f3 & 3;
        boolean misMem = (load || store) && ((size == 1 && (memAddr & 1) != 0) || (size == 2 && (memAddr & 3) != 0));
        boolean misTarget = redirect && (target & 2) != 0;
        boolean trap = halt || !known || misMem || misTarget;
        boolean writes = (lui || auipc || jal || jalr || load || opImm || op || mulDiv) && rd != 0 && !trap;
        return new Out(result, redirect, target, memAddr, trap, writes, load, store);
    }
}
