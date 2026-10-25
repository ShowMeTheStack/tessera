package org.eninehq.tessera.test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * A hardware test. Declare a parameter of type {@link HwSim HwSim&lt;MyModule&gt;} and Tessera
 * elaborates the design, opens a simulator and closes it afterwards:
 * <pre>{@code
 * @HwTest
 * void addsNumbers(HwSim<MulAdd> sim) {
 *     sim.poke(sim.dut().a, 3);
 *     sim.step(2);
 *     assertEquals(17, sim.peek(sim.dut().out));
 * }
 * }</pre>
 * The module is created with its no-argument constructor, or by a method of the test class
 * annotated {@link Design} that returns it.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@Test
@ExtendWith(HwTestExtension.class)
public @interface HwTest {
    /** Write a VCD waveform to {@code build/tessera-waves/<class>.<method>.vcd}. */
    boolean trace() default false;

    /** After the test, replay every recorded input on Verilator and compare all outputs cycle by cycle. */
    boolean crossCheck() default false;

    /** Simulator backend; empty means the default ({@code -Dtessera.sim}). */
    String backend() default "";
}
