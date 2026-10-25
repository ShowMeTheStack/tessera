package org.eninehq.tessera.core;

import java.util.ArrayList;
import java.util.List;
import org.eninehq.tessera.ir.Expr;
import org.eninehq.tessera.ir.OpKind;
import org.eninehq.tessera.ir.Ops;

/**
 * The shape of a hardware value: a single signal ({@link HwType}), a bundle (a Java record,
 * see {@link Bundle}) or a {@link Vec}. Shapes are flattened into leaves, each one signal.
 *
 * @param <T> the Java type of values of this shape
 */
public interface Shape<T> {

    /** One signal of a shape. {@code path} is appended to the value's name; {@code flipped} reverses its direction. */
    record Leaf(String path, HwType<?> type, boolean flipped) {}

    /** The leaves, most significant first when packed. */
    List<Leaf> leaves();

    /** Build a value from one hardware value per leaf, in {@link #leaves()} order. */
    T assemble(List<? extends Data> leafValues);

    /** Total width of all leaves. */
    default int totalWidth() {
        int w = 0;
        for (var l : leaves()) w += l.type().width();
        return w;
    }

    /** The same shape with every direction reversed (Spade's {@code inv}, Chisel's {@code Flipped}). */
    static <T> Shape<T> flip(Shape<T> s) {
        return new Shape<>() {
            @Override public List<Leaf> leaves() {
                var out = new ArrayList<Leaf>();
                for (var l : s.leaves()) out.add(new Leaf(l.path(), l.type(), !l.flipped()));
                return out;
            }
            @Override public T assemble(List<? extends Data> leafValues) { return s.assemble(leafValues); }
            @Override public String toString() { return "flip(" + s + ")"; }
        };
    }

    /** Reinterpret packed bits as a value of this shape (first leaf in the most significant bits). */
    default T unpack(UInt bits) {
        if (bits.width() != totalWidth())
            throw TesseraException.here("cannot unpack " + bits.width() + " bits into " + this + " (" + totalWidth() + " bits)");
        Expr all = Elab.read(bits);
        int hi = totalWidth() - 1;
        var vals = new ArrayList<Data>();
        for (var l : leaves()) {
            int w = l.type().width();
            Expr e = w == bits.width() ? all : new Expr.Op(OpKind.SLICE, List.of(all), w, false, hi, hi - w + 1);
            if (l.type().signed()) e = Ops.op(OpKind.CAST, w, true, e);
            vals.add(l.type().wrap(Elab.init(e)));
            hi -= w;
        }
        return assemble(vals);
    }
}
