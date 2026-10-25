package org.eninehq.tessera.stdlib;

import org.eninehq.tessera.core.Bool;
import org.eninehq.tessera.core.Bundle;
import org.eninehq.tessera.core.Shape;

/**
 * A ready/valid stream (AXI-Stream style handshake, Chisel's {@code Decoupled}): an item
 * moves when {@code valid} and {@code ready} are both high in the same cycle. {@code ready}
 * flows against the data, so an input stream has {@code ready} as an output.
 */
public record Stream<T>(Bool valid, Bool ready, T bits) {

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static <T> Shape<Stream<T>> of(Shape<T> payload) {
        return (Shape) Bundle.of(Stream.class, Bool.TYPE, Shape.flip(Bool.TYPE), payload);
    }

    /** True when an item is transferred this cycle. */
    public Bool fire() {
        return valid.and(ready);
    }
}
