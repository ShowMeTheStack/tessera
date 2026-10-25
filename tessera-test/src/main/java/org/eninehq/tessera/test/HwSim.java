package org.eninehq.tessera.test;

import java.math.BigInteger;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.eninehq.tessera.core.Data;
import org.eninehq.tessera.core.Elaborated;
import org.eninehq.tessera.core.Mem;
import org.eninehq.tessera.core.Module;
import org.eninehq.tessera.ir.Signal;
import org.eninehq.tessera.sim.Netlist;
import org.eninehq.tessera.sim.Simulator;

/**
 * A simulator bound to an elaborated design, given to {@link HwTest} methods. {@link #dut()}
 * is the top module, so ports can be poked and peeked through its fields. It records every
 * top-level input per cycle for Verilator replay.
 */
public final class HwSim<M extends Module> implements Simulator {
    private final Elaborated<M> design;
    private final Simulator sim;
    private final List<Signal> inputs = new ArrayList<>();
    private final Map<String, BigInteger> current = new LinkedHashMap<>();
    private final List<BigInteger[]> recorded = new ArrayList<>();
    private boolean recordable = true;

    HwSim(Elaborated<M> design, Simulator sim) {
        this.design = design;
        this.sim = sim;
        for (var p : design.circuit().top().ports())
            if (p.kind() == Signal.Kind.INPUT && !(p.isClockOrReset() && p.name().equals("clock"))) {
                inputs.add(p);
                current.put(sim.netlist().pathOf(p), BigInteger.ZERO);
            }
    }

    /** The top module: use its port fields with poke/peek. */
    public M dut() { return design.top(); }

    public Elaborated<M> design() { return design; }

    /** A testbench for concurrent processes on this simulator. */
    public Testbench testbench() { return new Testbench(this); }

    List<BigInteger[]> recorded() { return recordable ? recorded : null; }

    private void record(String path, BigInteger v) {
        if (current.containsKey(path)) current.put(path, v);
    }

    @Override public void poke(Data input, long value) {
        sim.poke(input, value);
        record(sim.netlist().pathOf(input.signal()), BigInteger.valueOf(value).and(maskOf(input.width())));
    }

    @Override public void poke(String path, long value) {
        sim.poke(path, value);
        var s = sim.netlist().signalsByPath().get(path);
        if (s != null) record(path, BigInteger.valueOf(value).and(maskOf(s.width())));
    }

    @Override public void pokeBig(String path, BigInteger value) {
        sim.pokeBig(path, value);
        var s = sim.netlist().signalsByPath().get(path);
        if (s != null) record(path, value.and(maskOf(s.width())));
    }

    private static BigInteger maskOf(int w) {
        return BigInteger.ONE.shiftLeft(w).subtract(BigInteger.ONE);
    }

    @Override public void step() {
        recorded.add(current.values().toArray(BigInteger[]::new));
        sim.step();
    }

    @Override public void step(String clock) {
        recordable = false;
        sim.step(clock);
    }

    @Override public void stepClocks(Collection<String> clocks) {
        recordable = false;
        sim.stepClocks(clocks);
    }

    @Override public long peek(Data signal) { return sim.peek(signal); }
    @Override public long peek(String path) { return sim.peek(path); }
    @Override public BigInteger peekBig(String path) { return sim.peekBig(path); }
    @Override public BigInteger peekBig(Data signal) { return sim.peekBig(signal); }
    @Override public List<String> clocks() { return sim.clocks(); }
    @Override public Map<String, Long> coverHits() { return sim.coverHits(); }
    @Override public long cycle() { return sim.cycle(); }
    @Override public long[] memory(Mem<?> mem) { recordable = false; return sim.memory(mem); }
    @Override public long[] memory(String path) { recordable = false; return sim.memory(path); }
    @Override public Snapshot snapshot() { return sim.snapshot(); }
    @Override public void restore(Snapshot s) { recordable = false; sim.restore(s); }
    @Override public Map<String, BigInteger> registerState() { return sim.registerState(); }
    @Override public int loadRegisterState(Map<String, BigInteger> state) { recordable = false; return sim.loadRegisterState(state); }
    @Override public void addListener(Listener l) { sim.addListener(l); }
    @Override public void trace(Path file) { sim.trace(file); }
    @Override public Netlist netlist() { return sim.netlist(); }
    @Override public void close() { sim.close(); }
}
