package org.eninehq.tessera.core;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks the method that implements a Java operator for this type, for the Tessera javac
 * ({@code jdk25-tessera}): with it {@code a + b} compiles to {@code a.add(b)}, {@code x[7:4]}
 * to {@code x.bits(7, 4)} and {@code {a, b}} to {@code a.cat(b)}. A static method marked
 * {@code @Operator("name")} receives every local variable of the type with its name. Stock
 * javac ignores the annotation.
 */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.METHOD)
public @interface Operator {
    /** Operators: "+", "-", "*", "&", "|", "^", "~", "!", "<<", ">>", "<", "<=", ">", ">=", "[]", "[:]", "{}", "name". */
    String[] value();
}
