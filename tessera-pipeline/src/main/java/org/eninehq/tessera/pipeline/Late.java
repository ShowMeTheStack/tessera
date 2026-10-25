package org.eninehq.tessera.pipeline;

import org.eninehq.tessera.core.Data;
import org.eninehq.tessera.core.Internal;
import org.eninehq.tessera.core.TesseraException;
import org.eninehq.tessera.ir.Signal;

/**
 * A value read in one stage but produced later in the code, typically by a later
 * (older) stage: forwarding paths and hazard checks. Spade's {@code stage(+1).x}.
 * <pre>{@code
 * Late<UInt> exResult = p.late(UInt.w(32));     // in ID
 * ... p.stage("EX"); ...
 * exResult.set(aluOut);                          // EX's value, seen by ID in the same cycle
 * }</pre>
 */
public final class Late<T extends Data> {
    private final T value;
    private final Signal wire;
    private final Pipeline pipeline;
    private boolean assigned;
    /** Where {@code late(...)} was called, for the error when it is never set. */
    final org.eninehq.tessera.core.SourceLoc created = org.eninehq.tessera.core.SourceLoc.here();

    Late(T value, Signal wire, Pipeline pipeline) {
        this.value = value;
        this.wire = wire;
        this.pipeline = pipeline;
    }

    /** The value, usable in the stage where {@code late(...)} was called. */
    public T get() {
        return value;
    }

    /** Bind the value: {@code v} as seen in the current stage, this cycle. */
    public void set(T v) {
        if (assigned) throw TesseraException.here("late value '" + wire.name() + "' is already set");
        if (v.stage() >= 0 && v.stageDomain() != pipeline)
            throw TesseraException.here("late value set from a different pipeline");
        Internal.assignRaw(wire, pipeline.adapt(v, Internal.resolveRaw(v)));
        assigned = true;
    }

    boolean assigned() { return assigned; }

    Signal wire() { return wire; }
}
