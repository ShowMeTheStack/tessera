package org.eninehq.tessera.stdlib;

import org.eninehq.tessera.core.Bool;
import org.eninehq.tessera.core.Bundle;
import org.eninehq.tessera.core.Shape;

/** A value with a valid bit (no back-pressure). */
public record Valid<T>(Bool valid, T bits) {

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static <T> Shape<Valid<T>> of(Shape<T> payload) {
        return (Shape) Bundle.of(Valid.class, Bool.TYPE, payload);
    }
}
