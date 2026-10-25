package org.eninehq.tessera.riscv;

import java.util.ArrayList;
import java.util.List;

/** Small kernels that show what an out-of-order window buys (and what it does not). */
public final class Benchmarks {
    private Benchmarks() {}

    private static int[] finish(List<Integer> p) {
        p.add(RandomProgram.EBREAK);
        return p.stream().mapToInt(Integer::intValue).toArray();
    }

    /** 20 times: a divide, 16 independent additions, then a use of the quotient. */
    public static int[] divideOverlap() {
        List<Integer> p = new ArrayList<>();
        p.add(RandomProgram.i(1000, 0, 0, 6, 0x13));
        p.add(RandomProgram.i(7, 0, 0, 7, 0x13));
        for (int k = 0; k < 20; k++) {
            p.add(RandomProgram.r(1, 7, 6, 4, 5, 0x33));                                   // div x5, x6, x7
            for (int a = 0; a < 16; a++) p.add(RandomProgram.i(1, 8 + a % 8, 0, 8 + a % 8, 0x13)); // addi x8+n, x8+n, 1
            p.add(RandomProgram.r(0, 5, 6, 0, 6, 0x33));                                   // add x6, x6, x5
        }
        return finish(p);
    }

    /** 20 times: a chain of 4 dependent multiplies next to 8 independent additions. */
    public static int[] multiplyOverlap() {
        List<Integer> p = new ArrayList<>();
        p.add(RandomProgram.i(3, 0, 0, 5, 0x13));
        for (int k = 0; k < 20; k++) {
            for (int m = 0; m < 4; m++) {
                p.add(RandomProgram.r(1, 5, 5, 0, 5, 0x33));                               // mul x5, x5, x5
                p.add(RandomProgram.i(1, 8 + m, 0, 8 + m, 0x13));
                p.add(RandomProgram.i(1, 12 + m, 0, 12 + m, 0x13));
            }
        }
        return finish(p);
    }

    /**
     * A long-running loop for simulator speed: {@code n} iterations of load, add, store,
     * decrement and branch (5 instructions), on a word at 0x2000.
     */
    public static int[] loop(int n) {
        List<Integer> p = new ArrayList<>();
        p.add(RandomProgram.u(0x2, 31, 0x37));                                   // lui x31, 0x2000
        int hi = (n + 0x800) >>> 12, lo = n - (hi << 12);
        p.add(RandomProgram.u(hi, 6, 0x37));                                     // lui x6, hi
        p.add(RandomProgram.i(lo, 6, 0, 6, 0x13));                               // addi x6, x6, lo  (x6 = n)
        p.add(RandomProgram.i(0, 31, 2, 7, 0x03));                               // loop: lw x7, 0(x31)
        p.add(RandomProgram.r(0, 6, 7, 0, 7, 0x33));                             // add x7, x7, x6
        p.add(RandomProgram.s(0, 7, 31, 2));                                     // sw x7, 0(x31)
        p.add(RandomProgram.i(-1, 6, 0, 6, 0x13));                               // addi x6, x6, -1
        p.add(RandomProgram.b(-16, 0, 6, 1));                                    // bne x6, x0, loop
        return finish(p);
    }

    /** A dependent chain of additions: no parallelism to find. */
    public static int[] dependentChain() {
        List<Integer> p = new ArrayList<>();
        for (int k = 0; k < 400; k++) p.add(RandomProgram.i(1, 5, 0, 5, 0x13));          // addi x5, x5, 1
        return finish(p);
    }
}
