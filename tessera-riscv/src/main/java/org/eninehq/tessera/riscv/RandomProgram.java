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

    /** A load of {@code size} bytes ({@code f3}: LB, LH, LW, LBU, LHU) at an aligned offset in the first {@code words} words. */
    private static int load(Random rnd, int words, int rd) {
        int[] f3s = {0, 1, 2, 4, 5};
        int f3 = f3s[rnd.nextInt(f3s.length)];
        int size = f3 == 2 ? 4 : (f3 == 1 || f3 == 5) ? 2 : 1;
        return i(4 * rnd.nextInt(words) + rnd.nextInt(4) / size * size, 31, f3, rd, 0x03);
    }

    private static int store(Random rnd, int words, int rs2) {
        int f3 = rnd.nextInt(3), size = 1 << f3;
        return s(4 * rnd.nextInt(words) + rnd.nextInt(4) / size * size, rs2, 31, f3);
    }

    /**
     * Programs for two lanes that both load and store: blocks of two or three memory instructions over a
     * window of {@code 8} words (so pairs often hit one word): a store then a load or store of the same word (bytes,
     * halves, words), of another word, a load then a store, loads and their uses, a store followed a stage later by a
     * load in the same lane, and ALU instructions before memory ones (so loads and stores land in lane 1), at every
     * alignment. Machine mode: mtvec points at the final EBREAK, and some programs end with a misaligned load or
     * store (a trap, possibly from lane 1).
     */
    public static int[] memPairs(long seed, int length) {
        var rnd = new Random(seed);
        final int w = 8;
        List<Integer> p = new ArrayList<>();
        p.add(u(DATA_BASE >>> 12, 31, 0x37));
        for (int k = 1; k <= 8; k++) p.add(i(rnd.nextInt(4096) - 2048, 0, 0, k, 0x13));
        int mtvecAt = p.size();
        for (int k = 0; k < 3; k++) p.add(0);
        boolean trapped = false;
        while (p.size() < length && !trapped) {
            int a = 1 + rnd.nextInt(8), b = 1 + rnd.nextInt(8);
            if (rnd.nextInt(3) == 0) p.add(i(rnd.nextInt(64), b, 0, 1 + rnd.nextInt(8), 0x13));   // shift the alignment
            int word = rnd.nextInt(w);
            switch (rnd.nextInt(10)) {
                case 0 -> {   // a store, then a load of the same word; half of them through a copy of the base that the
                    // load overwrites (lw a, off(a): a load that must not write its register when it is replayed)
                    int f3 = rnd.nextInt(3), size = 1 << f3;
                    boolean viaCopy = rnd.nextBoolean();
                    if (viaCopy) p.add(i(0, 31, 0, a, 0x13));
                    p.add(s(4 * word + rnd.nextInt(4) / size * size, b, 31, f3));
                    p.add(i(4 * word, viaCopy ? a : 31, 2, a, 0x03));
                }
                case 1 -> {   // two sub-word stores into one word (different bytes or the same), then a load of it
                    p.add(s(4 * word + rnd.nextInt(4), a, 31, 0));
                    p.add(s(4 * word + 2 * rnd.nextInt(2), b, 31, rnd.nextInt(2)));
                    if (rnd.nextBoolean()) p.add(i(4 * word, 31, 2, 1 + rnd.nextInt(8), 0x03));
                }
                case 2 -> {   // two stores
                    p.add(store(rnd, w, a));
                    p.add(store(rnd, w, b));
                }
                case 3 -> {   // two loads
                    p.add(load(rnd, w, a));
                    p.add(load(rnd, w, b == a ? (a % 8) + 1 : b));
                }
                case 4 -> {   // a load, then a store (of the same word or not)
                    p.add(load(rnd, w, a));
                    p.add(rnd.nextBoolean() ? s(4 * word, b, 31, 2) : store(rnd, w, b));
                }
                case 5 -> {   // a store, an ALU instruction, then a load: the load reads while the store writes
                    p.add(store(rnd, w, b));
                    p.add(i(rnd.nextInt(64), a, 0, a, 0x13));
                    p.add(load(rnd, w, 1 + rnd.nextInt(8)));
                }
                case 6 -> {   // a load and a use of it; a store of a value just computed
                    p.add(load(rnd, w, a));
                    p.add(r(0, a, b, 0, b, 0x33));
                    p.add(s(4 * word, b, 31, 2));
                }
                case 7 -> {   // an ALU instruction, then a memory one (lane 1)
                    p.add(i(rnd.nextInt(2048), b, 0, a, 0x13));
                    p.add(rnd.nextBoolean() ? load(rnd, w, b == a ? (a % 8) + 1 : b) : store(rnd, w, b));
                }
                case 8 -> {   // a multiply beside memory traffic (M stays in lane 0)
                    p.add(r(1, b, a, rnd.nextInt(4), a, 0x33));
                    p.add(rnd.nextBoolean() ? load(rnd, w, b == a ? (a % 8) + 1 : b) : store(rnd, w, b));
                }
                default -> {
                    if (seed % 4 == 3 && rnd.nextInt(12) == 0) {
                        // a misaligned load or store: the trap goes to mtvec, the final EBREAK
                        p.add(i(rnd.nextInt(64), b, 0, a, 0x13));
                        p.add(rnd.nextBoolean() ? i(4 * word + 1, 31, 2, (a % 8) + 1, 0x03) : s(4 * word + 2, b, 31, 2));
                        trapped = true;
                    } else {
                        p.add(store(rnd, w, a));
                        p.add(i(rnd.nextInt(64), b, 0, (b % 8) + 1, 0x13));
                    }
                }
            }
        }
        return finishMachineMode(p, mtvecAt);
    }

    /**
     * Programs dense in control transfers, for execute-time branch recovery (C11): forward branches on data (predicted
     * not taken), counted loops nested two deep (the backward branch predicted taken, mispredicted at the exit),
     * forward JALRs relative to an AUIPC (predicted to fall through), some of them waiting for a divide, and branches on
     * multiply, divide and load results, so branches resolve out of order and multiplies and divides sit on wrong paths.
     * x9 and x10 count loops, x11 holds 1, x13 holds JALR bases, x31 the data base; other results go to x1..x8. With
     * {@code m}, multiplies and divides appear among the operations. Ends with EBREAK.
     */
    public static int[] branches(long seed, int length, boolean m) {
        var rnd = new Random(seed);
        List<Integer> p = new ArrayList<>();
        p.add(u(DATA_BASE >>> 12, 31, 0x37));
        for (int k = 1; k <= 8; k++) p.add(i(rnd.nextInt(4096) - 2048, 0, 0, k, 0x13));
        p.add(i(1, 0, 0, 11, 0x13));                                // x11 = 1
        while (p.size() < length) p.addAll(block(rnd, m, 2));
        if (p.size() * 4 >= DATA_BASE) throw new IllegalArgumentException("program overlaps the data region");
        for (int k = 0; k < 4; k++) p.add(i(0, 0, 0, 0, 0x13));
        p.add(EBREAK);
        return p.stream().mapToInt(Integer::intValue).toArray();
    }

    /**
     * Programs dense in loads and stores to a few words (for the load/store queue, C11): bytes, halves and words at
     * every offset of 16 words, so loads overlap older stores fully, partly or not at all; store and load bases that
     * wait for a divide or multiply (x12 = x31 / 1 or x31 * 1); stores whose data waits for a divide; and the branch-dense
     * blocks of {@link #branches} in between, so loads and stores sit on wrong paths. Ends with EBREAK.
     */
    public static int[] memory(long seed, int length, boolean m) {
        var rnd = new Random(seed);
        List<Integer> p = new ArrayList<>();
        p.add(u(DATA_BASE >>> 12, 31, 0x37));
        for (int k = 1; k <= 8; k++) p.add(i(rnd.nextInt(4096) - 2048, 0, 0, k, 0x13));
        p.add(i(1, 0, 0, 11, 0x13));                                // x11 = 1
        p.add(r(0, 0, 31, 0, 12, 0x33));                            // x12 = x31
        while (p.size() < length) {
            int base = switch (rnd.nextInt(4)) { case 0 -> 12; default -> 31; };
            int rd = 1 + rnd.nextInt(8);
            switch (rnd.nextInt(10)) {
                case 0, 1, 2 -> {   // a store of any size at any aligned offset of 16 words
                    int f3 = rnd.nextInt(3), size = 1 << f3;
                    p.add(s(rnd.nextInt(64) / size * size, rnd.nextInt(9), base, f3));
                }
                case 3, 4, 5 -> {   // a load of any size and sign
                    int[] f3s = {0, 1, 2, 4, 5};
                    int f3 = f3s[rnd.nextInt(f3s.length)];
                    int size = f3 == 2 ? 4 : (f3 == 1 || f3 == 5) ? 2 : 1;
                    p.add(i(rnd.nextInt(64) / size * size, base, f3, rd, 0x03));
                }
                case 6 -> {         // the base x12 again, behind a divide (or a multiply): younger stores' addresses wait
                    p.add(r(1, 11, 31, rnd.nextBoolean() ? 4 : 0, 12, 0x33));
                }
                case 7 -> {         // a store whose data waits for a divide
                    p.add(r(1, rnd.nextInt(9), rnd.nextInt(9), 4 + rnd.nextInt(4), rd, 0x33));
                    p.add(s(4 * rnd.nextInt(16), rd, base, 2));
                }
                default -> p.addAll(block(rnd, m, 1));
            }
        }
        if (p.size() * 4 >= DATA_BASE) throw new IllegalArgumentException("program overlaps the data region");
        for (int k = 0; k < 4; k++) p.add(i(0, 0, 0, 0, 0x13));
        p.add(EBREAK);
        return p.stream().mapToInt(Integer::intValue).toArray();
    }

    /**
     * A directed program for checkpoint release and reuse after a recovery (C11, at least 2 checkpoints, a window of
     * about 8). First, 8 times in a loop: branch K (waiting for a divide) is mispredicted while branch J on its wrong
     * path holds a second checkpoint, which the recovery must free (if it does not, the core keeps working with fewer
     * free checkpoints, more slowly: such a leak is visible in cycle counts only). Then once: on the right path of such a K, I waits for a second divide while K' (in K's freed
     * slot) is mispredicted, J' resolves, and J'' takes a checkpoint and is mispredicted with I still in flight.
     */
    public static int[] checkpointReuse() {
        int[] loop = {
                i(8, 0, 0, 9, 0x13),            //    addi x9, x0, 8
                i(100, 0, 0, 1, 0x13),          //    addi x1, x0, 100
                i(7, 0, 0, 2, 0x13),            //    addi x2, x0, 7
                r(1, 2, 1, 4, 5, 0x33),         // L: div  x5, x1, x2
                b(20, 5, 5, 0),                 //    beq  x5, x5, M       K: taken, predicted not taken
                b(8, 0, 5, 1),                  //    bne  x5, x0, +8      J: on K's wrong path
                i(0, 0, 0, 0, 0x13),
                i(0, 0, 0, 0, 0x13),
                i(0, 0, 0, 0, 0x13),
                i(-1, 9, 0, 9, 0x13),           // M: addi x9, x9, -1
                b(-28, 0, 9, 1)};               //    bne  x9, x0, L       (backward: predicted taken)
        int[] p = {
                i(100, 0, 0, 1, 0x13),          //  0 addi x1, x0, 100
                i(7, 0, 0, 2, 0x13),            //  1 addi x2, x0, 7
                r(1, 2, 1, 4, 5, 0x33),         //  2 div  x5, x1, x2
                b(20, 5, 5, 0),                 //  3 beq  x5, x5, 8        K: taken, predicted not taken
                b(8, 0, 5, 1),                  //  4 bne  x5, x0, 6        J: on K's wrong path
                i(0, 0, 0, 0, 0x13),            //  5
                i(0, 0, 0, 0, 0x13),            //  6
                i(0, 0, 0, 0, 0x13),            //  7
                r(1, 2, 1, 4, 8, 0x33),         //  8 div  x8, x1, x2
                r(0, 8, 8, 0, 7, 0x33),         //  9 add  x7, x8, x8       I: waits for the divide
                b(8, 0, 0, 0),                  // 10 beq  x0, x0, 12       K': mispredicted
                i(1, 0, 0, 9, 0x13),            // 11 (wrong path)
                b(8, 0, 0, 1),                  // 12 bne  x0, x0, 14       J': right (not taken)
                b(8, 0, 0, 0),                  // 13 beq  x0, x0, 15       J'': mispredicted
                i(1, 0, 0, 10, 0x13),           // 14 (wrong path)
                i(1, 7, 0, 11, 0x13),           // 15 addi x11, x7, 1
                i(0, 0, 0, 0, 0x13), i(0, 0, 0, 0, 0x13), i(0, 0, 0, 0, 0x13), i(0, 0, 0, 0, 0x13),
                EBREAK};
        int[] out = java.util.Arrays.copyOf(loop, loop.length + p.length);
        System.arraycopy(p, 0, out, loop.length, p.length);
        return out;
    }

    private static List<Integer> block(Random rnd, boolean m, int depth) {
        List<Integer> out = new ArrayList<>();
        int[] f3s = {0, 1, 4, 5, 6, 7};
        switch (rnd.nextInt(10)) {
            case 0, 1, 2 -> {
                for (int n = 1 + rnd.nextInt(3); n > 0; n--) out.add(simple(rnd, m));
            }
            case 3, 4 -> {   // a forward branch over a few operations (or a nested block)
                List<Integer> body = new ArrayList<>();
                for (int n = 1 + rnd.nextInt(3); n > 0; n--) body.add(simple(rnd, m));
                if (depth > 0 && rnd.nextInt(3) == 0) body.addAll(block(rnd, m, depth - 1));
                out.add(b(4 * (body.size() + 1), rnd.nextInt(9), rnd.nextInt(9), f3s[rnd.nextInt(f3s.length)]));
                out.addAll(body);
            }
            case 5, 6 -> {   // a counted loop (x9 outside, x10 inside)
                if (depth == 0) {
                    out.add(simple(rnd, m));
                    break;
                }
                int cnt = depth == 2 ? 9 : 10;
                List<Integer> body = new ArrayList<>();
                for (int n = 1 + rnd.nextInt(3); n > 0; n--) body.addAll(block(rnd, m, depth - 1));
                out.add(i(1 + rnd.nextInt(4), 0, 0, cnt, 0x13));
                out.addAll(body);
                out.add(i(-1, cnt, 0, cnt, 0x13));
                out.add(b(-4 * (body.size() + 1), 0, cnt, 1));      // bne cnt, x0, body
            }
            case 7, 8 -> {   // a forward JALR from an AUIPC, its base possibly behind a divide (x13 / 1)
                boolean slow = rnd.nextBoolean();
                int skip = rnd.nextInt(3);
                out.add(u(0, 13, 0x17));                            // auipc x13, 0
                if (slow) out.add(r(1, 11, 13, 4 + rnd.nextInt(2), 13, 0x33));   // div/divu x13, x13, x11
                int rd = rnd.nextInt(3) == 0 ? 0 : 1 + rnd.nextInt(8);
                out.add(i(4 * (out.size() + 1 + skip), 13, 0, rd, 0x67));
                for (int n = 0; n < skip; n++) out.add(simple(rnd, m));
            }
            default -> {     // a branch on a multiply, divide or load result
                int rd = 1 + rnd.nextInt(8);
                if (rnd.nextBoolean()) out.add(r(1, rnd.nextInt(9), rnd.nextInt(9), rnd.nextInt(8), rd, 0x33));
                else out.add(i(4 * rnd.nextInt(256), 31, 2, rd, 0x03));
                int skip = 1 + rnd.nextInt(3);
                out.add(b(4 * (skip + 1), rnd.nextInt(9), rd, f3s[rnd.nextInt(f3s.length)]));
                for (int n = 0; n < skip; n++) out.add(simple(rnd, m));
            }
        }
        return out;
    }

    /** One operation without control transfer, writing x1..x8: ALU, upper immediate, load, store, or (with m) M. */
    private static int simple(Random rnd, boolean m) {
        int rd = 1 + rnd.nextInt(8), rs1 = rnd.nextInt(9), rs2 = rnd.nextInt(9);
        switch (rnd.nextInt(9)) {
            case 0, 1 -> {
                int f3 = rnd.nextInt(8);
                int f7 = (f3 == 0 || f3 == 5) && rnd.nextBoolean() ? 0x20 : 0;
                if (m && rnd.nextInt(2) == 0) f7 = 1;
                return r(f7, rs2, rs1, f3, rd, 0x33);
            }
            case 2, 3 -> {
                int f3 = rnd.nextInt(8);
                int imm = rnd.nextInt(4096) - 2048;
                if (f3 == 1) imm = rnd.nextInt(32);
                if (f3 == 5) imm = rnd.nextInt(32) | (rnd.nextBoolean() ? 0x400 : 0);
                return i(imm, rs1, f3, rd, 0x13);
            }
            case 4 -> {
                return u(rnd.nextInt(1 << 20), rd, rnd.nextBoolean() ? 0x37 : 0x17);
            }
            case 5, 6 -> {
                int[] f3s = {0, 1, 2, 4, 5};
                int f3 = f3s[rnd.nextInt(f3s.length)];
                int size = f3 == 2 ? 4 : (f3 == 1 || f3 == 5) ? 2 : 1;
                return i(rnd.nextInt(1024) / size * size, 31, f3, rd, 0x03);
            }
            default -> {
                int f3 = rnd.nextInt(3);
                int size = 1 << f3;
                return s(rnd.nextInt(1024) / size * size, rs2, 31, f3);
            }
        }
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
        return finishMachineMode(p, mtvecAt);
    }

    /** The landing pad and final EBREAK, and mtvec (patched at {@code mtvecAt}) pointing at that EBREAK. */
    private static int[] finishMachineMode(List<Integer> p, int mtvecAt) {
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
