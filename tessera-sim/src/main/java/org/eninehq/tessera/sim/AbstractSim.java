package org.eninehq.tessera.sim;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.eninehq.tessera.core.Data;
import org.eninehq.tessera.core.Mem;
import org.eninehq.tessera.ir.Assertion;
import org.eninehq.tessera.ir.Signal;

/** Shared state and bookkeeping for simulator backends. */
abstract class AbstractSim implements Simulator {
    protected final Netlist net;
    protected final long[] v;
    protected final long[][] mems;
    protected boolean dirty = true;
    private final Netlist.Reg[] regs, wideRegs;
    private final Netlist.MemWrite[] writes;
    private final int[] writeWords;
    private final Netlist.Check[] checks;
    private final Netlist.Reg[] asyncRegs;
    private final long[] coverHits;
    private final long[] next;
    private final long[][] nextWide;
    private final long[] wAddr, wData;
    private final long[][] wDataWide;
    private final boolean[] wEn;
    protected long cycle;
    private WaveWriter vcd;
    private final List<Simulator.Listener> listeners = new ArrayList<>();

    AbstractSim(Netlist net) {
        this.net = net;
        this.v = net.initialValues();
        this.regs = net.regs().stream().filter(r -> r.width() <= 64).toArray(Netlist.Reg[]::new);
        this.wideRegs = net.regs().stream().filter(r -> r.width() > 64).toArray(Netlist.Reg[]::new);
        this.writes = net.memWrites().toArray(Netlist.MemWrite[]::new);
        this.writeWords = new int[writes.length];
        for (int i = 0; i < writes.length; i++) writeWords[i] = net.mems().get(writes[i].mem()).words();
        this.checks = net.checks().toArray(Netlist.Check[]::new);
        this.asyncRegs = net.regs().stream().filter(r -> r.hasReset() && r.asyncReset()).toArray(Netlist.Reg[]::new);
        this.coverHits = new long[checks.length];
        this.next = new long[regs.length];
        this.nextWide = new long[wideRegs.length][];
        for (int i = 0; i < wideRegs.length; i++) nextWide[i] = new long[wideRegs[i].words()];
        this.wAddr = new long[writes.length];
        this.wData = new long[writes.length];
        this.wDataWide = new long[writes.length][];
        for (int i = 0; i < writes.length; i++) if (writeWords[i] > 1) wDataWide[i] = new long[writeWords[i]];
        this.wEn = new boolean[writes.length];
        this.mems = new long[net.mems().size()][];
        for (int i = 0; i < mems.length; i++) {
            var m = net.mems().get(i);
            int nw = m.words();
            mems[i] = new long[m.depth() * nw];
            if (m.init() != null) {
                long mask = mask(m.width());
                for (int k = 0; k < m.init().length; k++) mems[i][k * nw] = m.init()[k] & mask;
            }
        }
    }

    static long mask(int w) {
        return w >= 64 ? -1L : (1L << w) - 1;
    }

    static long sx(long x, int w) {
        return w >= 64 ? x : (x << (64 - w)) >> (64 - w);
    }

    /** Evaluate all combinational logic. */
    protected abstract void eval();

    private void checkProperties(long mask) {
        for (int i = 0; i < checks.length; i++) {
            var c = checks[i];
            if ((mask >>> c.domain() & 1) == 0 || v[c.en()] == 0) continue;
            boolean ok = v[c.cond()] != 0;
            switch (c.kind()) {
                case COVER -> { if (ok) coverHits[i]++; }
                case ASSERT, ASSUME -> {
                    if (!ok) throw new AssertionError((c.kind() == Assertion.Kind.ASSERT ? "assertion '" : "assumption '")
                            + c.name() + "' failed at cycle " + cycle + " (" + c.loc() + ")");
                }
            }
        }
    }

    private boolean resetActive(Netlist.Reg r) {
        return r.hasReset() && v[r.resetSlot()] == r.resetActive();
    }

    /** Check properties, then compute next state from settled values and commit it, for the clocks in {@code mask}. */
    private void commit(long mask) {
        checkProperties(mask);
        boolean all = mask == -1L;
        if (all && fastCommit != null) {
            // generated register update (CompiledSim): same values, same order of effects as below
            captureWrites(true, mask);
            fastCommit.commit(v, next);
            applyWrites();
            return;
        }
        for (int i = 0; i < regs.length; i++) {
            var r = regs[i];
            if (!all && (mask >>> r.domain() & 1) == 0) next[i] = v[r.slot()];
            else next[i] = resetActive(r) ? r.resetValue() : v[r.next()];
        }
        for (int i = 0; i < wideRegs.length; i++) {
            var r = wideRegs[i];
            if (!all && (mask >>> r.domain() & 1) == 0) System.arraycopy(v, r.slot(), nextWide[i], 0, r.words());
            else if (resetActive(r)) System.arraycopy(r.resetWords(), 0, nextWide[i], 0, r.words());
            else System.arraycopy(v, r.next(), nextWide[i], 0, r.words());
        }
        captureWrites(all, mask);
        for (int i = 0; i < regs.length; i++) v[regs[i].slot()] = next[i];
        for (int i = 0; i < wideRegs.length; i++) System.arraycopy(nextWide[i], 0, v, wideRegs[i].slot(), wideRegs[i].words());
        applyWrites();
    }

    /**
     * Generated code that computes and stores the next value of every register of at most 64 bits
     * (all clocks, reset applied), reading only values settled before the edge; {@code tmp} has one
     * word per such register. Set only when the design has no wider register.
     */
    protected CompiledCommit fastCommit;

    /** Narrow registers, in the order {@link #fastCommit} indexes {@code tmp}. */
    protected final Netlist.Reg[] narrowRegs() { return regs; }

    protected final boolean hasWideRegs() { return wideRegs.length > 0; }

    /** Latch the write ports' enable, address and data from the settled values (before any register changes). */
    private void captureWrites(boolean all, long mask) {
        for (int i = 0; i < writes.length; i++) {
            var w = writes[i];
            wEn[i] = (all || (mask >>> w.domain() & 1) != 0) && v[w.en()] != 0;
            wAddr[i] = v[w.addr()];
            if (wDataWide[i] != null) System.arraycopy(v, w.data(), wDataWide[i], 0, writeWords[i]);
            else wData[i] = v[w.data()];
        }
    }

    private void applyWrites() {
        for (int i = 0; i < writes.length; i++) {
            if (!wEn[i]) continue;
            long[] mem = mems[writes[i].mem()];
            int nw = writeWords[i];
            if (Long.compareUnsigned(wAddr[i], mem.length / nw) >= 0) continue;
            if (nw == 1) mem[(int) wAddr[i]] = wData[i];
            else System.arraycopy(wDataWide[i], 0, mem, (int) wAddr[i] * nw, nw);
            if (writeListeners != null && writeListeners[i] != null)
                for (var l : writeListeners[i]) l.written(cycle, wAddr[i], nw == 1 ? wData[i] : wDataWide[i][0]);
        }
    }

    private void settle() {
        if (!dirty) return;
        eval();
        for (int iter = 0; asyncRegs.length > 0; iter++) {
            boolean changed = false;
            for (var r : asyncRegs) {
                if (!resetActive(r)) continue;
                for (int k = 0; k < r.words(); k++) {
                    if (v[r.slot() + k] != r.resetWords()[k]) {
                        v[r.slot() + k] = r.resetWords()[k];
                        changed = true;
                    }
                }
            }
            if (!changed) break;
            if (iter > 16) throw new IllegalStateException("asynchronous resets did not settle");
            eval();
        }
        dirty = false;
    }

    // ------------------------------------------------------------ clocks and properties

    @Override public Map<String, Long> coverHits() {
        var out = new LinkedHashMap<String, Long>();
        for (int i = 0; i < checks.length; i++)
            if (checks[i].kind() == Assertion.Kind.COVER) out.merge(checks[i].name(), coverHits[i], Long::sum);
        return out;
    }

    @Override public List<String> clocks() {
        return net.clockPaths();
    }

    @Override public void step(String clock) {
        int i = net.clockPaths().indexOf(clock);
        if (i < 0) throw new IllegalArgumentException("no clock " + clock + "; clocks are " + net.clockPaths());
        tick(1L << i);
    }

    @Override public void stepClocks(Collection<String> clocks) {
        long mask = 0;
        for (var c : clocks) {
            int i = net.clockPaths().indexOf(c);
            if (i < 0) throw new IllegalArgumentException("no clock " + c + "; clocks are " + net.clockPaths());
            mask |= 1L << i;
        }
        tick(mask);
    }

    @Override public void step() {
        tick(-1L);
    }

    private void tick(long mask) {
        settle();
        if (vcd != null) vcd.sample(cycle, v);
        for (var l : listeners) l.beforeEdge(this, cycle);
        commit(mask);
        cycle++;
        dirty = true;
    }

    @Override public long cycle() { return cycle; }

    @Override public void addListener(Simulator.Listener l) { listeners.add(l); }

    /** Per write port: the listeners of its memory (null when nobody observes it). */
    private Simulator.MemoryWriteListener[][] writeListeners;

    @Override public void addMemoryWriteListener(Mem<?> mem, Simulator.MemoryWriteListener l) {
        int m = net.memIndexOf(mem.def());
        if (writeListeners == null) writeListeners = new Simulator.MemoryWriteListener[writes.length][];
        for (int i = 0; i < writes.length; i++) {
            if (writes[i].mem() != m) continue;
            var old = writeListeners[i] == null ? new Simulator.MemoryWriteListener[0] : writeListeners[i];
            var n = java.util.Arrays.copyOf(old, old.length + 1);
            n[old.length] = l;
            writeListeners[i] = n;
        }
    }

    // ------------------------------------------------------------ poke and peek

    private int inputSlot(Signal s, String what) {
        if (s == null) throw new IllegalArgumentException("no signal " + what);
        if (s.kind() != Signal.Kind.INPUT || s.owner() != net.circuit().top())
            throw new IllegalArgumentException("can only poke top-level inputs, not " + what);
        return net.slotOf(s);
    }

    private void pokeSlot(int slot, long value) {
        int w = net.slotWidth(slot);
        if (w > 64) {
            pokeSlotBig(slot, BigInteger.valueOf(value).and(BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE)));
            return;
        }
        long nv = value & mask(w);
        if (v[slot] != nv) {
            v[slot] = nv;
            dirty = true;
        }
    }

    private void pokeSlotBig(int slot, BigInteger value) {
        BitVec.fromBig(value, v, slot, net.slotWidth(slot));
        dirty = true;
    }

    @Override public void poke(Data input, long value) {
        pokeSlot(inputSlot(input.signal(), String.valueOf(input)), value);
    }

    @Override public void poke(String path, long value) {
        if (path.equals("reset") && !net.signalsByPath().containsKey("reset")) return;
        pokeSlot(inputSlot(net.signalsByPath().get(path), path), value);
    }

    @Override public void pokeBig(String path, BigInteger value) {
        pokeSlotBig(inputSlot(net.signalsByPath().get(path), path), value);
    }

    /** {@code what} is only turned into a string for the error message (not on every peek). */
    private long peekSlot(int slot, Object what) {
        if (slot < 0) throw new IllegalArgumentException("signal " + what + " is not part of the simulated design");
        settle();
        return v[slot];
    }

    @Override public long peek(Data d) {
        if (d.signal() == null) throw new IllegalArgumentException("can only peek named signals (ports, wires, registers); "
                + "use .named(...) to name an expression");
        return peekSlot(net.slotOf(d.signal()), d);
    }

    @Override public long peek(String path) {
        Signal s = net.signalsByPath().get(path);
        if (s == null) throw new IllegalArgumentException("no signal " + path);
        return peekSlot(net.slotOf(s), path);
    }

    @Override public BigInteger peekBig(String path) {
        Signal s = net.signalsByPath().get(path);
        if (s == null) throw new IllegalArgumentException("no signal " + path);
        int slot = net.slotOf(s);
        peekSlot(slot, path);
        return BitVec.toBig(v, slot, s.width());
    }

    @Override public BigInteger peekBig(Data d) {
        if (d.signal() == null) throw new IllegalArgumentException("can only peek named signals");
        return peekBig(net.pathOf(d.signal()));
    }

    // ------------------------------------------------------------ memories, state, tracing

    @Override public long[] memory(Mem<?> mem) {
        dirty = true;
        return mems[net.memIndexOf(mem.def())];
    }

    @Override public long[] memory(String path) {
        Integer i = net.memsByPath().get(path);
        if (i == null) throw new IllegalArgumentException("no memory " + path);
        dirty = true;
        return mems[i];
    }

    @Override public Snapshot snapshot() {
        long[][] m = new long[mems.length][];
        for (int i = 0; i < mems.length; i++) m[i] = mems[i].clone();
        long[] hits = coverHits.clone();
        return new Snapshot(cycle, v.clone(), m, hits);
    }

    @Override public void restore(Snapshot s) {
        if (s.values().length != v.length || s.memories().length != mems.length)
            throw new IllegalArgumentException("snapshot is from a different design");
        System.arraycopy(s.values(), 0, v, 0, v.length);
        for (int i = 0; i < mems.length; i++) System.arraycopy(s.memories()[i], 0, mems[i], 0, mems[i].length);
        System.arraycopy(s.coverHits(), 0, coverHits, 0, coverHits.length);
        cycle = s.cycle();
        dirty = true;
    }

    @Override public Map<String, BigInteger> registerState() {
        var out = new LinkedHashMap<String, BigInteger>();
        for (var r : net.regs()) if (r.path() != null) out.put(r.path(), BitVec.toBig(v, r.slot(), r.width()));
        return out;
    }

    @Override public int loadRegisterState(Map<String, BigInteger> state) {
        int n = 0;
        for (var r : net.regs()) {
            var x = r.path() == null ? null : state.get(r.path());
            if (x == null) continue;
            BitVec.fromBig(x, v, r.slot(), r.width());
            n++;
        }
        dirty = true;
        return n;
    }

    @Override public void trace(Path file) {
        try {
            if (vcd != null) vcd.close();
            vcd = file.getFileName().toString().endsWith(".fst") ? new FstWriter(file, net) : new VcdWriter(file, net);
            TypeSidecar.write(net, file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override public Netlist netlist() { return net; }

    @Override public void close() {
        if (vcd != null) {
            settle();
            vcd.sample(cycle, v);
            vcd.close();
            vcd = null;
        }
    }
}
