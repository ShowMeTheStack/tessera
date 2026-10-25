package org.eninehq.tessera.sim;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Time-travel debugging: run a simulation forward, then jump back to any earlier cycle and
 * look at it again. The recorder takes a snapshot every {@code interval} cycles and logs every
 * input change; going to cycle {@code c} restores the nearest snapshot at or before {@code c}
 * and replays the logged inputs, so it costs at most {@code interval} cycles of re-simulation.
 *
 * <pre>{@code
 * var tt = new TimeTravel(sim, 256);
 * tt.poke("in", 3); tt.step(10_000);       // run, as usual
 * tt.goTo(4_321);                          // back in time
 * tt.history("u_core.pc", 4_300, 4_330);   // a signal over a range of cycles
 * }</pre>
 */
public final class TimeTravel {
    private final Simulator sim;
    private final int interval;
    private final TreeMap<Long, Simulator.Snapshot> snapshots = new TreeMap<>();
    /** Inputs poked before the edge of each cycle. */
    private final TreeMap<Long, Map<String, BigInteger>> inputs = new TreeMap<>();
    /** Memory words written from outside before the edge of each cycle: {memory path, address, words}. */
    private final TreeMap<Long, List<Object[]>> memWrites = new TreeMap<>();
    private long head;
    private Stepper stepper = (s, c) -> s.step();

    /**
     * How the step at a cycle is taken. By default every clock has an edge; a design with several
     * clocks of different periods steps only the clocks due at that cycle (a cycle is then a tick of
     * a common time base), and replays take the same edges.
     */
    @FunctionalInterface
    public interface Stepper {
        void step(Simulator sim, long cycle);
    }

    public void stepper(Stepper s) { this.stepper = s; }

    public TimeTravel(Simulator sim, int interval) {
        if (interval < 1) throw new IllegalArgumentException("interval must be >= 1");
        this.sim = sim;
        this.interval = interval;
        snapshots.put(sim.cycle(), sim.snapshot());
        head = sim.cycle();
    }

    public Simulator sim() { return sim; }

    /** The furthest cycle simulated so far. */
    public long head() { return head; }

    public void poke(String path, long value) {
        pokeBig(path, BigInteger.valueOf(value));
    }

    public void pokeBig(String path, BigInteger value) {
        truncateFuture();
        inputs.computeIfAbsent(sim.cycle(), k -> new LinkedHashMap<>()).put(path, value);
        sim.pokeBig(path, value);
    }

    /**
     * Write a memory word from outside the design (a debugger's edit), logged like an input so that
     * going back and forward again replays it. {@code words} is the word in 64-bit pieces, low first.
     */
    public void pokeMemory(String path, int address, long[] words) {
        truncateFuture();
        memWrites.computeIfAbsent(sim.cycle(), k -> new ArrayList<>()).add(new Object[]{path, address, words.clone()});
        writeWord(path, address, words);
    }

    private void writeWord(String path, int address, long[] words) {
        long[] mem = sim.memory(path);
        System.arraycopy(words, 0, mem, address * words.length, words.length);
    }

    /** Leaving the past by changing an input forks a new future: forget the old one. */
    private void truncateFuture() {
        long now = sim.cycle();
        if (now >= head) return;
        snapshots.tailMap(now, false).clear();
        inputs.tailMap(now, false).clear();
        memWrites.tailMap(now, false).clear();
        head = now;
    }

    public void step() {
        stepper.step(sim, sim.cycle());
        long c = sim.cycle();
        if (c > head) head = c;
        if (c % interval == 0 && !snapshots.containsKey(c)) snapshots.put(c, sim.snapshot());
    }

    public void step(long n) {
        for (long i = 0; i < n; i++) step();
    }

    /** Go to any cycle between the first recorded one and {@link #head()} (or further forward, by simulating). */
    public void goTo(long cycle) {
        if (cycle < snapshots.firstKey()) throw new IllegalArgumentException("cycle " + cycle + " is before the recording started");
        var e = snapshots.floorEntry(cycle);
        sim.restore(e.getValue());
        for (long c = e.getKey(); c < cycle; c++) {
            replayInputs(c);
            stepper.step(sim, c);
        }
        replayInputs(cycle);
    }

    private void replayInputs(long cycle) {
        // the latest value of every input up to this cycle is already in the snapshot except for this cycle's pokes
        var m = inputs.get(cycle);
        if (m != null) m.forEach(sim::pokeBig);
        var w = memWrites.get(cycle);
        if (w != null) for (var x : w) writeWord((String) x[0], (Integer) x[1], (long[]) x[2]);
    }

    public void back(long n) {
        goTo(Math.max(snapshots.firstKey(), sim.cycle() - n));
    }

    /** Values of {@code path} for cycles {@code from} (inclusive) to {@code to} (exclusive); leaves the simulator at {@code to}. */
    public List<BigInteger> history(String path, long from, long to) {
        var out = new ArrayList<BigInteger>();
        goTo(from);
        for (long c = from; c < to; c++) {
            out.add(sim.peekBig(path));
            if (c + 1 < to) {
                stepper.step(sim, c);
                replayInputs(c + 1);
            }
        }
        if (to > from) {
            stepper.step(sim, to - 1);
            replayInputs(to);
        }
        return out;
    }

    /** The first cycle in [from, to) where {@code path} has {@code value}, or -1. */
    public long find(String path, BigInteger value, long from, long to) {
        var h = history(path, from, to);
        for (int i = 0; i < h.size(); i++) if (h.get(i).equals(value)) return from + i;
        return -1;
    }

    /** Snapshots kept (memory use is about this many copies of the state). */
    public int snapshotCount() { return snapshots.size(); }

    /** The earliest cycle that {@link #goTo} can still reach. */
    public long earliest() { return snapshots.firstKey(); }

    /**
     * Forget the history before {@code cycle}, to bound memory on long runs: snapshots and logged
     * inputs older than the last snapshot at or before {@code cycle} are dropped, so {@link #goTo}
     * reaches back to that snapshot and no further. The current state is never lost.
     */
    public void forgetBefore(long cycle) {
        var keep = snapshots.floorKey(Math.min(cycle, sim.cycle()));
        if (keep == null) return;
        snapshots.headMap(keep, false).clear();
        inputs.headMap(keep, false).clear();
        memWrites.headMap(keep, false).clear();
    }
}
