package org.eninehq.tessera.riscv;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.Arrays;
import org.eninehq.tessera.core.Elaborated;
import org.eninehq.tessera.sim.Sim;
import org.eninehq.tessera.sim.Simulator;

/** Runs a program on a core and checks every retired instruction against the golden ISS. */
final class CoreRunner {
    private CoreRunner() {}

    record Result(long cycles, long retired, int gp) {
        double cpi() { return (double) cycles / retired; }
    }

    static Result run(Elaborated<? extends RvCore> e, Simulator sim, int[] image, long maxCycles) {
        return run(e, sim, image, maxCycles, false);
    }

    /** With {@code zicsr}: the ISS runs in machine mode too (traps go to mtvec), for programs that set it up. */
    static Result run(Elaborated<? extends RvCore> e, Simulator sim, int[] image, long maxCycles, boolean zicsr) {
        RvCore core = e.top();
        long[] imem = sim.memory(core.imem), dmem = sim.memory(core.dmem);
        Arrays.fill(imem, 0);
        Arrays.fill(dmem, 0);
        clearRegs(sim, core);
        for (int i = 0; i < image.length; i++) {
            imem[i] = Integer.toUnsignedLong(image[i]);
            dmem[i] = Integer.toUnsignedLong(image[i]);
        }
        var iss = new Rv32iIss(imem.length, image, zicsr);
        var effects = new SideEffects(sim, core, iss);
        sim.reset(1);
        long start = sim.cycle(), retired = 0;
        // channel 0, then channel 1 of a dual-issue core: program order
        var ports = core.retirePorts();
        while (sim.peek(core.halted) == 0) {
            if (sim.cycle() - start > maxCycles) fail("no halt within " + maxCycles + " cycles; last pc " + iss.pc);
            for (var port : ports) {
                if (sim.peek(port.valid()) != 1) continue;
                var got = new Retire((int) sim.peek(port.pc()), (int) sim.peek(port.insn()),
                        (int) sim.peek(port.rd()), (int) sim.peek(port.rdValue()));
                var want = iss.step();
                if (!got.equals(want)) fail("retire #" + retired + " differs:\n  core " + got + "\n  iss  " + want);
                retired++;
            }
            effects.sample();
            sim.step();
        }
        assertTrue(iss.halted, "core halted before the ISS (retired " + retired + ")");
        effects.check(dmem);
        long[] regs = regs(sim, core);
        for (int r = 1; r < 32; r++) assertEquals(iss.x[r], (int) regs[r], "x" + r + " at halt");
        return new Result(sim.cycle() - start, retired, (int) regs[3]);
    }

    /** Zero the register file: the memory, or every bank copy and the table of a live-value table. */
    static void clearRegs(Simulator sim, RvCore c) {
        if (c.regs != null) Arrays.fill(sim.memory(c.regs), 0);
        else for (var m : c.lvtRegs.memories()) Arrays.fill(sim.memory(m), 0);
    }

    /** The architectural registers: the memory, or for a live-value table each register from the bank that wrote it last. */
    static long[] regs(Simulator sim, RvCore c) {
        if (c.regs != null) return sim.memory(c.regs);
        var t = c.lvtRegs;
        long[] table = t.table() == null ? new long[32] : sim.memory(t.table()), out = new long[32];
        for (int i = 0; i < 32; i++) out[i] = sim.memory(t.copy((int) table[i], 0))[i];
        return out;
    }

    static Simulator sim(Elaborated<?> e) {
        return Sim.create(e, Sim.Backend.COMPILED);
    }
}
