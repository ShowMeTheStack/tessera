package org.eninehq.tessera.core;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The pipeline stage a value belongs to, checked at compile time by the Tessera javac plugin:
 * using a {@code @Stage(3)} value in code before the third {@code stage()} call is an error.
 */
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.TYPE_USE, ElementType.PARAMETER, ElementType.FIELD, ElementType.LOCAL_VARIABLE})
public @interface Stage {
    int value();
}
