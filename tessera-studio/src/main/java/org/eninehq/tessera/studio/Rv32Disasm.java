package org.eninehq.tessera.studio;

/** RV32IM and Zicsr instruction words as assembly, for the rows of the pipeline diagram. */
final class Rv32Disasm {
    private Rv32Disasm() {}

    private static final String[] BRANCH = {"beq", "bne", "?", "?", "blt", "bge", "bltu", "bgeu"};
    private static final String[] LOAD = {"lb", "lh", "lw", "?", "lbu", "lhu", "?", "?"};
    private static final String[] STORE = {"sb", "sh", "sw", "?", "?", "?", "?", "?"};
    private static final String[] OPIMM = {"addi", "slli", "slti", "sltiu", "xori", "sr?i", "ori", "andi"};
    private static final String[] OP = {"add", "sll", "slt", "sltu", "xor", "srl", "or", "and"};
    private static final String[] MULDIV = {"mul", "mulh", "mulhsu", "mulhu", "div", "divu", "rem", "remu"};
    private static final String[] CSR = {"?", "csrrw", "csrrs", "csrrc", "?", "csrrwi", "csrrsi", "csrrci"};

    static String of(long word) {
        int w = (int) word;
        int op = w & 0x7f, rd = (w >>> 7) & 31, f3 = (w >>> 12) & 7, rs1 = (w >>> 15) & 31, rs2 = (w >>> 20) & 31, f7 = w >>> 25;
        int immI = w >> 20;
        int immS = ((w >> 25) << 5) | ((w >>> 7) & 31);
        int immB = ((w >> 31) << 12) | (((w >>> 7) & 1) << 11) | (((w >>> 25) & 63) << 5) | (((w >>> 8) & 15) << 1);
        int immJ = ((w >> 31) << 20) | (((w >>> 12) & 255) << 12) | (((w >>> 20) & 1) << 11) | (((w >>> 21) & 1023) << 1);
        String d = "x" + rd, a = "x" + rs1, b = "x" + rs2;
        return switch (op) {
            case 0x37 -> "lui " + d + ", 0x" + Integer.toHexString(w >>> 12);
            case 0x17 -> "auipc " + d + ", 0x" + Integer.toHexString(w >>> 12);
            case 0x6f -> "jal " + d + ", " + immJ;
            case 0x67 -> "jalr " + d + ", " + immI + "(" + a + ")";
            case 0x63 -> BRANCH[f3] + " " + a + ", " + b + ", " + immB;
            case 0x03 -> LOAD[f3] + " " + d + ", " + immI + "(" + a + ")";
            case 0x23 -> STORE[f3] + " " + b + ", " + immS + "(" + a + ")";
            case 0x13 -> f3 == 1 ? "slli " + d + ", " + a + ", " + rs2
                    : f3 == 5 ? ((f7 & 0x20) != 0 ? "srai " : "srli ") + d + ", " + a + ", " + rs2
                    : OPIMM[f3] + " " + d + ", " + a + ", " + immI;
            case 0x33 -> (f7 == 1 ? MULDIV[f3] : f7 == 0x20 && f3 == 0 ? "sub" : f7 == 0x20 && f3 == 5 ? "sra" : OP[f3])
                    + " " + d + ", " + a + ", " + b;
            case 0x0f -> "fence";
            case 0x73 -> f3 == 0
                    ? switch (immI & 0xfff) {
                        case 0 -> "ecall";
                        case 1 -> "ebreak";
                        case 0x302 -> "mret";
                        case 0x105 -> "wfi";
                        default -> "system";
                    }
                    : CSR[f3] + " " + d + ", 0x" + Integer.toHexString(immI & 0xfff) + ", " + (f3 >= 5 ? String.valueOf(rs1) : a);
            default -> w == 0 ? "(zero)" : ".word 0x" + Integer.toHexString(w);
        };
    }
}
