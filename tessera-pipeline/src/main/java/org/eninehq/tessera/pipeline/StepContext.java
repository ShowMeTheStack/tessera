package org.eninehq.tessera.pipeline;

import java.util.IdentityHashMap;
import java.util.Map;
import org.eninehq.tessera.core.Bool;
import org.eninehq.tessera.core.TesseraException;

/** What a step sees: the values produced so far for the current item, and its pipeline status. */
public final class StepContext {
    private final Map<Key<?>, Object> values = new IdentityHashMap<>();
    private final Map<Key<?>, String> producers = new IdentityHashMap<>();
    /** The keys in the order they were produced (for deterministic generation over all of them). */
    private final java.util.List<Key<?>> order = new java.util.ArrayList<>();
    private final InOrderModel model;
    private final Schedule schedule;
    Pipeline pipeline;
    Bool halted;
    String step;
    /** The issue lane (0 in a single-issue pipeline) and, in lane pipelines, whether this lane holds a real item. */
    int lane;
    Bool laneValid;

    StepContext(InOrderModel model, Schedule schedule) {
        this.model = model;
        this.schedule = schedule;
    }

    @SuppressWarnings("unchecked")
    public <T> T get(Key<T> key) {
        Object v = values.get(key);
        if (v == null) {
            String by = model.producerOf(key);
            throw TesseraException.here("step '" + step + "' reads " + key + ", which "
                    + (by == null ? "no step produces"
                            : "is produced by step '" + by + "' in stage " + schedule.stageOf(by) + ", later than this step"));
        }
        return (T) v;
    }

    public boolean has(Key<?> key) {
        return values.containsKey(key);
    }

    public <T> void put(Key<T> key, T value) {
        if (value == null) throw TesseraException.here("step '" + step + "' put null for " + key);
        if (values.containsKey(key))
            throw TesseraException.here(key + " is produced twice (by '" + producers.get(key) + "' and '" + step + "')");
        values.put(key, value);
        producers.put(key, step);
        order.add(key);
    }

    /**
     * {@code x}, read now and carried along with this item through later stages. Values from
     * outside the pipeline (registers, inputs) are otherwise read live in whatever stage uses them.
     */
    public <T extends org.eninehq.tessera.core.Data> T capture(T x) {
        return pipeline.input(x);
    }

    /** {@code x} comes out of a register clocked while this stage advances (a synchronous read): a next-stage value. */
    public <T extends org.eninehq.tessera.core.Data> T registered(T x) {
        return pipeline.registered(x);
    }

    /** True when the current stage advances this cycle. */
    public Bool advancing() {
        return pipeline.advancing();
    }

    /**
     * The current stage cannot advance this cycle (a multi-cycle unit is busy): it and all
     * earlier stages hold, the next stage gets a bubble.
     */
    public void stall(Bool cond) {
        pipeline.stall(cond.and(pipeline.valid()));
    }

    /** True when the current stage holds a real item (in this lane). */
    public Bool valid() {
        return laneValid == null ? pipeline.valid() : pipeline.valid().and(laneValid);
    }

    /** True when the current stage holds a real item and advances this cycle: gate side effects with this. */
    public Bool fire() {
        return laneValid == null ? pipeline.valid().and(pipeline.advancing()) : valid().and(pipeline.advancing());
    }

    /**
     * The issue lane this step body is generating hardware for: always 0 in a single-issue pipeline; 0 or 1 in a
     * dual-issue one, where each step runs once per lane. A model can leave units out of lane 1 with it.
     */
    public int lane() {
        return lane;
    }

    /** The keys produced so far for this item, in the order they were produced. */
    java.util.List<Key<?>> keys() {
        return order;
    }

    /** True once the pipeline has retired a halting item. */
    public Bool halted() {
        return halted;
    }

    /** The name of the current stage. */
    public String stage() {
        return schedule.stageNames().get(pipeline.current());
    }
}
