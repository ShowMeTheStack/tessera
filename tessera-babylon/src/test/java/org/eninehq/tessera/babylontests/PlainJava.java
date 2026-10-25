package org.eninehq.tessera.babylontests;

import jdk.incubator.code.Reflect;
import org.eninehq.tessera.examples.Alu;

/** Hardware written as plain Java: each method also runs as ordinary Java (its own golden model). */
public final class PlainJava {
    private PlainJava() {}

    @Reflect
    public static int alu(Alu.Op op, int a, int b) {
        return switch (op) {
            case ADD -> a + b;
            case SUB -> a - b;
            case AND -> a & b;
            case OR -> a | b;
            case XOR -> a ^ b;
            case SLL -> a << b;
            case SRL -> a >>> b;
            case SRA -> a >> b;
            case SLT -> a < b ? 1 : 0;
            case SLTU -> Integer.compareUnsigned(a, b) < 0 ? 1 : 0;
        };
    }

    /** Loops with constant bounds unroll; locals become named wires. */
    @Reflect
    public static int popcount(int x) {
        int n = 0;
        for (int i = 0; i < 32; i++) {
            if (((x >>> i) & 1) != 0) n++;
        }
        return n;
    }

    /** Early returns and a width parameter known at elaboration time. */
    @Reflect
    public static int saturate(int x, int bits) {
        int max = (1 << bits) - 1;
        if (x < 0) return 0;
        if (x > max) return max;
        return x;
    }

    /** Calls are inlined; the constant argument is folded. */
    @Reflect
    public static int clampedSum(int a, int b) {
        return saturate(a + b, 8);
    }

    /** A while loop with break and an elaboration-time accumulator. */
    @Reflect
    public static int firstSetFromTop(int x) {
        int result = -1;
        int i = 31;
        while (i >= 0) {
            if (((x >>> i) & 1) != 0) {
                result = i;
                break;
            }
            i--;
        }
        return result;
    }

    @Reflect
    public static long mulHigh(int a, int b) {
        long p = (long) a * (long) b;
        return p >> 32;
    }

    @Reflect
    public static int divide(int a, int b) {
        return a / b;
    }

    @Reflect
    public static int spin(int x) {
        int i = 0;
        while (i < x) i++;
        return i;
    }
}
