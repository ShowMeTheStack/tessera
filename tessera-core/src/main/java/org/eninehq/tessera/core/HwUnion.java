package org.eninehq.tessera.core;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import org.eninehq.tessera.ir.Expr;
import org.eninehq.tessera.ir.OpKind;
import org.eninehq.tessera.ir.Ops;

/**
 * A tagged union (Spade's enums with payloads): a Java {@code sealed interface} whose
 * permitted subclasses are records of hardware values. The hardware value is a tag
 * followed by the payload of the active variant.
 *
 * <pre>{@code
 * sealed interface Cmd permits Cmd.Nop, Cmd.Write, Cmd.Read {
 *     record Nop() implements Cmd {}
 *     record Write(UInt addr, UInt data) implements Cmd {}
 *     record Read(UInt addr) implements Cmd {}
 * }
 * static final HwUnion.Type<Cmd> CMD = HwUnion.of(Cmd.class,
 *         HwUnion.variant(Cmd.Nop.class),
 *         HwUnion.variant(Cmd.Write.class, UInt.w(8), UInt.w(32)),
 *         HwUnion.variant(Cmd.Read.class, UInt.w(8)));
 *
 * UInt addr = cmd.match(c -> switch (c) {   // javac checks that every variant is handled
 *     case Cmd.Nop n -> Hw.lit(0, 8);
 *     case Cmd.Write(var a, var d) -> a;
 *     case Cmd.Read(var a) -> a;
 * });
 * }</pre>
 */
public final class HwUnion<S> extends Data {

    /** One variant: a record class and the shape of its components. */
    public record Variant<R>(Class<R> cls, Shape<R> shape) {}

    public static <R extends Record> Variant<R> variant(Class<R> cls, Shape<?>... components) {
        return new Variant<>(cls, Bundle.of(cls, components));
    }

    public static final class Type<S> implements HwType<HwUnion<S>> {
        private final Class<S> base;
        private final List<Variant<?>> variants;
        private final int tagWidth, payloadWidth;

        Type(Class<S> base, List<Variant<?>> variants) {
            this.base = base;
            this.variants = List.copyOf(variants);
            this.tagWidth = Math.max(1, 32 - Integer.numberOfLeadingZeros(variants.size() - 1));
            int p = 0;
            for (var v : variants) p = Math.max(p, v.shape().totalWidth());
            this.payloadWidth = p;
        }

        @Override public int width() { return tagWidth + payloadWidth; }
        @Override public boolean signed() { return false; }
        @Override public HwUnion<S> wrap(Init init) { return new HwUnion<>(init, this); }
        @Override public String toString() { return base.getSimpleName(); }

        public Class<S> base() { return base; }
        public List<Variant<?>> variants() { return variants; }
        public int tagWidth() { return tagWidth; }
        public int payloadWidth() { return payloadWidth; }

        int indexOf(Class<?> cls) {
            for (int i = 0; i < variants.size(); i++) if (variants.get(i).cls() == cls) return i;
            throw TesseraException.here(cls.getSimpleName() + " is not a variant of " + base.getSimpleName());
        }

        /** Build a union value from a variant record whose components are hardware values. */
        public HwUnion<S> make(S value) {
            int idx = indexOf(value.getClass());
            var shape = variants.get(idx).shape();
            var leaves = Agg.leaves(value);
            var specs = shape.leaves();
            var parts = new ArrayList<Expr>();
            parts.add(Ops.lit(idx, tagWidth));
            int used = 0;
            for (int i = 0; i < specs.size(); i++) {
                var spec = specs.get(i);
                Expr e = Elab.fit(Elab.read(leaves.get(i)), spec.type().width(), spec.type().signed(),
                        base.getSimpleName() + "." + value.getClass().getSimpleName() + " field", SourceLoc.here());
                if (e.signed()) e = Ops.op(OpKind.CAST, e.width(), false, e);
                parts.add(e);
                used += spec.type().width();
            }
            if (used < payloadWidth) parts.add(Ops.lit(0, payloadWidth - used));
            Expr all = parts.size() == 1 ? parts.getFirst() : new Expr.Op(OpKind.CAT, parts, width(), false);
            return new HwUnion<>(Elab.init(all), this);
        }
    }

    /** The union type of sealed interface {@code base}; the variants' order defines the tags. */
    @SafeVarargs
    public static <S> Type<S> of(Class<S> base, Variant<? extends S>... variants) {
        if (variants.length == 0) throw TesseraException.here("a union needs at least one variant");
        var seen = new java.util.HashSet<Class<?>>();
        for (var v : variants) {
            if (!base.isAssignableFrom(v.cls())) throw TesseraException.here(v.cls().getSimpleName() + " does not implement " + base.getSimpleName());
            if (!seen.add(v.cls())) throw TesseraException.here("variant " + v.cls().getSimpleName() + " listed twice");
        }
        if (base.isSealed()) {
            for (var p : base.getPermittedSubclasses())
                if (!seen.contains(p))
                    throw TesseraException.here("sealed " + base.getSimpleName() + " permits " + p.getSimpleName()
                            + " but no variant was given for it");
        }
        var list = new ArrayList<Variant<?>>(variants.length);
        for (var v : variants) list.add(v);
        return new Type<>(base, list);
    }

    private final Type<S> type;

    HwUnion(Init init, Type<S> type) {
        super(init);
        this.type = type;
    }

    @Override protected Data rewrap(Init init) { return new HwUnion<>(init, type); }

    public Type<S> type() { return type; }

    /** The tag (index of the active variant). */
    public UInt tag() {
        int w = width();
        return UInt.mk(new Expr.Op(OpKind.SLICE, List.of(Elab.read(this)), type.tagWidth, false, w - 1, w - type.tagWidth));
    }

    /** True when the active variant is {@code cls}. */
    public Bool is(Class<? extends S> cls) {
        int idx = type.indexOf(cls);
        return tag().eq(UInt.lit(idx, type.tagWidth));
    }

    /** The payload interpreted as variant {@code i} (meaningful only when that variant is active). */
    @SuppressWarnings("unchecked")
    private S view(int i) {
        var v = type.variants.get(i);
        int vw = v.shape().totalWidth();
        if (vw == 0) return (S) v.shape().assemble(List.of());
        int pw = type.payloadWidth;
        UInt payload = UInt.mk(new Expr.Op(OpKind.SLICE, List.of(Elab.read(this)), vw, false, pw - 1, pw - vw));
        return (S) v.shape().unpack(payload);
    }

    /**
     * A value computed per variant and selected by the tag. {@code f} is called once per
     * variant with that variant's fields; use a {@code switch} with record patterns so javac
     * checks that every variant is handled.
     */
    public <T> T match(Function<S, T> f) {
        int n = type.variants.size();
        var results = new ArrayList<T>(n);
        for (int i = 0; i < n; i++) results.add(f.apply(view(i)));
        T acc = results.getLast();
        for (int i = n - 2; i >= 0; i--) acc = Hw.mux(tag().eq(UInt.lit(i, type.tagWidth)), results.get(i), acc);
        return acc;
    }

    /** Run {@code body} once per variant, under a condition that the variant is active. */
    public void when(Consumer<S> body) {
        WhenChain chain = null;
        for (int i = 0; i < type.variants.size(); i++) {
            final int k = i;
            Bool c = tag().eq(UInt.lit(i, type.tagWidth));
            Runnable r = () -> body.accept(view(k));
            chain = chain == null ? Hw.when(c, r) : chain.elseWhen(c, r);
        }
    }

    /** The raw bits (tag, then payload). */
    public UInt asUInt() {
        return UInt.mk(Ops.op(OpKind.CAST, width(), false, Elab.read(this)));
    }

    public void set(HwUnion<S> value) {
        if (value.type != type && !value.type.variants.equals(type.variants))
            throw TesseraException.here("union type mismatch: " + type + " vs " + value.type);
        Elab.assign(this, value);
    }

    /** Assign a variant directly: {@code cmd.set(new Cmd.Read(addr))}. */
    public void set(S variant) {
        Elab.assign(this, type.make(variant));
    }

    @SuppressWarnings("unchecked")
    public HwUnion<S> named(String name) { return (HwUnion<S>) namedData(name); }
}
