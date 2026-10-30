package org.eninehq.tessera.sim;

import java.nio.file.Path;
import org.eninehq.tessera.core.Data;
import org.eninehq.tessera.core.Mem;

/** A cycle-based simulator of an elaborated design. */
public interface Simulator extends AutoCloseable {

    /** Drive a top-level input. */
    void poke(Data input, long value);

    void poke(String path, long value);

    /** Read any named signal (ports, wires, registers), anywhere in the hierarchy. */
    long peek(Data signal);

    long peek(String path);

    /** Signed view of {@link #peek(Data)}. */
    default long peekSigned(Data signal) {
        long v = peek(signal);
        int w = signal.width();
        return w == 64 ? v : (v << (64 - w)) >> (64 - w);
    }

    default boolean peekBool(Data signal) {
        return peek(signal) != 0;
    }

    /** Advance one clock cycle (rising edge). */
    void step();

    default void step(int cycles) {
        for (int i = 0; i < cycles; i++) step();
    }

    /** Assert reset for {@code cycles} cycles, then release it. */
    default void reset(int cycles) {
        poke("reset", 1);
        step(cycles);
        poke("reset", 0);
    }

    /** Rising edge of one clock only (designs with several clock domains). */
    void step(String clock);

    /** Simultaneous rising edges of the given clocks. */
    void stepClocks(java.util.Collection<String> clocks);

    /** Top-level clock inputs, by path. */
    java.util.List<String> clocks();

    /** Number of clock edges each cover property held on. */
    java.util.Map<String, Long> coverHits();

    /** Number of edges simulated (on any clock). */
    long cycle();

    /** Live contents of a memory (writes are visible to the design). */
    long[] memory(Mem<?> mem);

    long[] memory(String path);

    /** Drive a top-level input of any width. */
    void pokeBig(String path, java.math.BigInteger value);

    /** Read a signal of any width (signals wider than 64 bits need this instead of {@link #peek}). */
    java.math.BigInteger peekBig(String path);

    java.math.BigInteger peekBig(Data signal);

    /** The complete simulation state at the current cycle: every value, every memory word, cover counts. */
    record Snapshot(long cycle, long[] values, long[][] memories, long[] coverHits) {}

    /** Capture the state (cheap: one array copy per memory). */
    Snapshot snapshot();

    /** Go back (or forward) to a captured state of the same design. */
    void restore(Snapshot s);

    /** Current register values by hierarchical path (for carrying state across a design change). */
    java.util.Map<String, java.math.BigInteger> registerState();

    /** Load register values by path; registers missing from {@code state} keep their value. Returns how many were loaded. */
    int loadRegisterState(java.util.Map<String, java.math.BigInteger> state);

    /** Called just before every clock edge, after the values have settled. */
    interface Listener {
        void beforeEdge(Simulator sim, long cycle);
    }

    void addListener(Listener l);

    /** Called for each write to a memory as it commits at a clock edge: the cycle, the element index and its value (up to 64 bits). */
    interface MemoryWriteListener {
        void written(long cycle, long index, long value);
    }

    /** Observe every committed write to {@code mem}; no cost for memories nobody observes. */
    default void addMemoryWriteListener(Mem<?> mem, MemoryWriteListener l) {
        throw new UnsupportedOperationException("memory write listeners: " + getClass().getSimpleName());
    }

    /**
     * Record a waveform of every named signal to {@code file}: FST when the name ends in
     * {@code .fst}, VCD otherwise. A {@code <file>.types.json} sidecar describes the Java types.
     */
    void trace(Path file);

    Netlist netlist();

    @Override
    void close();
}
