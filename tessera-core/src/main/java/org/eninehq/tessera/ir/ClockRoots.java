package org.eninehq.tessera.ir;

/** Resolves clock signals of child modules to the top-level clock inputs that drive them. */
public final class ClockRoots {
    private ClockRoots() {}

    /**
     * The top-level input port that drives {@code clock}, following instance connections.
     * Clocks must be connected directly (no logic on clock paths).
     */
    public static Signal root(Signal clock) {
        Signal s = clock;
        while (true) {
            var inst = s.owner().instantiatedAs();
            if (inst == null) return s;
            Expr d = inst.inputs().get(s);
            if (d == null) throw new IllegalStateException("clock " + s + " is not connected");
            if (!(d instanceof Expr.Ref r) || r.signal().kind() != Signal.Kind.INPUT || !r.signal().isClockOrReset())
                throw new IllegalStateException("clock " + s + " must be driven directly by a clock port of "
                        + inst.parent().name() + " (no logic on clock paths)");
            s = r.signal();
        }
    }

    /** The root clock of a register's domain. */
    public static Signal rootOf(ClockDomainDef d) {
        return root(d.clock());
    }
}
