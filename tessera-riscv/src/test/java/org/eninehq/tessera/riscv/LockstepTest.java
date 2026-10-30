package org.eninehq.tessera.riscv;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import org.eninehq.tessera.core.Tessera;
import org.eninehq.tessera.sim.Sim;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The interpreter (the reference) and the compiled simulator, both optimised, run every validation core
 * on random programs in lockstep; every named signal of up to 64 bits must agree on
 * every cycle, and every memory at the end.
 */
class LockstepTest {
    static final int CYCLES = 2500;

    static List<String> cores() {
        return Cores.ALL.stream().map(Cores.Spec::id).toList();
    }

    @ParameterizedTest
    @MethodSource("cores")
    void interpreterAndCompiledAgree(String id) {
        lockstep(id, () -> Sim.create(Tessera.elaborate(() -> Cores.get(id).build(4096, null)), Sim.Backend.COMPILED));
    }

    /** The experimental multi-threaded evaluation, 2 and 3 threads, against the interpreter. */
    @ParameterizedTest
    @MethodSource("cores")
    void interpreterAndParallelAgree(String id) {
        for (int t : new int[]{2, 3})
            lockstep(id, () -> new org.eninehq.tessera.sim.ParallelSim(
                    org.eninehq.tessera.sim.Netlist.build(Tessera.elaborate(() -> Cores.get(id).build(4096, null)).circuit()), t));
    }

    /** Three independent cores (different programs) on the parallel evaluation with 2 and 3 threads, against the interpreter. */
    @org.junit.jupiter.api.Test
    void multiCoreParallelAgrees() {
        for (String core : new String[]{"C5", "C9"})
            for (int t : new int[]{2, 3}) {
                var e = Tessera.elaborate(() -> new MultiCore(3, core, null));
                try (var a = Sim.create(e, Sim.Backend.INTERPRETER);
                     var b = new org.eninehq.tessera.sim.ParallelSim(org.eninehq.tessera.sim.Netlist.build(e.circuit()), t)) {
                    for (int k = 0; k < 3; k++) {
                        int[] prog = RandomProgram.generate(100 + k, 300, k % 2 == 1);
                        for (var s : List.of(a, b))
                            for (String m : new String[]{"imem", "dmem"}) {
                                long[] mem = s.memory("core" + k + "." + m);
                                for (int i = 0; i < prog.length; i++) mem[i] = Integer.toUnsignedLong(prog[i]);
                            }
                    }
                    var paths = new ArrayList<String>();
                    for (var x : a.netlist().signalsByPath().entrySet())
                        if (a.netlist().slotOf(x.getValue()) >= 0 && x.getValue().width() <= 64 && !a.netlist().isClock(x.getValue()))
                            paths.add(x.getKey());
                    a.reset(1);
                    b.reset(1);
                    for (int c = 0; c < 1500; c++) {
                        for (var p : paths) assertEquals(a.peek(p), b.peek(p), core + " x3, " + t + " threads, cycle " + c + " " + p);
                        a.step();
                        b.step();
                    }
                    for (var m : a.netlist().memsByPath().keySet()) assertArrayEquals(a.memory(m), b.memory(m), "memory " + m);
                }
            }
    }

    private static void lockstep(String id, java.util.function.Supplier<org.eninehq.tessera.sim.Simulator> other) {
        var e = Tessera.elaborate(() -> Cores.get(id).build(4096, null));
        for (long seed : new long[]{11, 12}) {
            int[] prog = RandomProgram.generate(seed, 300, seed % 2 == 1);
            try (var a = Sim.create(e, Sim.Backend.INTERPRETER); var b = other.get()) {
                for (var s : List.of(a, b))
                    for (String m : new String[]{"imem", "dmem"}) {
                        long[] mem = s.memory(m);
                        for (int i = 0; i < prog.length; i++) mem[i] = Integer.toUnsignedLong(prog[i]);
                    }
                var paths = new ArrayList<String>();
                for (var x : a.netlist().signalsByPath().entrySet())
                    if (a.netlist().slotOf(x.getValue()) >= 0 && x.getValue().width() <= 64 && !a.netlist().isClock(x.getValue()))
                        paths.add(x.getKey());
                a.reset(1);
                b.reset(1);
                for (int c = 0; c < CYCLES; c++) {
                    for (var p : paths) assertEquals(a.peek(p), b.peek(p), id + " seed " + seed + " cycle " + c + " " + p);
                    a.step();
                    b.step();
                }
                for (var m : a.netlist().memsByPath().keySet()) assertArrayEquals(a.memory(m), b.memory(m), id + " memory " + m);
            }
        }
    }
}
