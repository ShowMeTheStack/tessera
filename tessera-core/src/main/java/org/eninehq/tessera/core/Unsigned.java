package org.eninehq.tessera.core;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an {@code int} or {@code long} as unsigned, for stock Java: {@code @Unsigned int x},
 * {@code (@Unsigned int) i}, {@code static @Unsigned long f(...)}. The javac plugin
 * ({@code -Xplugin:TesseraCheck}) rejects silent mixing with signed values and the operators whose
 * Java meaning is signed; {@code -Xplugin:TesseraSyntaxLite} rewrites those operators to the JDK's
 * unsigned methods. Any annotation named {@code Unsigned} is treated the same way.
 */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.TYPE_USE)
public @interface Unsigned {}
