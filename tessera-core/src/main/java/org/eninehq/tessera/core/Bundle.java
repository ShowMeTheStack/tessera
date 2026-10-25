package org.eninehq.tessera.core;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;

/**
 * Bundles: Java records whose components are hardware values.
 *
 * <pre>{@code
 * record Req(UInt addr, UInt data, Bool write) {}
 * static final Shape<Req> REQ = Bundle.of(Req.class, UInt.w(32), UInt.w(32), Bool.TYPE);
 *
 * Req in = input("req", REQ);         // ports req_addr, req_data, req_write
 * }</pre>
 *
 * Components may be other bundles or {@link Vec}s; wrap a component's shape in
 * {@link Shape#flip} to reverse its direction (for example a {@code ready} signal).
 */
public final class Bundle {
    private Bundle() {}

    /** The shape of record {@code cls}, with one shape per record component, in declaration order. */
    public static <R extends Record> Shape<R> of(Class<R> cls, Shape<?>... components) {
        RecordComponent[] rcs = cls.getRecordComponents();
        if (rcs == null) throw TesseraException.here(cls.getName() + " is not a record");
        if (rcs.length != components.length)
            throw TesseraException.here("record " + cls.getSimpleName() + " has " + rcs.length + " components but "
                    + components.length + " shapes were given");
        var ctor = canonical(cls);
        List<Shape<?>> comps = List.of(components);
        return new Shape<>() {
            @Override public List<Leaf> leaves() {
                var out = new ArrayList<Leaf>();
                for (int i = 0; i < rcs.length; i++)
                    for (var l : comps.get(i).leaves())
                        out.add(new Leaf("_" + rcs[i].getName() + l.path(), l.type(), l.flipped()));
                return out;
            }

            @Override public R assemble(List<? extends Data> leafValues) {
                Object[] args = new Object[rcs.length];
                int k = 0;
                for (int i = 0; i < rcs.length; i++) {
                    int n = comps.get(i).leaves().size();
                    args[i] = assembleAny(comps.get(i), leafValues.subList(k, k + n));
                    k += n;
                }
                return construct(ctor, args);
            }

            @Override public String toString() { return cls.getSimpleName(); }
        };
    }

    @SuppressWarnings("unchecked")
    private static <T> T assembleAny(Shape<T> s, List<? extends Data> leaves) {
        return s.assemble(leaves);
    }

    @SuppressWarnings("unchecked")
    static <R> Constructor<R> canonical(Class<R> cls) {
        var rcs = cls.getRecordComponents();
        Class<?>[] types = new Class<?>[rcs.length];
        for (int i = 0; i < rcs.length; i++) types[i] = rcs[i].getType();
        try {
            Constructor<R> c = cls.getDeclaredConstructor(types);
            c.setAccessible(true);
            return c;
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw TesseraException.here("cannot access the constructor of record " + cls.getName()
                    + "; make the record accessible to Tessera (" + e.getMessage() + ")");
        }
    }

    static <R> R construct(Constructor<R> ctor, Object[] args) {
        try {
            return ctor.newInstance(args);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException re) throw re;
            throw new IllegalStateException(e.getCause());
        } catch (IllegalArgumentException e) {
            throw TesseraException.here("record " + ctor.getDeclaringClass().getSimpleName()
                    + ": a component shape does not match the component's Java type");
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
