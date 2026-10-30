package org.eninehq.tessera.riscv;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.eninehq.tessera.sim.Netlist;

/**
 * Is per-cycle multi-threading of the compiled simulator worth building? Two
 * measurements, both run through {@link SimBench}:
 * <ul>
 * <li>{@code cones}: partition the next-state logic (each register's next value and reset, each write
 *     port's operands) into P groups and count the instructions each group needs (the union of the
 *     fan-in cones of its sinks, shared logic duplicated). A thread per group needs one barrier per cycle;
 *     the best possible speed-up is total / max(group) (machine-independent).
 * <li>{@code barrier}: the cost of one cycle barrier between P spinning threads (machine-dependent).
 * </ul>
 */
final class ParallelScope {
    private ParallelScope() {}

    static void cones(String id, Netlist n) {
        var insns = n.insns();
        int slots = n.slotCount();
        int[] def = new int[slots];
        Arrays.fill(def, -1);
        for (int i = 0; i < insns.size(); i++) def[insns.get(i).dst()] = i;
        // sinks: per register {next, reset}, per write port {addr, data, en}
        var sinks = new ArrayList<int[]>();
        for (var r : n.regs()) sinks.add(r.hasReset() ? new int[]{r.next(), r.resetSlot()} : new int[]{r.next()});
        for (var w : n.memWrites()) sinks.add(new int[]{w.addr(), w.data(), w.en()});
        var cones = new ArrayList<BitSet>();
        BitSet all = new BitSet();
        for (int[] s : sinks) {
            var b = new BitSet();
            var stack = new ArrayList<Integer>();
            for (int x : s) stack.add(x);
            while (!stack.isEmpty()) {
                int slot = stack.removeLast();
                int d = def[slot];
                if (d < 0 || b.get(d)) continue;
                b.set(d);
                for (int a : insns.get(d).args()) stack.add(a);
            }
            cones.add(b);
            all.or(b);
        }
        int total = all.cardinality();
        var order = new ArrayList<Integer>();
        for (int i = 0; i < cones.size(); i++) order.add(i);
        order.sort((x, y) -> cones.get(y).cardinality() - cones.get(x).cardinality());
        int largest = cones.get(order.getFirst()).cardinality();
        StringBuilder sb = new StringBuilder();
        for (int p : new int[]{2, 4, 8, 16}) {
            var parts = new BitSet[p];
            for (int k = 0; k < p; k++) parts[k] = new BitSet();
            for (int i : order) {
                int best = -1, bestSize = Integer.MAX_VALUE;
                for (int k = 0; k < p; k++) {
                    var u = (BitSet) parts[k].clone();
                    u.or(cones.get(i));
                    int sz = u.cardinality();
                    if (sz < bestSize) { bestSize = sz; best = k; }
                }
                parts[best].or(cones.get(i));
            }
            int max = 0, sum = 0;
            for (var b : parts) { max = Math.max(max, b.cardinality()); sum += b.cardinality(); }
            sb.append(String.format(" P=%d max=%d sum=%d bound=%.2f", p, max, sum, (double) total / max));
        }
        System.out.printf("CONES %s insns=%d inCones=%d sinks=%d largestCone=%d (%.0f%%)%s%n", id, insns.size(), total, sinks.size(), largest,
                100.0 * largest / total, sb);
        // the partition ParallelSim actually uses (sinks include named signals; growth-first with a 1.1x cap)
        try {
            var m = Class.forName("org.eninehq.tessera.sim.ParallelSim").getMethod("groupSizes", Netlist.class, int.class);
            var sb2 = new StringBuilder();
            for (int p : new int[]{2, 4, 8}) {
                int[] g = (int[]) m.invoke(null, n, p);
                int max = Arrays.stream(g).max().orElse(0), sum = Arrays.stream(g).sum();
                sb2.append(String.format(" P=%d max=%d sum=%d bound=%.2f", p, max, sum, (double) insns.size() / max));
            }
            System.out.printf("PARTITION %s insns=%d%s%n", id, insns.size(), sb2);
        } catch (ReflectiveOperationException e) {
            System.out.println("PARTITION " + id + " n/a");
        }
    }

    /** P threads (the caller is one of them) meet at a spinning barrier {@code n} times; prints ns per barrier per rep. */
    static void barrier(int p, int n, int reps) throws InterruptedException {
        for (int rep = 0; rep < reps; rep++) {
            var arrived = new AtomicInteger();
            var gen = new AtomicInteger();
            Runnable body = () -> {
                for (int c = 0; c < n; c++) {
                    int g = gen.get();
                    if (arrived.incrementAndGet() == p) {
                        arrived.set(0);
                        gen.incrementAndGet();
                    } else {
                        while (gen.get() == g) Thread.onSpinWait();
                    }
                }
            };
            List<Thread> ts = new ArrayList<>();
            for (int k = 1; k < p; k++) ts.add(Thread.ofPlatform().start(body));
            long t0 = System.nanoTime();
            body.run();
            for (var t : ts) t.join();
            double ns = (System.nanoTime() - t0) / (double) n;
            System.out.printf("BARRIER threads=%d rep=%d ns_per_barrier=%.1f%n", p, rep, ns);
        }
    }
}
