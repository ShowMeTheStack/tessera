package org.eninehq.tessera.ir;

import java.math.BigInteger;
import java.util.List;

/**
 * An expression node of the Tessera IR. Expressions form a DAG and are compared by
 * identity: never put {@link Op} or {@link MemRead} nodes in hash-based collections
 * other than identity maps.
 */
public sealed interface Expr permits Expr.Lit, Expr.Ref, Expr.InstRef, Expr.MemRead, Expr.Op {

    int width();

    boolean signed();

    /** A constant. {@code value} is the bit pattern, {@code 0 <= value < 2^width}. */
    record Lit(BigInteger value, int width, boolean signed) implements Expr {
        public Lit {
            if (width < 1) throw new IllegalArgumentException("width must be >= 1");
            if (value.signum() < 0 || value.bitLength() > width)
                throw new IllegalArgumentException("literal " + value + " does not fit in " + width + " bits");
        }

        public long longValue() {
            return value.longValue();
        }
    }

    /** A reference to a signal of the module the expression lives in. */
    record Ref(Signal signal) implements Expr {
        @Override public int width() { return signal.width(); }
        @Override public boolean signed() { return signal.signed(); }
    }

    /** A reference to an output port of a child instance. */
    record InstRef(Instance instance, Signal port) implements Expr {
        @Override public int width() { return port.width(); }
        @Override public boolean signed() { return port.signed(); }
    }

    /** Asynchronous (combinational) memory read. */
    final class MemRead implements Expr {
        private final MemDef mem;
        private final Expr addr;

        public MemRead(MemDef mem, Expr addr) {
            this.mem = mem;
            this.addr = addr;
        }

        public MemDef mem() { return mem; }
        public Expr addr() { return addr; }
        @Override public int width() { return mem.width(); }
        @Override public boolean signed() { return mem.signed(); }
        @Override public String toString() { return mem.name() + "[...]"; }
    }

    /** A primitive operation; see {@link OpKind} for semantics. */
    final class Op implements Expr {
        private final OpKind kind;
        private final List<Expr> args;
        private final int width;
        private final boolean signed;
        private final int p0, p1;

        public Op(OpKind kind, List<Expr> args, int width, boolean signed, int p0, int p1) {
            if (width < 1) throw new IllegalArgumentException(kind + ": width must be >= 1, got " + width);
            this.kind = kind;
            this.args = List.copyOf(args);
            this.width = width;
            this.signed = signed;
            this.p0 = p0;
            this.p1 = p1;
        }

        public Op(OpKind kind, List<Expr> args, int width, boolean signed) {
            this(kind, args, width, signed, 0, 0);
        }

        public OpKind kind() { return kind; }
        public List<Expr> args() { return args; }
        public Expr arg(int i) { return args.get(i); }
        public int p0() { return p0; }
        public int p1() { return p1; }
        @Override public int width() { return width; }
        @Override public boolean signed() { return signed; }
        @Override public String toString() { return kind + "<" + width + ">"; }
    }
}
