package org.eninehq.tessera.core;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The width of a hardware value, for compile-time checking by the Tessera javac plugin
 * ({@code -Xplugin:TesseraCheck}): {@code @Width(32) UInt a}. Elaboration remains the final
 * authority; the annotation lets width errors show up when the code is compiled.
 */
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.TYPE_USE, ElementType.PARAMETER, ElementType.FIELD, ElementType.LOCAL_VARIABLE, ElementType.METHOD})
public @interface Width {
    int value();
}
