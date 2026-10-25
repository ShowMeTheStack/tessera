package org.eninehq.tessera.pipeline;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Declares the latency (pipeline depth) of a module; checked when it is instantiated with {@link Pipeline#inst}. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Latency {
    int value();
}
