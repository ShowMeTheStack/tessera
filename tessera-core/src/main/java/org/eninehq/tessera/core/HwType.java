package org.eninehq.tessera.core;

import java.util.List;

/** A hardware type: how to build a {@link Data} of a given shape. A single-signal {@link Shape}. */
public interface HwType<T extends Data> extends Shape<T> {
    int width();

    boolean signed();

    /** Wrap an elaborated value as this type. Internal. */
    T wrap(Data.Init init);

    @Override
    default List<Leaf> leaves() {
        return List.of(new Leaf("", this, false));
    }

    @Override
    @SuppressWarnings("unchecked")
    default T assemble(List<? extends Data> leafValues) {
        return (T) leafValues.getFirst();
    }

    @Override
    default int totalWidth() {
        return width();
    }
}
