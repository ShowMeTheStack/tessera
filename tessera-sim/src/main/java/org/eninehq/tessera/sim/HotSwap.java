package org.eninehq.tessera.sim;

import java.util.ArrayList;
import java.util.List;
import org.eninehq.tessera.ir.Circuit;

/**
 * Change the hardware while it runs: elaborate the edited design, then carry the register
 * and memory contents of the running simulation over by hierarchical name, so the new design
 * continues from the old state instead of from reset.
 */
public final class HotSwap {
    private HotSwap() {}

    /** What was carried over: registers and memories found in both designs, and the ones only in the new one. */
    public record Report(int registers, int memories, List<String> newRegisters, List<String> droppedRegisters) {}

    public record Swapped(Simulator sim, Report report) {}

    /** A new simulator for {@code edited}, starting from the state of {@code running} (which is closed). */
    public static Swapped swap(Simulator running, Circuit edited, Sim.Backend backend) {
        var oldRegs = running.registerState();
        var next = Sim.create(edited, backend);
        int regs = next.loadRegisterState(oldRegs);
        var fresh = new ArrayList<String>();
        for (var p : next.registerState().keySet()) if (!oldRegs.containsKey(p)) fresh.add(p);
        var dropped = new ArrayList<String>();
        var newPaths = next.registerState().keySet();
        for (var p : oldRegs.keySet()) if (!newPaths.contains(p)) dropped.add(p);
        int mems = 0;
        for (var e : running.netlist().memsByPath().entrySet()) {
            Integer idx = next.netlist().memsByPath().get(e.getKey());
            if (idx == null) continue;
            long[] from = running.memory(e.getKey()), to = next.memory(e.getKey());
            System.arraycopy(from, 0, to, 0, Math.min(from.length, to.length));
            mems++;
        }
        running.close();
        return new Swapped(next, new Report(regs, mems, fresh, dropped));
    }
}
