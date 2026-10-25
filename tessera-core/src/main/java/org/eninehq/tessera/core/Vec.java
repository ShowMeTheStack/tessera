package org.eninehq.tessera.core;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.IntFunction;

/**
 * A fixed-length array of hardware values. Elements can be read and written with a
 * constant index ({@link #get(int)}) or a hardware index ({@link #get(UInt)},
 * {@link #set(UInt, Object)}), which builds a multiplexer or a set of enables.
 */
public final class Vec<T> implements Iterable<T> {
    private final List<T> items;

    private Vec(List<T> items) {
        this.items = List.copyOf(items);
    }

    /** The shape of {@code n} elements of shape {@code elem}. */
    public static <T> Shape<Vec<T>> of(int n, Shape<T> elem) {
        if (n < 1) throw TesseraException.here("Vec length must be >= 1, got " + n);
        return new Shape<>() {
            @Override public List<Leaf> leaves() {
                var out = new ArrayList<Leaf>();
                for (int i = 0; i < n; i++)
                    for (var l : elem.leaves()) out.add(new Leaf("_" + i + l.path(), l.type(), l.flipped()));
                return out;
            }

            @Override public Vec<T> assemble(List<? extends Data> leafValues) {
                int per = elem.leaves().size();
                var items = new ArrayList<T>(n);
                for (int i = 0; i < n; i++) items.add(elem.assemble(leafValues.subList(i * per, (i + 1) * per)));
                return new Vec<>(items);
            }

            @Override public String toString() { return "Vec<" + elem + ">[" + n + "]"; }
        };
    }

    /** A Vec of existing values. */
    public static <T> Vec<T> of(List<T> items) {
        if (items.isEmpty()) throw TesseraException.here("Vec must not be empty");
        return new Vec<>(items);
    }

    @SafeVarargs
    public static <T> Vec<T> of(T... items) {
        var list = new ArrayList<T>(items.length);
        for (var x : items) list.add(x);
        return of(list);
    }

    /** {@code n} values computed by {@code f}. */
    public static <T> Vec<T> tabulate(int n, IntFunction<T> f) {
        var items = new ArrayList<T>(n);
        for (int i = 0; i < n; i++) items.add(f.apply(i));
        return of(items);
    }

    public int size() { return items.size(); }

    public T get(int i) {
        if (i < 0 || i >= items.size()) throw TesseraException.here("index " + i + " out of range for Vec of " + items.size());
        return items.get(i);
    }

    /** Dynamic read: a multiplexer tree. Indexes past the end read the last element. */
    public T get(UInt idx) {
        return Hw.index(idx, items);
    }

    /** Dynamic write: element {@code i} gets {@code value} when {@code idx == i}. */
    public void set(UInt idx, T value) {
        for (int i = 0; i < items.size(); i++) {
            final int k = i;
            if (Long.numberOfLeadingZeros(i) < 64 - idx.width()) break;
            Hw.when(idx.eq(UInt.lit(i, idx.width())), () -> Hw.assign(items.get(k), value));
        }
    }

    public List<T> asList() { return items; }

    @Override public Iterator<T> iterator() { return items.iterator(); }

    @Override public String toString() { return "Vec" + items; }
}
