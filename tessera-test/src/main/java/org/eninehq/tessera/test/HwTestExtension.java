package org.eninehq.tessera.test;

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.nio.file.Path;
import java.util.function.Supplier;
import org.eninehq.tessera.core.Elaborated;
import org.eninehq.tessera.core.Module;
import org.eninehq.tessera.core.Tessera;
import org.eninehq.tessera.sim.Sim;
import org.eninehq.tessera.sim.Simulator;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;

/**
 * JUnit 5 extension behind {@link HwTest}: creates, injects and closes {@link HwSim}s. With
 * {@code @ExtendWith(HwTestExtension.class)} it also gives plain {@code @Test} methods a {@link Simulator}
 * (the Tessera Studio's testbenches): a fresh one per test, starting from reset, of the design the
 * class's {@link Design} method makes or its {@link TestsDesign} annotation names.
 */
public final class HwTestExtension implements ParameterResolver, AfterEachCallback {

    private static final ExtensionContext.Namespace NS = ExtensionContext.Namespace.create(HwTestExtension.class);

    @Override
    public boolean supportsParameter(ParameterContext pc, ExtensionContext ec) {
        var t = pc.getParameter().getType();
        return t == HwSim.class || t == Simulator.class;
    }

    @Override
    public Object resolveParameter(ParameterContext pc, ExtensionContext ec) {
        Object testInstance = ec.getRequiredTestInstance();
        Class<?> cls = pc.getParameter().getType() == Simulator.class ? designOf(testInstance.getClass()) : null;
        var type = pc.getParameter().getParameterizedType();
        if (cls == null && (!(type instanceof ParameterizedType pt) || !(pt.getActualTypeArguments()[0] instanceof Class<?> c)
                || !Module.class.isAssignableFrom(c)))
            throw new ParameterResolutionException("declare the parameter as HwSim<YourModule>");
        if (cls == null) cls = (Class<?>) ((ParameterizedType) type).getActualTypeArguments()[0];
        Supplier<Module> factory = factory(cls, testInstance);
        Elaborated<Module> e = Tessera.elaborate(factory);
        var ann = ec.getRequiredTestMethod().getAnnotation(HwTest.class);
        Sim.Backend backend = ann == null || ann.backend().isEmpty() ? Sim.defaultBackend()
                : Sim.Backend.valueOf(ann.backend().toUpperCase());
        var sim = new HwSim<>(e, Sim.create(e, backend));
        if (ann != null && ann.trace()) {
            String name = ec.getRequiredTestClass().getSimpleName() + "." + ec.getRequiredTestMethod().getName() + ".vcd";
            sim.trace(Path.of("build", "tessera-waves", name));
        }
        // a new simulator's registers are 0, not their reset values: a Simulator test starts the way
        // the Studio starts a run, with every reset input held for two cycles
        if (pc.getParameter().getType() == Simulator.class) resetAll(sim, e);
        ec.getStore(NS).put("sim", sim);
        return sim;
    }

    /** Hold every reset input ({@code reset}, {@code <domain>_reset}) for two cycles of all clocks, then release them. */
    static void resetAll(Simulator sim, Elaborated<?> e) {
        var resets = new java.util.ArrayList<String>();
        for (var p : e.circuit().top().ports())
            if (p.kind() == org.eninehq.tessera.ir.Signal.Kind.INPUT && p.isClockOrReset() && (p.name().equals("reset") || p.name().endsWith("_reset")))
                resets.add(p.name());
        if (resets.isEmpty()) return;
        for (String r : resets) sim.poke(r, 1);
        for (int i = 0; i < 2; i++) sim.stepClocks(sim.clocks());
        for (String r : resets) sim.poke(r, 0);
    }

    /** The design a test class simulates through {@link Simulator} parameters: its {@link Design} method's, else {@link TestsDesign}'s. */
    private static Class<?> designOf(Class<?> test) {
        for (Class<?> c = test; c != null; c = c.getSuperclass())
            for (Method m : c.getDeclaredMethods())
                if (m.isAnnotationPresent(Design.class) && m.getParameterCount() == 0 && Module.class.isAssignableFrom(m.getReturnType()))
                    return m.getReturnType();
        var ann = test.getAnnotation(TestsDesign.class);
        if (ann != null) return ann.value();
        throw new ParameterResolutionException(test.getSimpleName() + " does not say which design its Simulator runs: add a @Design method"
                + " that creates it, such as @Design Counter dut() { return new Counter(); }, or annotate the class @TestsDesign(Counter.class)");
    }

    private static Supplier<Module> factory(Class<?> cls, Object test) {
        for (Class<?> c = test.getClass(); c != null; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.isAnnotationPresent(Design.class) && cls.isAssignableFrom(m.getReturnType()) && m.getParameterCount() == 0) {
                    m.setAccessible(true);
                    return () -> {
                        try {
                            return (Module) m.invoke(test);
                        } catch (java.lang.reflect.InvocationTargetException ex) {
                            if (ex.getCause() instanceof RuntimeException re) throw re;
                            throw new IllegalStateException(ex.getCause());
                        } catch (ReflectiveOperationException ex) {
                            throw new IllegalStateException(ex);
                        }
                    };
                }
            }
        }
        try {
            var ctor = cls.getDeclaredConstructor();
            ctor.setAccessible(true);
            return () -> {
                try {
                    return (Module) ctor.newInstance();
                } catch (java.lang.reflect.InvocationTargetException ex) {
                    if (ex.getCause() instanceof RuntimeException re) throw re;
                    throw new IllegalStateException(ex.getCause());
                } catch (ReflectiveOperationException ex) {
                    throw new IllegalStateException(ex);
                }
            };
        } catch (NoSuchMethodException ex) {
            throw new ParameterResolutionException(cls.getSimpleName()
                    + " has no no-argument constructor; add a @Design method to the test class that creates it");
        }
    }

    @Override
    public void afterEach(ExtensionContext ec) throws Exception {
        HwSim<?> sim = ec.getStore(NS).remove("sim", HwSim.class);
        if (sim == null) return;
        sim.close();
        var ann = ec.getRequiredTestMethod().getAnnotation(HwTest.class);
        if (ann != null && ann.crossCheck() && ec.getExecutionException().isEmpty() && Verilator.available()) {
            java.util.List<java.math.BigInteger[]> rec = sim.recorded();
            if (rec == null)
                throw new AssertionError("crossCheck needs a test that only pokes inputs and calls step()");
            String name = "hwtest-" + ec.getRequiredTestClass().getSimpleName() + "-" + ec.getRequiredTestMethod().getName();
            Verilator.crossCheckBig(sim.design(), name, rec, Sim.defaultBackend());
        }
    }
}
