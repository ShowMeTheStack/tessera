package org.eninehq.tessera.riscv;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Random;
import org.junit.jupiter.api.Test;

/** The M extension in the shared model (16x16 partial products, restoring division) against Java arithmetic. */
class MulDivTest {
    static final int[] EDGES = {0, 1, -1, 2, -2, 7, Integer.MAX_VALUE, Integer.MIN_VALUE, 0x10000, 0xFFFF, 0x8000_0001, 3};

    static int run(int f3, int a, int b) {
        var d = SwDom.INSTANCE;
        int inst = (1 << 25) | (2 << 20) | (1 << 15) | (f3 << 12) | (3 << 7) | 0x33;
        var dec = Rv32i.decode(d, inst);
        return Rv32i.mulDivFinish(d, Rv32i.mulDivStart(d, dec, a, b));
    }

    static int expected(int f3, int a, int b) {
        long ua = Integer.toUnsignedLong(a), ub = Integer.toUnsignedLong(b);
        return switch (f3) {
            case 0 -> a * b;
            case 1 -> (int) (((long) a * b) >> 32);
            case 2 -> (int) (new java.math.BigInteger(Long.toString(a)).multiply(new java.math.BigInteger(Long.toString(ub))).shiftRight(32).longValue());
            case 3 -> (int) ((ua * ub) >>> 32);
            case 4 -> b == 0 ? -1 : (a == Integer.MIN_VALUE && b == -1 ? a : a / b);
            case 5 -> b == 0 ? -1 : Integer.divideUnsigned(a, b);
            case 6 -> b == 0 ? a : (a == Integer.MIN_VALUE && b == -1 ? 0 : a % b);
            default -> b == 0 ? a : Integer.remainderUnsigned(a, b);
        };
    }

    /** The two halves a schedule can finish in different stages: multiplies and divides. */
    static int runSplit(int f3, int a, int b) {
        var d = SwDom.INSTANCE;
        int inst = (1 << 25) | (2 << 20) | (1 << 15) | (f3 << 12) | (3 << 7) | 0x33;
        var h = Rv32i.mulDivStart(d, Rv32i.decode(d, inst), a, b);
        return f3 >= 4 ? Rv32i.divFinish(d, h, 16) : Rv32i.mulFinish(d, h);
    }

    @Test
    void splitHalvesMatchJavaArithmetic() {
        var rnd = new Random(9);
        for (int f3 = 0; f3 < 8; f3++) {
            for (int a : EDGES) for (int b : EDGES) assertEquals(expected(f3, a, b), runSplit(f3, a, b), "f3=" + f3 + " a=" + a + " b=" + b);
            for (int i = 0; i < 1_000_000; i++) {
                int a = rnd.nextInt() >> rnd.nextInt(32), b = rnd.nextInt() >> rnd.nextInt(32);
                assertEquals(expected(f3, a, b), runSplit(f3, a, b), "f3=" + f3 + " a=" + a + " b=" + b);
            }
        }
    }

    @Test
    void allEightInstructions() {
        var rnd = new Random(8);
        for (int f3 = 0; f3 < 8; f3++) {
            for (int a : EDGES) for (int b : EDGES) assertEquals(expected(f3, a, b), run(f3, a, b), "f3=" + f3 + " a=" + a + " b=" + b);
            for (int i = 0; i < 20_000; i++) {
                int a = rnd.nextInt() >> rnd.nextInt(32), b = rnd.nextInt() >> rnd.nextInt(32);
                assertEquals(expected(f3, a, b), run(f3, a, b), "f3=" + f3 + " a=" + a + " b=" + b);
            }
        }
    }
}
