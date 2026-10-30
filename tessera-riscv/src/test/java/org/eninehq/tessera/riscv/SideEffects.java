package org.eninehq.tessera.riscv;

import static org.junit.jupiter.api.Assertions.fail;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.eninehq.tessera.sim.Simulator;

/**
 * Co-simulation of what a core does besides what it reports on retirement: every write that reaches the data
 * memory, and the CSRs that instructions and traps write. The core's writes are observed at the memory itself
 * ({@link Simulator#addMemoryWriteListener}) and its CSRs by register name, so nothing is added to the hardware.
 * A core may write in another cycle than it retires, so ordered sequences are compared, not cycles: the memory
 * writes as (word index, word), matched one by one as both sides produce them, and the distinct successive values
 * of the CSR vector ({@link Rv32iIss#csrChanges}). At the end the whole data memory is compared with the ISS's.
 */
final class SideEffects {
    static final String[] CSRS = {"mstatus", "mie", "mtvec", "mscratch", "mepc", "mcause", "mtval"};

    private final Simulator sim;
    private final Rv32iIss iss;
    // writes one side has made and the other not yet: {index, word}
    private final ArrayDeque<long[]> core = new ArrayDeque<>(), ref = new ArrayDeque<>();
    private long matched;
    private final List<int[]> csrChanges = new ArrayList<>();
    private final boolean csrs;

    /** Start observing; call after the memories are loaded and before reset ends. */
    SideEffects(Simulator sim, RvCore c, Rv32iIss iss) {
        this.sim = sim;
        this.iss = iss;
        sim.addMemoryWriteListener(c.dmem, (cycle, index, value) -> arrive(core, ref, index, value & 0xFFFF_FFFFL));
        iss.stores = (index, word) -> arrive(ref, core, index, word & 0xFFFF_FFFFL);
        csrs = iss.zicsr && sim.netlist().signalsByPath().containsKey(CSRS[0]);
        if (csrs) {
            iss.csrChanges = new ArrayList<>();
            iss.csrChanges.add(Rv32iIss.csrVector(iss.csrs));
        }
    }

    private void arrive(ArrayDeque<long[]> mine, ArrayDeque<long[]> other, long index, long word) {
        if (other.isEmpty()) {
            mine.add(new long[]{index, word});
            return;
        }
        long[] o = other.poll();
        boolean coreFirst = mine == core;
        long[] c = coreFirst ? new long[]{index, word} : o, r = coreFirst ? o : new long[]{index, word};
        if (c[0] != r[0] || c[1] != r[1]) fail("memory write #" + matched + " differs: core " + show(c) + ", iss " + show(r));
        matched++;
    }

    /** Sample the CSRs; call once per cycle, before the clock edge. */
    void sample() {
        if (!csrs) return;
        int[] v = new int[CSRS.length];
        for (int i = 0; i < v.length; i++) v[i] = (int) sim.peek(CSRS[i]);
        if (csrChanges.isEmpty() || !Arrays.equals(csrChanges.getLast(), v)) csrChanges.add(v);
    }

    /** After the run: the same memory writes in the same order, the same CSR history, the same data memory. */
    void check(long[] dmem) {
        sample();
        if (!core.isEmpty() || !ref.isEmpty())
            fail("after " + matched + " matching memory writes: " + (core.isEmpty() ? "the ISS stored " + show(ref.peek()) + " and "
                    + (ref.size() - 1) + " more that the core did not write" : "the core wrote " + show(core.peek()) + " and "
                    + (core.size() - 1) + " more that the ISS did not store"));
        if (csrs) {
            var want = iss.csrChanges;
            for (int i = 0; i < Math.max(csrChanges.size(), want.size()); i++) {
                int[] h = i < csrChanges.size() ? csrChanges.get(i) : null, s = i < want.size() ? want.get(i) : null;
                if (h == null || s == null || !Arrays.equals(h, s))
                    fail("CSR change #" + i + " differs (" + String.join(" ", CSRS) + "): core " + hex(h) + ", iss " + hex(s));
            }
        }
        System.out.println("SIDE-EFFECTS " + matched + " memory writes, " + (csrs ? csrChanges.size() + " CSR states" : "no CSRs"));
        int n = Math.min(dmem.length, iss.mem.length);
        for (int i = 0; i < n; i++)
            if ((int) dmem[i] != iss.mem[i]) fail("data memory word " + i + " differs at the end: core 0x"
                    + Integer.toHexString((int) dmem[i]) + ", iss 0x" + Integer.toHexString(iss.mem[i]));
    }

    private static String show(long[] w) {
        return w == null ? "none" : "[" + w[0] + "] = 0x" + Long.toHexString(w[1]);
    }

    private static String hex(int[] v) {
        if (v == null) return "none";
        var b = new StringBuilder();
        for (int x : v) b.append(b.isEmpty() ? "" : " ").append(Integer.toHexString(x));
        return b.toString();
    }
}
