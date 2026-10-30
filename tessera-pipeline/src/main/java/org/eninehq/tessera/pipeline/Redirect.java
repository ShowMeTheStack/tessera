package org.eninehq.tessera.pipeline;

import java.util.Map;
import org.eninehq.tessera.core.Bool;
import org.eninehq.tessera.core.Data;
import org.eninehq.tessera.core.TesseraException;
import org.eninehq.tessera.core.UInt;

/**
 * What the generator hands a model's repair ({@link InOrderModel#onRedirect}) about the redirect of this cycle: whether
 * one happens, the resolving item's pc and the next pc it resolved (where fetch goes), and the item's values of the
 * keys declared {@link InOrderModel#speculative speculative}. When several resolve points redirect in a cycle these
 * are the winning one's (the same priority as fetch's pc: the latest stage, lane 0 before lane 1).
 *
 * <p>All values are plain wires of the current cycle, usable anywhere (not tied to a stage). The repair typically
 * writes the model's speculative registers under {@code when(r.taken(), ...)}; being generated after every step, that
 * write takes precedence over fetch's speculative update of the same cycle (last assignment wins).
 */
public final class Redirect {
    private final Bool taken;
    private final UInt pc, nextPc, sequentialPc;
    private final Bool sequential;
    private final Map<Key<?>, Data> values;

    Redirect(Bool taken, UInt pc, UInt nextPc, UInt sequentialPc, Bool sequential, Map<Key<?>, Data> values) {
        this.taken = taken;
        this.pc = pc;
        this.nextPc = nextPc;
        this.sequentialPc = sequentialPc;
        this.sequential = sequential;
        this.values = values;
    }

    /** True when a resolve point redirects fetch this cycle. */
    public Bool taken() { return taken; }

    /** The resolving item's pc. */
    public UInt pc() { return pc; }

    /** The next pc the resolving item resolved: where fetch goes next cycle. */
    public UInt nextPc() { return nextPc; }

    /**
     * The resolving item's pc plus the pc increment (a call's return address). Computed at each resolve point beside the
     * redirect decision, so a repair using it does not add an adder after the selection.
     */
    public UInt sequentialPc() { return sequentialPc; }

    /** True when the resolved next pc is {@link #sequentialPc()} (the item did not jump); computed per point, as that. */
    public Bool sequential() { return sequential; }

    /** The resolving item's value of {@code key}, which must have been declared with {@link InOrderModel#speculative}. */
    @SuppressWarnings("unchecked")
    public <T extends Data> T get(Key<T> key) {
        Data v = values.get(key);
        if (v == null) throw TesseraException.here(key + " is not declared speculative(...); the repair can read only " + values.keySet());
        return (T) v;
    }
}
