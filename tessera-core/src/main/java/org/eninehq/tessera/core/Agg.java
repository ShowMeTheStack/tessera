package org.eninehq.tessera.core;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Structural helpers for aggregate values (records of hardware values, {@link Vec}s, and
 * plain {@link Data}). Record components that are not hardware (ints, strings, ...) are
 * carried along unchanged.
 */
final class Agg {
    private Agg() {}

    /** All hardware leaves of {@code v}, in declaration order. */
    static List<Data> leaves(Object v) {
        var out = new ArrayList<Data>();
        collect(v, out, 0);
        return out;
    }

    static boolean isHardware(Object v) {
        return v instanceof Data || v instanceof Vec<?> || v instanceof Record;
    }

    private static void collect(Object v, List<Data> out, int depth) {
        if (depth > 32) throw TesseraException.here("aggregate nested too deeply (cyclic?)");
        switch (v) {
            case null -> throw TesseraException.here("null inside a hardware aggregate");
            case Data d -> out.add(d);
            case Vec<?> vec -> {
                for (var x : vec) collect(x, out, depth + 1);
            }
            case Record r -> {
                for (var rc : r.getClass().getRecordComponents()) {
                    Object c = component(r, rc);
                    if (isHardware(c)) collect(c, out, depth + 1);
                }
            }
            default -> throw TesseraException.here(v.getClass().getSimpleName() + " is not a hardware value");
        }
    }

    private static Object component(Record r, java.lang.reflect.RecordComponent rc) {
        try {
            var acc = rc.getAccessor();
            acc.setAccessible(true);
            return acc.invoke(r);
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw TesseraException.here("cannot read component " + rc.getName() + " of record "
                    + r.getClass().getName() + " (" + e.getMessage() + ")");
        }
    }

    /** A value shaped like {@code template} whose leaves are taken, in order, from {@code leaves}. */
    @SuppressWarnings("unchecked")
    static <T> T rebuild(T template, List<? extends Data> leaves) {
        var it = leaves.iterator();
        T out = (T) rebuild(template, it);
        if (it.hasNext()) throw new IllegalStateException("too many leaves");
        return out;
    }

    private static Object rebuild(Object t, Iterator<? extends Data> it) {
        return switch (t) {
            case Data d -> it.next();
            case Vec<?> vec -> {
                var items = new ArrayList<Object>(vec.size());
                for (var x : vec) items.add(rebuild(x, it));
                yield Vec.of(items);
            }
            case Record r -> {
                var rcs = r.getClass().getRecordComponents();
                Object[] args = new Object[rcs.length];
                for (int i = 0; i < rcs.length; i++) {
                    Object c = component(r, rcs[i]);
                    args[i] = isHardware(c) ? rebuild(c, it) : c;
                }
                yield Bundle.construct(Bundle.canonical(r.getClass()), args);
            }
            default -> throw TesseraException.here(t.getClass().getSimpleName() + " is not a hardware value");
        };
    }

    /** Check that two aggregates have the same structure and leaf widths. */
    static void checkSameShape(Object a, Object b, String what) {
        if (a.getClass() != b.getClass() && !(a instanceof Data && b instanceof Data))
            throw TesseraException.here(what + ": " + a.getClass().getSimpleName() + " vs " + b.getClass().getSimpleName());
        var la = leaves(a);
        var lb = leaves(b);
        if (la.size() != lb.size())
            throw TesseraException.here(what + ": aggregates have " + la.size() + " and " + lb.size() + " signals");
    }
}
