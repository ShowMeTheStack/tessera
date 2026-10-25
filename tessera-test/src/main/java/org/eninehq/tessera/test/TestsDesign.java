package org.eninehq.tessera.test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.eninehq.tessera.core.Module;

/**
 * Names the design that a test class's {@link org.eninehq.tessera.sim.Simulator Simulator} parameters
 * simulate, when it has a no-argument constructor: {@code @TestsDesign(Counter.class)}. A
 * {@link Design} method in the class says the same and can pass constructor arguments; it wins.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface TestsDesign {
    Class<? extends Module> value();
}
