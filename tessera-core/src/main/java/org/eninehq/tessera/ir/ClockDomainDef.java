package org.eninehq.tessera.ir;

/**
 * A clock domain of a module: a clock input, a reset input and how the reset behaves.
 * Every register and memory write port belongs to exactly one domain.
 */
public final class ClockDomainDef {

    /** Reset behaviour. Synchronous resets are sampled on the clock edge; asynchronous ones act immediately. */
    public enum ResetKind {
        SYNC_HIGH(false, true), SYNC_LOW(false, false), ASYNC_HIGH(true, true), ASYNC_LOW(true, false);

        private final boolean async, activeHigh;

        ResetKind(boolean async, boolean activeHigh) {
            this.async = async;
            this.activeHigh = activeHigh;
        }

        public boolean async() { return async; }
        public boolean activeHigh() { return activeHigh; }
    }

    private final String name;
    private final Signal clock, reset;
    private final ResetKind resetKind;
    private final ModuleDef owner;

    public ClockDomainDef(String name, Signal clock, Signal reset, ResetKind resetKind, ModuleDef owner) {
        this.name = name;
        this.clock = clock;
        this.reset = reset;
        this.resetKind = resetKind;
        this.owner = owner;
    }

    public String name() { return name; }
    public Signal clock() { return clock; }
    public Signal reset() { return reset; }
    public ResetKind resetKind() { return resetKind; }
    public ModuleDef owner() { return owner; }

    @Override public String toString() { return owner.name() + "." + name; }
}
