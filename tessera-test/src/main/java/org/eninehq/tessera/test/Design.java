package org.eninehq.tessera.test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a test-class method that creates the design for {@link HwTest} methods, and for the
 * {@code Simulator} parameters {@link HwTestExtension} gives plain {@code @Test} methods:
 * {@code @Design Counter dut() { return new Counter(8); }}.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Design {}
