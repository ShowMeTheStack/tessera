package org.eninehq.tessera.core;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A combinational function: it may only compute values, never create registers, memories,
 * instances, ports or assignments. Checked at compile time by the Tessera javac plugin; use
 * {@link Hw#fn} for the same check at elaboration time.
 */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.METHOD)
public @interface Fn {}
