package org.eninehq.tessera.riscv;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Random RV32I programs for differential testing: dense register reuse (to exercise
 * forwarding and load-use hazards), loads and stores into a data region, forward branches
 * and jumps, ending with EBREAK. Register x31 holds the data base address.
 */
public final class RandomProgram {
    private RandomProgram() {}

    public static final int DATA_BASE = 0x2000;

    public static int r(int f7, int rs2, int rs1, int f3, int rd, int op) {
        return f7 << 25 | rs2 << 20 | rs1 << 15 | f3 << 12 | rd << 7 | op;
    }

    public static int i(int imm, int rs1, int f3, int rd, int op) {
        return (imm & 0xFFF) << 20 | rs1 << 15 | f3 << 12 | rd << 7 | op;
    }

    public static int s(int imm, int rs2, int rs1, int f3) {
        return ((imm >> 5) & 0x7F) << 25 | rs2 << 20 | rs1 << 15 | f3 << 12 | (imm & 0x1F) << 7 | 0x23;
    }

    public static int b(int imm, int rs2, int rs1, int f3) {
        return ((imm >> 12) & 1) << 31 | ((imm >> 5) & 0x3F) << 25 | rs2 << 20 | rs1 << 15 | f3 << 12
                | ((imm >> 1) & 0xF) << 8 | ((imm >> 11) & 1) << 7 | 0x63;
    }

    public static int u(int imm20, int rd, int op) {
        return imm20 << 12 | rd << 7 | op;
    }

    public static int j(int imm, int rd) {
        return ((imm >> 20) & 1) << 31 | ((imm >> 1) & 0x3FF) << 21 | ((imm >> 11) & 1) << 20
                | ((imm >> 12) & 0xFF) << 12 | rd << 7 | 0x6F;
    }

    public static final int EBREAK = 0x00100073;

    public static int[] generate(long seed, int length) {
        return generate(seed, length, false);
    }

    /** With {@code m}: a third of the register-register operations are multiplies and divides. */
    public static int[] generate(long seed, int length, boolean m) {
        var rnd = new Random(seed);
        List<Integer> p = new ArrayList<>();
        p.add(u(DATA_BASE >>> 12, 31, 0x37));                       // lui x31, DATA_BASE
        for (int k = 1; k <= 8; k++) p.add(i(rnd.nextInt(4096) - 2048, 0, 0, k, 0x13));
        while (p.size() < length) {
            int rd = 1 + rnd.nextInt(8), rs1 = rnd.nextInt(9), rs2 = rnd.nextInt(9);
            if (p.size() >= 500) throw new IllegalArgumentException("programs must stay below 2 KiB (absolute jalr)");
            switch (rnd.nextInt(12)) {
                case 0, 1, 2 -> {
                    int f3 = rnd.nextInt(8);
                    int f7 = (f3 == 0 || f3 == 5) && rnd.nextBoolean() ? 0x20 : 0;
                    if (m && rnd.nextInt(3) == 0) f7 = 1;
                    p.add(r(f7, rs2, rs1, f3, rd, 0x33));
                }
                case 3, 4 -> {
                    int f3 = rnd.nextInt(8);
                    int imm = rnd.nextInt(4096) - 2048;
                    if (f3 == 1) imm = rnd.nextInt(32);
                    if (f3 == 5) imm = rnd.nextInt(32) | (rnd.nextBoolean() ? 0x400 : 0);
                    p.add(i(imm, rs1, f3, rd, 0x13));
                }
                case 5 -> p.add(u(rnd.nextInt(1 << 20), rd, rnd.nextBoolean() ? 0x37 : 0x17));
                case 6, 7 -> {
                    int[] f3s = {0, 1, 2, 4, 5};
                    int f3 = f3s[rnd.nextInt(f3s.length)];
                    int size = f3 == 2 ? 4 : (f3 == 1 || f3 == 5) ? 2 : 1;
                    p.add(i(rnd.nextInt(1024) / size * size, 31, f3, rd, 0x03));
                }
                case 8 -> {
                    int f3 = rnd.nextInt(3);
                    int size = 1 << f3;
                    p.add(s(rnd.nextInt(1024) / size * size, rs2, 31, f3));
                }
                case 9, 10 -> {
                    int[] f3s = {0, 1, 4, 5, 6, 7};
                    int skip = 1 + rnd.nextInt(3);
                    p.add(b(4 * (skip + 1), rs2, rs1, f3s[rnd.nextInt(f3s.length)]));
                }
                default -> {
                    if (rnd.nextBoolean()) p.add(j(8 + 4 * rnd.nextInt(2), rd));
                    else p.add(i(4 * (p.size() + 2), 0, 0, rd, 0x67)); // jalr rd, next+4(x0): absolute

                }
            }
        }
        for (int k = 0; k < 4; k++) p.add(i(0, 0, 0, 0, 0x13));        // landing pad for trailing skips
        p.add(EBREAK);
        return p.stream().mapToInt(Integer::intValue).toArray();
    }

    /**
     * Programs that stress dual issue: blocks of two (or three) instructions that a pair check must split or may
     * issue together: an instruction and one that reads its result, two that write the same register, an ALU
     * instruction followed by a branch, a JAL or a JALR (so the jump can be in lane 1), a load or a multiply with a
     * dependent instruction, and independent pairs, at every alignment. Machine mode is assumed: mtvec points at the
     * final EBREAK, and a few programs end with a misaligned JALR (a trap, possibly in lane 1) that goes there.
     */
    public static int[] pairs(long seed, int length) {
        var rnd = new Random(seed);
        List<Integer> p = new ArrayList<>();
        p.add(u(DATA_BASE >>> 12, 31, 0x37));
        for (int k = 1; k <= 8; k++) p.add(i(rnd.nextInt(4096) - 2048, 0, 0, k, 0x13));
        int mtvecAt = p.size();
        for (int k = 0; k < 3; k++) p.add(0);                    // lui, addi, csrw mtvec: patched below
        boolean trapped = false;
        while (p.size() < length && !trapped) {
            int a = 1 + rnd.nextInt(8), b = 1 + rnd.nextInt(8), c = rnd.nextInt(9);
            if (rnd.nextInt(3) == 0) p.add(i(rnd.nextInt(64), c, 0, 1 + rnd.nextInt(8), 0x13));   // shift the alignment
            switch (rnd.nextInt(9)) {
                case 0 -> {   // b reads a
                    p.add(r(0, c, b, 0, a, 0x33));
                    p.add(i(rnd.nextInt(64) - 32, a, 0, b, 0x13));
                }
                case 1 -> {   // both write a
                    p.add(i(rnd.nextInt(64), b, 4, a, 0x13));
                    p.add(r(0, c, b, 7, a, 0x33));
                }
                case 2 -> {   // an ALU instruction, then a forward branch on its result
                    int[] f3s = {0, 1, 4, 5, 6, 7};
                    p.add(r(0, c, b, 0, a, 0x33));
                    p.add(b(4 * (2 + rnd.nextInt(2)), rnd.nextBoolean() ? a : c, b, f3s[rnd.nextInt(f3s.length)]));
                    p.add(i(1, a, 0, a, 0x13));
                }
                case 3 -> {   // an ALU instruction, then a JAL over one instruction
                    p.add(i(rnd.nextInt(64), b, 0, a, 0x13));
                    p.add(j(8, rnd.nextBoolean() ? a : 0));
                    p.add(i(7, 0, 0, b, 0x13));
                }
                case 4 -> {   // an ALU instruction, then an absolute JALR to the next instruction
                    p.add(r(0x20, c, b, 0, a, 0x33));
                    p.add(i(4 * (p.size() + 1), 0, 0, rnd.nextBoolean() ? b : 0, 0x67));
                }
                case 5 -> {   // a load and a use of it
                    p.add(i(4 * rnd.nextInt(256), 31, 2, a, 0x03));
                    p.add(r(0, a, b, 0, b, 0x33));
                }
                case 6 -> {   // a multiply and a use of it, or a store beside an ALU instruction
                    if (rnd.nextBoolean()) {
                        p.add(r(1, c, b, rnd.nextInt(8), a, 0x33));
                        p.add(r(0, a, c, 4, b, 0x33));
                    } else {
                        p.add(s(4 * rnd.nextInt(256), b, 31, 2));
                        p.add(i(3, c, 6, a, 0x13));
                    }
                }
                case 7 -> {   // independent instructions
                    p.add(i(rnd.nextInt(2048), c, 0, a, 0x13));
                    p.add(u(rnd.nextInt(1 << 20), a == b ? (b % 8) + 1 : b, rnd.nextBoolean() ? 0x37 : 0x17));
                }
                default -> {
                    if (seed % 4 == 3 && rnd.nextInt(12) == 0) {
                        // a misaligned jump target: the trap goes to mtvec, the final EBREAK
                        // (a different destination, or the pair check splits them and the jump is always in lane 0)
                        p.add(i(rnd.nextInt(64), b, 0, a, 0x13));
                        p.add(i(4 * (p.size() + 1) + 2, 0, 0, rnd.nextBoolean() ? 0 : (a % 8) + 1, 0x67));
                        trapped = true;
                    } else {
                        p.add(i(rnd.nextInt(64), b, 0, a, 0x13));
                        p.add(i(rnd.nextInt(64), c, 0, b == a ? (a % 8) + 1 : b, 0x13));
                    }
                }
            }
        }
        for (int k = 0; k < 4; k++) p.add(i(0, 0, 0, 0, 0x13));
        p.add(EBREAK);
        int end = 4 * (p.size() - 1);
        int hi = (end + 0x800) >>> 12, lo = end - (hi << 12);
        p.set(mtvecAt, u(hi, 30, 0x37));
        p.set(mtvecAt + 1, i(lo, 30, 0, 30, 0x13));
        p.set(mtvecAt + 2, i(0x305, 30, 1, 0, 0x73));          // csrrw x0, mtvec, x30
        return p.stream().mapToInt(Integer::intValue).toArray();
    }
}
