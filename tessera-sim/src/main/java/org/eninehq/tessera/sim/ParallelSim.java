package org.eninehq.tessera.sim;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Experimental: the compiled simulator with the combinational evaluation split over threads.
 * Every value something outside the evaluation reads (register inputs, write-port operands, properties,
 * named signals) is a sink; the sinks are partitioned into {@code threads} groups and each group's code
 * computes the whole fan-in cone of its sinks, so the groups never wait for each other inside a cycle
 * (logic shared by two groups is computed by both, writing equal values). Each group also owns the
 * registers whose next value its sinks include: at the end of its evaluation it computes their next values
 * into a scratch array (reading only), and at the clock edge it writes them (two round trips per cycle:
 * evaluate, then write registers; memory writes and properties stay on the calling thread). Worker threads
 * spin while waiting (they use a CPU each), and are stopped by {@link #close()}.
 *
 * <p>It relies on 64-bit array stores not tearing (true of HotSpot on 64-bit platforms): when two
 * groups store the same slot concurrently they store the same value.
 */
public final class ParallelSim extends AbstractSim {
    private final CompiledChunk[][] groups;
    /** Per group: next values of its registers into tmp, and tmp into its registers (null without generated commit). */
    private final CompiledCommit[] computeNext, writeRegs;
    private final long[] tmp;
    /** What the workers do on the next generation: 0 evaluate (and compute next values), 1 write registers. */
    private volatile int phase;
    private final int[] groupSizes;
    private final Thread[] workers;
    private volatile int gen;
    private volatile boolean stop;
    private volatile Throwable error;
    private final AtomicInteger done = new AtomicInteger();

    public ParallelSim(Netlist net, int threads) {
        super(net);
        if (threads < 1) throw new IllegalArgumentException("threads " + threads);
        var regs = narrowRegs();
        int[] owner = new int[regs.length];
        int[][] parts = partition(net, threads, regs, owner);
        groups = new CompiledChunk[parts.length][];
        groupSizes = new int[parts.length];
        computeNext = new CompiledCommit[parts.length];
        writeRegs = new CompiledCommit[parts.length];
        tmp = new long[regs.length];
        boolean generated = !hasWideRegs() && CompiledSim.on("commit");
        for (int k = 0; k < parts.length; k++) {
            groups[k] = CompiledSim.code(net, parts[k], regs, false).chunks();
            groupSizes[k] = parts[k].length;
            if (generated) {
                boolean[] mine = new boolean[regs.length];
                for (int i = 0; i < regs.length; i++) mine[i] = owner[i] == k;
                computeNext[k] = CompiledSim.commitPart(net, regs, mine, 1);
                writeRegs[k] = CompiledSim.commitPart(net, regs, mine, 2);
            }
        }
        if (generated) fastCommit = (vv, ignored) -> run(1);
        workers = new Thread[parts.length - 1];
        for (int k = 1; k < parts.length; k++) {
            final int g = k;
            workers[k - 1] = Thread.ofPlatform().daemon().name("tessera-parallel-" + k).start(() -> work(g));
        }
    }

    /** The instruction counts of the groups {@link ParallelSim} would use for {@code net} with {@code threads} threads. */
    public static int[] groupSizes(Netlist net, int threads) {
        return Arrays.stream(partition(net, threads)).mapToInt(a -> a.length).toArray();
    }

    /** Instructions per group (the largest bounds the speed-up). */
    public int[] groupSizes() { return groupSizes.clone(); }

    private void work(int g) {
        int seen = 0;
        while (true) {
            int spins = 0, cur;
            while ((cur = gen) == seen) {
                if (stop) return;
                if (++spins > (1 << 20)) Thread.yield();
                else Thread.onSpinWait();
            }
            seen = cur;
            try {
                if (phase == 0) evalGroup(g);
                else writeRegs[g].commit(v, tmp);
            } catch (Throwable t) {
                error = t;
            }
            done.incrementAndGet();
        }
    }

    private void evalGroup(int g) {
        for (var c : groups[g]) c.eval(v, mems);
        if (computeNext[g] != null) computeNext[g].commit(v, tmp);
    }

    @Override
    protected void eval() {
        run(0);
    }

    /** One round: phase 0 evaluates, phase 1 writes the registers; the calling thread does group 0. */
    private void run(int ph) {
        done.set(0);
        phase = ph;
        gen++; // publishes the inputs and registers (and the phase) to the workers
        if (ph == 0) evalGroup(0);
        else writeRegs[0].commit(v, tmp);
        int need = workers.length;
        while (done.get() < need) Thread.onSpinWait();
        var e = error;
        if (e != null) throw new IllegalStateException("parallel evaluation failed", e);
    }

    @Override
    public void close() {
        stop = true;
        for (var w : workers) {
            try {
                w.join();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
        super.close();
    }

    /**
     * Instruction indices (topological order) per group: the sinks, largest cone first, each go to the
     * group whose instruction set grows least by its cone.
     */
    static int[][] partition(Netlist net, int threads) {
        return partition(net, threads, net.regs().stream().filter(r -> r.width() <= 64).toArray(Netlist.Reg[]::new), null);
    }

    /** As above; {@code owner[i]} (when not null) receives the group of narrow register i (0 when its next value needs no logic). */
    static int[][] partition(Netlist net, int threads, Netlist.Reg[] narrow, int[] owner) {
        var insns = net.insns();
        int n = net.slotCount();
        int[] def = new int[n];
        Arrays.fill(def, -1);
        for (int i = 0; i < insns.size(); i++) {
            var in = insns.get(i);
            for (int k = 0; k < Math.max(1, BitVec.words(in.width())); k++) def[in.dst() + k] = i;
        }
        var sinks = new ArrayList<int[]>();
        var sinkReg = new ArrayList<Integer>();
        for (int i = 0; i < narrow.length; i++) {
            var r = narrow[i];
            sinks.add(r.hasReset() ? new int[]{r.next(), r.resetSlot()} : new int[]{r.next()});
            sinkReg.add(i);
        }
        for (var r : net.regs()) if (r.width() > 64) { sinks.add(new int[]{r.next()}); sinkReg.add(-1); }
        for (var w : net.memWrites()) { sinks.add(new int[]{w.addr(), w.data(), w.en()}); sinkReg.add(-1); }
        for (var c : net.checks()) { sinks.add(new int[]{c.cond(), c.en()}); sinkReg.add(-1); }
        for (int s = 0; s < n; s++) if (net.isSignalSlot(s) && def[s] >= 0) { sinks.add(new int[]{s}); sinkReg.add(-1); }
        List<BitSet> cones = new ArrayList<>();
        List<Integer> coneReg = new ArrayList<>();
        for (int si = 0; si < sinks.size(); si++) {
            int[] sink = sinks.get(si);
            var b = new BitSet();
            var stack = new ArrayList<Integer>();
            for (int x : sink) stack.add(x);
            while (!stack.isEmpty()) {
                int slot = stack.removeLast();
                int d = def[slot];
                if (d < 0 || b.get(d)) continue;
                b.set(d);
                for (int a : insns.get(d).args()) for (int k = 0; k < BitVec.words(net.slotWidth(a) == 0 ? 1 : net.slotWidth(a)); k++) stack.add(a + k);
            }
            if (!b.isEmpty()) { cones.add(b); coneReg.add(sinkReg.get(si)); }
        }
        var idx = new ArrayList<Integer>();
        for (int i = 0; i < cones.size(); i++) idx.add(i);
        idx.sort((x, y) -> cones.get(y).cardinality() - cones.get(x).cardinality());
        var parts = new BitSet[threads];
        for (int k = 0; k < threads; k++) parts[k] = new BitSet();
        // each cone goes where it adds the fewest new instructions (ties: the smaller group), unless that would
        // take the group over a cap of 1.1x an even share; when no group is under the cap, to the smallest result
        var everything = new BitSet();
        for (var c : cones) everything.or(c);
        long cap = (long) Math.ceil(1.1 * everything.cardinality() / threads);
        for (int ci : idx) {
            var cone = cones.get(ci);
            int best = -1, bestGrowth = Integer.MAX_VALUE, fallback = 0, fallbackSize = Integer.MAX_VALUE;
            for (int k = 0; k < threads; k++) {
                var u = (BitSet) parts[k].clone();
                u.or(cone);
                int sz = u.cardinality(), growth = sz - parts[k].cardinality();
                if (sz < fallbackSize) { fallbackSize = sz; fallback = k; }
                if (sz > cap) continue;
                if (growth < bestGrowth || growth == bestGrowth && parts[k].cardinality() < parts[best].cardinality()) {
                    bestGrowth = growth;
                    best = k;
                }
            }
            if (best < 0) best = fallback;
            parts[best].or(cone);
            if (owner != null && coneReg.get(ci) >= 0) owner[coneReg.get(ci)] = best;
        }
        // drop empty groups, renumbering the owners
        var out = new ArrayList<int[]>();
        int[] renum = new int[threads];
        for (int k = 0; k < threads; k++) {
            if (parts[k].isEmpty() && !(k == 0 && out.isEmpty())) { renum[k] = 0; continue; }
            renum[k] = out.size();
            out.add(parts[k].stream().toArray());
        }
        if (owner != null) for (int i = 0; i < owner.length; i++) owner[i] = renum[owner[i]];
        return out.toArray(int[][]::new);
    }
}
