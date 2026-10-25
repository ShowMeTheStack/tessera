package org.eninehq.tessera.core;

import org.eninehq.tessera.ir.ClockDomainDef;

/**
 * A clock domain: a clock, a reset and the reset's behaviour. Create extra domains with
 * {@link Module#clockDomain}, put logic in them with {@link Module#withClock}, and move
 * values between domains only through {@link Hw#synchronize} or an asynchronous FIFO.
 */
public final class ClockDomain {
    final ClockDomainDef def;

    ClockDomain(ClockDomainDef def) {
        this.def = def;
    }

    public String name() { return def.name(); }

    public ClockDomainDef.ResetKind resetKind() { return def.resetKind(); }

    /** The raw reset input of this domain (active level as declared). */
    public Bool reset() {
        return new Bool(Elab.plainInit(def.reset()));
    }

    /** True while this domain's reset is asserted, whatever its polarity. */
    public Bool resetActive() {
        return def.resetKind().activeHigh() ? reset() : reset().not();
    }

    /** Internal: the IR domain. */
    public ClockDomainDef def() { return def; }

    @Override public String toString() { return def.toString(); }
}
