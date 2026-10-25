package org.eninehq.tessera.babylon;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import jdk.incubator.code.Block;
import jdk.incubator.code.Body;
import jdk.incubator.code.Op;
import jdk.incubator.code.Value;
import jdk.incubator.code.dialect.core.CoreOp;
import jdk.incubator.code.dialect.java.JavaOp;
import jdk.incubator.code.dialect.java.JavaType;
import org.eninehq.tessera.core.Bool;
import org.eninehq.tessera.core.Data;
import org.eninehq.tessera.core.Hw;
import org.eninehq.tessera.core.HwEnum;
import org.eninehq.tessera.core.SInt;
import org.eninehq.tessera.core.SourceLoc;
import org.eninehq.tessera.core.TesseraException;
import org.eninehq.tessera.core.UInt;

/**
 * Front end B: plain Java methods become hardware. A method annotated {@code @Reflect}
 * (Babylon code reflection) and written over {@code int}, {@code long}, {@code boolean},
 * enums and records is interpreted symbolically while a module is elaborated:
 * <ul>
 *   <li>arithmetic keeps exact Java semantics (32-bit {@code int} wraps, {@code >>} is
 *       arithmetic, comparisons are signed), so the same method is also its own software
 *       model;</li>
 *   <li>values known at elaboration time are computed in Java: {@code if}s on them pick a
 *       branch and loops with constant bounds unroll (partial evaluation);</li>
 *   <li>{@code if}, {@code switch}, {@code ?:}, {@code &&} and early {@code return} on hardware
 *       values become multiplexers; local variables become wires named after them;</li>
 *   <li>calls to other {@code @Reflect} methods are inlined.</li>
 * </ul>
 * Not supported on hardware values: division, loops whose condition depends on hardware,
 * floating point, arrays, objects other than records and enums.
 *
 * <pre>{@code
 * @Reflect static int alu(Op op, int a, int b) {
 *     return switch (op) { case ADD -> a + b; case SUB -> a - b; case SLT -> a < b ? 1 : 0; };
 * }
 * // inside a module constructor:
 * UInt y = (UInt) JavaHw.of(Alus.class, "alu").apply(op, a, b);
 * }</pre>
 */
public final class JavaHw {

    private static final Map<Method, CoreOp.FuncOp> MODELS = new ConcurrentHashMap<>();
    private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();

    private final Method method;
    private final CoreOp.FuncOp model;

    private JavaHw(Method method, CoreOp.FuncOp model) {
        this.method = method;
        this.model = model;
    }

    /** The static method {@code name} of {@code owner} (must be {@code @Reflect}; overloads are not supported). */
    public static JavaHw of(Class<?> owner, String name) {
        Method found = null;
        for (var m : owner.getDeclaredMethods()) {
            if (!m.getName().equals(name)) continue;
            if (found != null) throw TesseraException.here(owner.getSimpleName() + "." + name + " is overloaded");
            found = m;
        }
        if (found == null) throw TesseraException.here("no method " + owner.getSimpleName() + "." + name);
        return of(found);
    }

    public static JavaHw of(Method m) {
        if (!Modifier.isStatic(m.getModifiers())) throw TesseraException.here(m + " must be static");
        return new JavaHw(m, model(m));
    }

    static CoreOp.FuncOp model(Method m) {
        return MODELS.computeIfAbsent(m, k -> Op.ofMethod(k).orElseThrow(() -> TesseraException.here(
                k.getDeclaringClass().getSimpleName() + "." + k.getName() + " has no code model; annotate it with @Reflect")));
    }

    /** The method's code model, as text (for debugging). */
    public String codeModel() {
        return model.toText();
    }

    /**
     * Elaborate the method on hardware arguments ({@link Data}, records of them) and Java
     * constants (which are folded). Returns a {@link Data} (UInt for int/long, Bool for
     * boolean, HwEnum for enums), a record of them, or, for a record with primitive
     * components, a {@code Map} from component name to hardware value.
     */
    public Object apply(Object... args) {
        if (args.length != method.getParameterCount())
            throw TesseraException.here(method.getName() + " takes " + method.getParameterCount() + " arguments, got " + args.length);
        var in = new ArrayList<SV>();
        for (int i = 0; i < args.length; i++) in.add(fromJava(args[i], method.getParameterTypes()[i]));
        SV r = new Interp().call(model, in, 0);
        return toJava(r, method.getReturnType());
    }

    // ================================================================ values

    /** A symbolic value: a Java constant, a hardware value of a Java type, or a record of symbolic values. */
    sealed interface SV permits K, H, R, VarRef, Interp.Yield {}

    /** A value known at elaboration time. */
    record K(Object v) implements SV {}

    /** A hardware value; {@code type} is the Java type it stands for (int.class, boolean.class, an enum class, ...). */
    record H(Data d, Class<?> type) implements SV {}

    /** A record whose components may be hardware. */
    record R(Class<?> cls, List<SV> comps) implements SV {}

    /** A local variable (the result of a var op), used as an SSA value. */
    record VarRef(Value var, String name) implements SV {}

    static int widthOf(Class<?> t) {
        if (t == int.class) return 32;
        if (t == long.class) return 64;
        if (t == short.class || t == char.class) return 16;
        if (t == byte.class) return 8;
        if (t == boolean.class) return 1;
        throw TesseraException.here("type " + t.getName() + " has no hardware width");
    }

    static boolean isPrim(Class<?> t) {
        return t == int.class || t == long.class || t == short.class || t == char.class || t == byte.class;
    }

    static long mask(int w) {
        return w >= 64 ? -1L : (1L << w) - 1;
    }

    static SV fromJava(Object a, Class<?> type) {
        if (a instanceof Data d) {
            if (type == boolean.class) {
                if (d.width() != 1) throw TesseraException.here("boolean parameter needs a 1-bit value, got " + d.width() + " bits");
                return new H(d instanceof Bool ? d : ((UInt) d).bit(0), boolean.class);
            }
            if (type.isEnum()) return new H(d, type);
            if (isPrim(type)) {
                int w = widthOf(type);
                UInt u = d instanceof SInt s ? s.asUInt() : (UInt) d;
                if (u.width() > w) throw TesseraException.here("a " + u.width() + "-bit value cannot be a " + type + " parameter");
                if (u.width() < w) u = (d instanceof SInt s ? s.sext(w).asUInt() : u.zext(w));
                return new H(u, type);
            }
            throw TesseraException.here("hardware value passed for parameter of type " + type.getSimpleName());
        }
        if (a instanceof Record r && !allConstant(r)) {
            var comps = new ArrayList<SV>();
            for (var rc : r.getClass().getRecordComponents()) comps.add(fromJava(component(r, rc), rc.getType()));
            return new R(r.getClass(), comps);
        }
        return new K(a);
    }

    static boolean allConstant(Record r) {
        for (var rc : r.getClass().getRecordComponents()) {
            Object c = component(r, rc);
            if (c instanceof Data) return false;
            if (c instanceof Record rr && !allConstant(rr)) return false;
        }
        return true;
    }

    static Object component(Record r, RecordComponent rc) {
        try {
            var acc = rc.getAccessor();
            acc.setAccessible(true);
            return acc.invoke(r);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    Object toJava(SV v, Class<?> type) {
        return switch (v) {
            case K k -> type == void.class ? null : lift(k, type).d();
            case H h -> h.d();
            case R r -> {
                var rcs = r.cls().getRecordComponents();
                boolean hwRecord = true;
                for (var rc : rcs) hwRecord &= Data.class.isAssignableFrom(rc.getType()) || rc.getType().isRecord();
                if (!hwRecord) {
                    // a record of primitives (the software view): return its fields as hardware values by name
                    var m = new java.util.LinkedHashMap<String, Object>();
                    for (int i = 0; i < rcs.length; i++) m.put(rcs[i].getName(), toJava(r.comps().get(i), rcs[i].getType()));
                    yield m;
                }
                Object[] args = new Object[rcs.length];
                for (int i = 0; i < args.length; i++) args[i] = toJava(r.comps().get(i), rcs[i].getType());
                try {
                    var ctor = r.cls().getDeclaredConstructors()[0];
                    ctor.setAccessible(true);
                    yield ctor.newInstance(args);
                } catch (ReflectiveOperationException | IllegalArgumentException e) {
                    throw TesseraException.here("cannot build record " + r.cls().getSimpleName() + ": " + e);
                }
            }
            case VarRef vr -> throw new IllegalStateException();
            case Interp.Yield y -> throw new IllegalStateException();
        };
    }

    /** A hardware literal for constant {@code k} of Java type {@code type}. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static H lift(K k, Class<?> type) {
        Object v = k.v();
        if (type == boolean.class || v instanceof Boolean) return new H(Bool.of((Boolean) v), boolean.class);
        if (v instanceof Enum<?> e) return new H(HwEnum.lit((Enum) e), e.getDeclaringClass());
        if (v instanceof Number || v instanceof Character) {
            long x = v instanceof Character c ? c : ((Number) v).longValue();
            Class<?> t = isPrim(type) ? type : (v instanceof Long ? long.class : int.class);
            int w = widthOf(t);
            return new H(UInt.lit(x & mask(w), w), t);
        }
        throw TesseraException.here("constant " + v + " has no hardware representation");
    }

    // ================================================================ interpreter

    /**
     * Control state: variables plus flags for returns, breaks and continues (hardware or
     * constant). {@code vars} holds the true (masked) value of each variable; {@code spec} its
     * value on the path that is still running, which keeps loop counters constant after a
     * hardware-dependent {@code break} so the loop can still be unrolled.
     */
    static final class State {
        Map<Value, SV> vars = new HashMap<>();
        Map<Value, SV> spec = new HashMap<>();
        SV done = new K(false), broken = new K(false), continued = new K(false);
        SV ret;

        State copy() {
            var s = new State();
            s.vars = new HashMap<>(vars);
            s.spec = new HashMap<>(spec);
            s.done = done;
            s.broken = broken;
            s.continued = continued;
            s.ret = ret;
            return s;
        }
    }

    private final class Interp {
        private final Map<Value, SV> vals = new HashMap<>();
        /** Entry blocks of loop bodies: a continue at their end is just the normal end of an iteration. */
        private final java.util.Set<Block> loopBodies = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        private SourceLoc where = SourceLoc.UNKNOWN;
        private String file = "?";

        SV call(CoreOp.FuncOp f, List<SV> args, int depth) {
            if (depth > 64) throw TesseraException.here("call depth over 64 (recursion is not supported)");
            String prev = file;
            var loc = f.location();
            if (loc != null && loc.sourceRef() != null) file = loc.sourceRef().substring(loc.sourceRef().lastIndexOf('/') + 1);
            var entry = f.body().entryBlock();
            var params = entry.parameters();
            for (int i = 0; i < params.size(); i++) vals.put(params.get(i), args.get(i));
            var st = new State();
            block(entry, st);
            file = prev;
            if (st.ret == null) return new K(null);
            return st.ret;
        }

        TesseraException err(Op op, String msg) {
            var l = op.location();
            String at = l == null ? "?" : file + ":" + l.line();
            return new TesseraException(l == null ? where : new SourceLoc("?", "?", file, l.line()), msg + " (at " + at + ")");
        }

        SV val(Value v) {
            SV s = vals.get(v);
            if (s == null) throw new IllegalStateException("unbound value " + v);
            return s;
        }

        /** Run a block; returns the yielded value (or null). */
        SV block(Block b, State st) {
            for (Op op : b.ops()) {
                SV r = op(op, st);
                if (r instanceof Yield y) return y.v;
                if (r != null) vals.put(op.result(), r);
            }
            return null;
        }

        SV body(Body body, State st, List<SV> args) {
            var entry = body.entryBlock();
            for (int i = 0; i < Math.min(args.size(), entry.parameters().size()); i++) vals.put(entry.parameters().get(i), args.get(i));
            if (body.blocks().size() != 1) throw new IllegalStateException("unexpected unstructured body");
            return block(entry, st);
        }

        /** Marker for yields (not stored as values). */
        record Yield(SV v) implements SV {}

        // ------------------------------------------------------------ flags

        SV alive(State st) {
            return not(or(or(st.done, st.broken), st.continued));
        }

        boolean isTrue(SV s) {
            return s instanceof K k && Boolean.TRUE.equals(k.v());
        }

        boolean isFalse(SV s) {
            return s instanceof K k && Boolean.FALSE.equals(k.v());
        }

        Bool bool(SV s) {
            return switch (s) {
                case K k -> Bool.of((Boolean) k.v());
                case H h -> (Bool) h.d();
                default -> throw new IllegalStateException("not a boolean: " + s);
            };
        }

        SV not(SV a) {
            if (a instanceof K k) return new K(!(Boolean) k.v());
            return new H(bool(a).not(), boolean.class);
        }

        SV and(SV a, SV b) {
            if (isFalse(a) || isFalse(b)) return new K(false);
            if (isTrue(a)) return b;
            if (isTrue(b)) return a;
            return new H(bool(a).and(bool(b)), boolean.class);
        }

        SV or(SV a, SV b) {
            if (isTrue(a) || isTrue(b)) return new K(true);
            if (isFalse(a)) return b;
            if (isFalse(b)) return a;
            return new H(bool(a).or(bool(b)), boolean.class);
        }

        /** {@code c ? a : b} on symbolic values. */
        SV mux(SV c, SV a, SV b) {
            if (isTrue(c)) return a;
            if (isFalse(c)) return b;
            if (a == b) return a;
            if (a instanceof K ka && b instanceof K kb && java.util.Objects.equals(ka.v(), kb.v())) return a;
            if (a == null || b == null) return a == null ? b : a;
            if (a instanceof R ra && b instanceof R rb) {
                var comps = new ArrayList<SV>();
                for (int i = 0; i < ra.comps().size(); i++) comps.add(mux(c, ra.comps().get(i), rb.comps().get(i)));
                return new R(ra.cls(), comps);
            }
            Class<?> t = a instanceof H ha ? ha.type() : b instanceof H hb ? hb.type() : typeOfConst((K) a);
            H ha = hw(a, t), hb = hw(b, t);
            return new H(Hw.mux(bool(c), ha.d(), hb.d()), t);
        }

        Class<?> typeOfConst(K k) {
            Object v = k.v();
            if (v instanceof Boolean) return boolean.class;
            if (v instanceof Long) return long.class;
            if (v instanceof Enum<?> e) return e.getDeclaringClass();
            return int.class;
        }

        H hw(SV s, Class<?> t) {
            return switch (s) {
                case H h -> h;
                case K k -> lift(k, t);
                default -> throw new IllegalStateException("cannot make hardware from " + s);
            };
        }

        void merge(State into, SV c, State t, State e) {
            var keys = new java.util.LinkedHashSet<Value>(t.vars.keySet());
            keys.addAll(e.vars.keySet());
            for (var k : keys) {
                SV a = t.vars.get(k), b = e.vars.get(k);
                into.vars.put(k, mux(c, a, b));
            }
            SV aliveT = alive(t), aliveE = alive(e);
            var specKeys = new java.util.LinkedHashSet<Value>(t.spec.keySet());
            specKeys.addAll(e.spec.keySet());
            into.spec = new HashMap<>();
            for (var k : specKeys) {
                SV a = t.spec.getOrDefault(k, t.vars.get(k)), b = e.spec.getOrDefault(k, e.vars.get(k));
                SV m = isFalse(aliveT) ? b : isFalse(aliveE) ? a : mux(c, a, b);
                if (m != into.vars.get(k)) into.spec.put(k, m);
            }
            into.done = mux(c, t.done, e.done);
            into.broken = mux(c, t.broken, e.broken);
            into.continued = mux(c, t.continued, e.continued);
            into.ret = mux(c, t.ret, e.ret);
        }

        // ------------------------------------------------------------ ops

        SV op(Op op, State st) {
            return switch (op) {
                case CoreOp.ConstantOp c -> new K(c.value());
                case CoreOp.VarOp v -> {
                    var ref = new VarRef(v.result(), v.varName());
                    if (!v.isUninitialized()) st.vars.put(v.result(), name(val(v.initOperand()), v.varName()));
                    yield ref;
                }
                case CoreOp.VarAccessOp.VarLoadOp l -> {
                    var ref = (VarRef) val(l.varOperand());
                    SV x = st.spec.getOrDefault(ref.var(), st.vars.get(ref.var()));
                    if (x == null) throw err(op, "variable '" + ref.name() + "' may be used before it is assigned");
                    yield x;
                }
                case CoreOp.VarAccessOp.VarStoreOp s -> {
                    var ref = (VarRef) val(s.varOperand());
                    SV nv = name(val(s.storeOperand()), ref.name());
                    SV alive = alive(st);
                    if (isTrue(alive)) {
                        st.vars.put(ref.var(), nv);
                        st.spec.remove(ref.var());
                    } else if (!isFalse(alive)) {
                        SV old = st.vars.get(ref.var());
                        st.vars.put(ref.var(), old == null ? nv : name(mux(alive, nv, old), ref.name()));
                        st.spec.put(ref.var(), nv);
                    }
                    yield null;
                }
                case CoreOp.ReturnOp r -> {
                    SV v = r.returnValue() == null ? new K(null) : val(r.returnValue());
                    SV alive = alive(st);
                    st.ret = st.ret == null ? v : mux(alive, v, st.ret);
                    st.done = or(st.done, alive);
                    yield null;
                }
                case CoreOp.YieldOp y -> new Yield(y.yieldValue() == null ? null : val(y.yieldValue()));
                case JavaOp.YieldOp y -> new Yield(y.yieldOperand() == null ? null : val(y.yieldOperand()));
                case JavaOp.ContinueOp c -> {
                    if (c.labelOperand() != null) throw err(op, "labelled continue is not supported");
                    boolean endOfBody = loopBodies.contains(c.ancestorBlock()) && c.ancestorBlock().ops().getLast() == c;
                    if (!endOfBody) st.continued = or(st.continued, alive(st));
                    yield null;
                }
                // unreachable paths (such as the synthetic default of an exhaustive switch) are don't-cares
                case JavaOp.ThrowOp t -> new Yield(null);
                case JavaOp.BreakOp b -> {
                    if (b.labelOperand() != null) throw err(op, "labelled break is not supported");
                    st.broken = or(st.broken, alive(st));
                    yield null;
                }
                case JavaOp.IfOp i -> {
                    ifChain(i.bodies(), 0, st);
                    yield null;
                }
                case JavaOp.BlockOp b -> {
                    body(b.bodies().getFirst(), st, List.of());
                    yield null;
                }
                case JavaOp.ConditionalExpressionOp c -> {
                    SV cond = body(c.bodies().get(0), st, List.of());
                    if (isTrue(cond)) yield body(c.bodies().get(1), st, List.of());
                    if (isFalse(cond)) yield body(c.bodies().get(2), st, List.of());
                    SV a = body(c.bodies().get(1), st, List.of());
                    SV b = body(c.bodies().get(2), st, List.of());
                    yield mux(cond, a, b);
                }
                case JavaOp.ConditionalAndOp a -> {
                    SV acc = new K(true);
                    for (var b : a.bodies()) {
                        if (isFalse(acc)) break;
                        acc = and(acc, body(b, st, List.of()));
                    }
                    yield acc;
                }
                case JavaOp.ConditionalOrOp o -> {
                    SV acc = new K(false);
                    for (var b : o.bodies()) {
                        if (isTrue(acc)) break;
                        acc = or(acc, body(b, st, List.of()));
                    }
                    yield acc;
                }
                case JavaOp.ForOp f -> {
                    loop(f, st, f.bodies().get(0), f.bodies().get(1), f.bodies().get(2), f.bodies().get(3));
                    yield null;
                }
                case JavaOp.WhileOp w -> {
                    loop(w, st, null, w.bodies().get(0), null, w.bodies().get(1));
                    yield null;
                }
                case JavaOp.SwitchStatementOp s -> {
                    switchOp(s, s.bodies(), st, false);
                    yield null;
                }
                case JavaOp.SwitchExpressionOp s -> switchOp(s, s.bodies(), st, true);
                case JavaOp.SwitchFallthroughOp f -> throw err(op, "switch fall-through is not supported; use case ... -> ...");
                case JavaOp.InvokeOp inv -> invoke(inv, st);
                case JavaOp.NewOp n -> newOp(n);
                case JavaOp.FieldAccessOp.FieldLoadOp fl -> fieldLoad(fl);
                case JavaOp.ConvOp c -> conv(c, val(c.operands().getFirst()));
                case JavaOp.NotOp n -> not(val(n.operands().getFirst()));
                case JavaOp.NegOp n -> arith(op, "sub", new K(0), val(n.operands().getFirst()));
                case JavaOp.ComplOp c -> arith(op, "xor", val(c.operands().getFirst()), new K(-1));
                case JavaOp.BinaryOp b -> arith(op, ((JavaOp) op).externalizeOpName(), val(b.operands().get(0)), val(b.operands().get(1)));
                case JavaOp.CompareOp c -> compare(op, ((JavaOp) op).externalizeOpName(), val(c.operands().get(0)), val(c.operands().get(1)));
                default -> throw err(op, "Java operation '" + op.getClass().getSimpleName() + "' cannot be compiled to hardware");
            };
        }

        /** Name a hardware value after the variable it is stored in. */
        SV name(SV v, String n) {
            if (v instanceof H h && h.d().signal() == null && n != null && !n.isEmpty())
                return new H(h.d().namedData(n), h.type());
            return v;
        }

        void ifChain(List<Body> bodies, int i, State st) {
            if (i >= bodies.size()) return;
            if (i == bodies.size() - 1) { // else
                body(bodies.get(i), st, List.of());
                return;
            }
            SV cond = body(bodies.get(i), st, List.of());
            if (isTrue(cond)) {
                body(bodies.get(i + 1), st, List.of());
                return;
            }
            if (isFalse(cond)) {
                ifChain(bodies, i + 2, st);
                return;
            }
            State t = st.copy(), e = st.copy();
            body(bodies.get(i + 1), t, List.of());
            ifChain(bodies, i + 2, e);
            merge(st, cond, t, e);
        }

        void loop(Op op, State st, Body init, Body cond, Body update, Body bodyB) {
            loopBodies.add(bodyB.entryBlock());
            List<SV> loopArgs = new ArrayList<>();
            if (init != null) {
                SV v = body(init, st, List.of());
                if (v != null) loopArgs.add(v);
            }
            SV outerBroken = st.broken;
            st.broken = new K(false);
            for (int iter = 0; ; iter++) {
                if (iter > 100_000) throw err(op, "loop did not finish after 100000 iterations at elaboration time");
                SV c = body(cond, st, loopArgs);
                if (c instanceof H) throw err(op, "loop condition depends on hardware values; loops must have bounds known at elaboration time");
                if (isFalse(c) || isTrue(st.broken) || isTrue(st.done)) break;
                body(bodyB, st, loopArgs);
                if (!isFalse(st.continued)) st.spec.clear();
                st.continued = new K(false);
                if (update != null) body(update, st, loopArgs);
            }
            if (!isFalse(st.broken)) st.spec.clear();
            st.broken = outerBroken;
        }

        SV switchOp(Op op, List<Body> bodies, State st, boolean expression) {
            SV sel = val(op.operands().getFirst());
            // evaluate case predicates in order; take the first that matches
            List<SV> preds = new ArrayList<>();
            for (int i = 0; i < bodies.size(); i += 2) preds.add(body(bodies.get(i), st, List.of(sel)));
            return switchFrom(bodies, preds, 0, st, expression);
        }

        SV switchFrom(List<Body> bodies, List<SV> preds, int k, State st, boolean expression) {
            if (k >= preds.size()) {
                if (expression) throw new IllegalStateException("switch expression without a matching case");
                return null;
            }
            SV p = preds.get(k);
            Body b = bodies.get(2 * k + 1);
            if (isTrue(p)) return body(b, st, List.of());
            if (isFalse(p)) return switchFrom(bodies, preds, k + 1, st, expression);
            State t = st.copy(), e = st.copy();
            SV vt = body(b, t, List.of());
            SV ve = switchFrom(bodies, preds, k + 1, e, expression);
            merge(st, p, t, e);
            return expression ? mux(p, vt, ve) : null;
        }

        SV fieldLoad(JavaOp.FieldAccessOp.FieldLoadOp fl) {
            try {
                var f = fl.fieldReference().resolveToField(LOOKUP);
                if (!Modifier.isStatic(f.getModifiers())) throw err(fl, "instance field access is not supported; use record accessors");
                f.setAccessible(true);
                return new K(f.get(null));
            } catch (ReflectiveOperationException e) {
                throw err(fl, "cannot resolve field " + fl.fieldReference() + ": " + e);
            }
        }

        SV newOp(JavaOp.NewOp n) {
            var args = new ArrayList<SV>();
            for (var v : n.operands()) args.add(val(v));
            Class<?> cls;
            try {
                cls = (Class<?>) ((JavaType) n.resultType()).resolve(LOOKUP);
            } catch (ReflectiveOperationException e) {
                throw err(n, "cannot resolve " + n.resultType());
            }
            if (cls.isRecord()) {
                if (args.stream().allMatch(a -> a instanceof K)) return construct(n, cls, args);
                return new R(cls, args);
            }
            if (args.stream().allMatch(a -> a instanceof K)) return construct(n, cls, args);
            throw err(n, "only records can hold hardware values (new " + cls.getSimpleName() + ")");
        }

        SV construct(Op op, Class<?> cls, List<SV> args) {
            try {
                for (var c : cls.getDeclaredConstructors()) {
                    if (c.getParameterCount() != args.size()) continue;
                    c.setAccessible(true);
                    Object[] a = args.stream().map(x -> ((K) x).v()).toArray();
                    return new K(c.newInstance(a));
                }
            } catch (ReflectiveOperationException | IllegalArgumentException e) {
                throw err(op, "cannot construct " + cls.getSimpleName() + ": " + e);
            }
            throw err(op, "no constructor of " + cls.getSimpleName() + " with " + args.size() + " arguments");
        }

        private State stateForStage;

        SV invoke(JavaOp.InvokeOp inv, State st) {
            stateForStage = st;
            Method m;
            try {
                m = inv.invokeReference().resolveToMethod(LOOKUP);
            } catch (ReflectiveOperationException e) {
                throw err(inv, "cannot resolve " + inv.invokeReference() + ": " + e);
            }
            var args = new ArrayList<SV>();
            for (var v : inv.operands()) args.add(val(v));
            boolean isStatic = Modifier.isStatic(m.getModifiers());
            // record accessors on symbolic records
            if (!isStatic && args.getFirst() instanceof R r && m.getParameterCount() == 0) {
                var rcs = r.cls().getRecordComponents();
                for (int i = 0; i < rcs.length; i++) if (rcs[i].getName().equals(m.getName())) return r.comps().get(i);
            }
            // enum ordinal on hardware
            if (!isStatic && args.getFirst() instanceof H h && h.type().isEnum() && m.getName().equals("ordinal"))
                return new H(((HwEnum<?>) h.d()).asUInt().zext(32), int.class);
            if (m.getDeclaringClass() == Hardware.class) return intrinsic(inv, m, args);
            // everything constant: just call it (elaboration-time computation)
            if (args.stream().allMatch(a -> a instanceof K)) {
                try {
                    m.setAccessible(true);
                    Object recv = isStatic ? null : ((K) args.getFirst()).v();
                    Object[] a = args.subList(isStatic ? 0 : 1, args.size()).stream().map(x -> ((K) x).v()).toArray();
                    return new K(m.invoke(recv, a));
                } catch (java.lang.reflect.InvocationTargetException e) {
                    throw err(inv, "call to " + m.getName() + " failed at elaboration time: " + e.getCause());
                } catch (ReflectiveOperationException e) {
                    throw err(inv, "cannot call " + m.getName() + ": " + e);
                }
            }
            if (isStatic) {
                SV intrinsic = intrinsic(inv, m, args);
                if (intrinsic != null) return intrinsic;
                var callee = Op.ofMethod(m);
                if (callee.isEmpty())
                    throw err(inv, "cannot compile the call to " + m.getDeclaringClass().getSimpleName() + "." + m.getName()
                            + " on hardware values; annotate it with @Reflect");
                var sub = new Interp();
                SV r = sub.call(callee.get(), args, 1);
                return r;
            }
            throw err(inv, "cannot call instance method " + m.getName() + " on hardware values");
        }

        SV intrinsic(Op op, Method m, List<SV> a) {
            String c = m.getDeclaringClass().getName() + "." + m.getName();
            return switch (c) {
                case "org.eninehq.tessera.babylon.Hardware.stage" -> {
                    if (!(org.eninehq.tessera.core.Internal.hook() instanceof org.eninehq.tessera.pipeline.Pipeline p))
                        throw err(op, "Hardware.stage() needs an active pipeline (call apply inside Pipeline.begin/end)");
                    if (!isTrue(alive(stateForStage)))
                        throw err(op, "Hardware.stage() must not be inside a condition on hardware values");
                    p.stage();
                    yield new K(null);
                }
                case "java.lang.Math.min" -> mux(compare(op, "lt", a.get(0), a.get(1)), a.get(0), a.get(1));
                case "java.lang.Math.max" -> mux(compare(op, "gt", a.get(0), a.get(1)), a.get(0), a.get(1));
                case "java.lang.Math.abs" -> mux(compare(op, "lt", a.get(0), new K(0)), arith(op, "sub", new K(0), a.get(0)), a.get(0));
                case "java.lang.Integer.compareUnsigned", "java.lang.Long.compareUnsigned" -> {
                    Class<?> t = c.startsWith("java.lang.Long") ? long.class : int.class;
                    UInt x = (UInt) hw(a.get(0), t).d(), y = (UInt) hw(a.get(1), t).d();
                    UInt lt = UInt.lit(0xFFFF_FFFFL, 32), eq = UInt.lit(0, 32), gt = UInt.lit(1, 32);
                    yield new H(Hw.mux(x.lt(y), lt, Hw.mux(x.eq(y), eq, gt)), int.class);
                }
                case "java.lang.Integer.bitCount" -> {
                    UInt x = (UInt) hw(a.get(0), int.class).d();
                    UInt acc = UInt.lit(0, 32);
                    for (int i = 0; i < 32; i++) acc = acc.addWrap(x.bit(i).zext(32));
                    yield new H(acc, int.class);
                }
                default -> null;
            };
        }

        // ------------------------------------------------------------ arithmetic with Java semantics

        Class<?> primType(Op op) {
            return javaClass(op, op.resultType());
        }

        Class<?> javaClass(Op op, jdk.incubator.code.CodeType t) {
            if (t.equals(JavaType.INT)) return int.class;
            if (t.equals(JavaType.LONG)) return long.class;
            if (t.equals(JavaType.BOOLEAN)) return boolean.class;
            if (t.equals(JavaType.SHORT)) return short.class;
            if (t.equals(JavaType.CHAR)) return char.class;
            if (t.equals(JavaType.BYTE)) return byte.class;
            try {
                return (Class<?>) ((JavaType) t).resolve(LOOKUP);
            } catch (ReflectiveOperationException | ClassCastException e) {
                throw err(op, "unsupported type " + t);
            }
        }

        SV arith(Op op, String name, SV a, SV b) {
            Class<?> t = primType(op);
            if (t == boolean.class) {
                return switch (name) {
                    case "and" -> and(a, b);
                    case "or" -> or(a, b);
                    case "xor" -> a instanceof K ka && b instanceof K kb ? new K((Boolean) ka.v() ^ (Boolean) kb.v())
                            : new H(bool(a).xor(bool(b)), boolean.class);
                    default -> throw err(op, "operator " + name + " on booleans");
                };
            }
            if (a instanceof K ka && b instanceof K kb) return new K(constArith(op, name, t, ka.v(), kb.v()));
            int w = widthOf(t);
            UInt x = (UInt) hw(a, t).d(), y;
            boolean shift = name.equals("lshl") || name.equals("ashr") || name.equals("lshr");
            if (shift) {
                Class<?> st2 = b instanceof H hb ? hb.type() : (b instanceof K kb && kb.v() instanceof Long ? long.class : int.class);
                int amtBits = Integer.numberOfTrailingZeros(w);
                if (b instanceof K kb) {
                    int n = (int) (((Number) kb.v()).longValue() & (w - 1));
                    UInt r = switch (name) {
                        case "lshl" -> n == 0 ? x : x.bits(w - 1 - n, 0).shl(n);
                        case "lshr" -> x.shr(n);
                        default -> x.asSInt().sra(n).asUInt();
                    };
                    return new H(r, t);
                }
                UInt amt = ((UInt) hw(b, st2).d()).bits(amtBits - 1, 0);
                UInt r = switch (name) {
                    case "lshl" -> x.dshl(amt);
                    case "lshr" -> x.dshr(amt);
                    default -> x.asSInt().dsra(amt).asUInt();
                };
                return new H(r, t);
            }
            y = (UInt) hw(b, t).d();
            UInt r = switch (name) {
                case "add" -> x.addWrap(y);
                case "sub" -> x.subWrap(y);
                case "mul" -> x.mul(y).trunc(w);
                case "and" -> x.and(y);
                case "or" -> x.or(y);
                case "xor" -> x.xor(y);
                case "div", "mod" -> throw err(op, "division of hardware values is not supported");
                default -> throw err(op, "operator " + name + " is not supported on hardware values");
            };
            return new H(r, t);
        }

        Object constArith(Op op, String name, Class<?> t, Object a, Object b) {
            if (t == long.class) {
                long x = ((Number) a).longValue(), y = ((Number) b).longValue();
                return switch (name) {
                    case "add" -> x + y;
                    case "sub" -> x - y;
                    case "mul" -> x * y;
                    case "div" -> x / y;
                    case "mod" -> x % y;
                    case "and" -> x & y;
                    case "or" -> x | y;
                    case "xor" -> x ^ y;
                    case "lshl" -> x << y;
                    case "ashr" -> x >> y;
                    case "lshr" -> x >>> y;
                    default -> throw err(op, "operator " + name);
                };
            }
            int x = a instanceof Character c ? c : ((Number) a).intValue(), y = b instanceof Character c ? c : ((Number) b).intValue();
            return switch (name) {
                case "add" -> x + y;
                case "sub" -> x - y;
                case "mul" -> x * y;
                case "div" -> x / y;
                case "mod" -> x % y;
                case "and" -> x & y;
                case "or" -> x | y;
                case "xor" -> x ^ y;
                case "lshl" -> x << y;
                case "ashr" -> x >> y;
                case "lshr" -> x >>> y;
                default -> throw err(op, "operator " + name);
            };
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        SV compare(Op op, String name, SV a, SV b) {
            Class<?> t = a instanceof H ha ? ha.type() : b instanceof H hb ? hb.type() : typeOfConst((K) a);
            if (a instanceof K ka && b instanceof K kb) {
                Object x = ka.v(), y = kb.v();
                if (!(x instanceof Number) || !(y instanceof Number)) {
                    return switch (name) {
                        case "eq" -> new K(java.util.Objects.equals(x, y));
                        case "neq" -> new K(!java.util.Objects.equals(x, y));
                        default -> throw err(op, "cannot compare " + x + " and " + y);
                    };
                }
                long p = ((Number) x).longValue(), q = ((Number) y).longValue();
                return new K(switch (name) {
                    case "eq" -> p == q;
                    case "neq" -> p != q;
                    case "lt" -> p < q;
                    case "le" -> p <= q;
                    case "gt" -> p > q;
                    default -> p >= q;
                });
            }
            if (t.isEnum()) {
                HwEnum e1 = (HwEnum) hw(a, t).d(), e2 = (HwEnum) hw(b, t).d();
                Bool eq = e1.eq(e2);
                return switch (name) {
                    case "eq" -> new H(eq, boolean.class);
                    case "neq" -> new H(eq.not(), boolean.class);
                    default -> throw err(op, "enums can only be compared with == and !=");
                };
            }
            if (t == boolean.class) {
                Bool x = bool(a), y = bool(b);
                return switch (name) {
                    case "eq" -> new H(x.xor(y).not(), boolean.class);
                    case "neq" -> new H(x.xor(y), boolean.class);
                    default -> throw err(op, "booleans can only be compared with == and !=");
                };
            }
            if (t != long.class) t = int.class;
            UInt x = (UInt) promote(hw(a, intLike(a)), t).d(), y = (UInt) promote(hw(b, intLike(b)), t).d();
            Bool r = switch (name) {
                case "eq" -> x.eq(y);
                case "neq" -> x.ne(y);
                case "lt" -> x.asSInt().lt(y.asSInt());
                case "le" -> x.asSInt().le(y.asSInt());
                case "gt" -> x.asSInt().gt(y.asSInt());
                default -> x.asSInt().ge(y.asSInt());
            };
            return new H(r, boolean.class);
        }

        Class<?> intLike(SV s) {
            if (s instanceof H h) return h.type();
            return ((K) s).v() instanceof Long ? long.class : int.class;
        }

        /** Java binary numeric promotion of a hardware value to {@code t}. */
        H promote(H h, Class<?> t) {
            if (h.type() == t) return h;
            UInt u = (UInt) h.d();
            int w = widthOf(t);
            if (u.width() == w) return new H(u, t);
            boolean signedSrc = h.type() != char.class;
            return new H(signedSrc ? u.asSInt().sext(w).asUInt() : u.zext(w), t);
        }

        SV conv(JavaOp.ConvOp c, SV v) {
            Class<?> to = primType(c);
            if (v instanceof K k) {
                Object x = k.v();
                long n = x instanceof Character ch ? ch : ((Number) x).longValue();
                return new K(switch (to.getName()) {
                    case "int" -> (int) n;
                    case "long" -> n;
                    case "short" -> (short) n;
                    case "char" -> (char) n;
                    case "byte" -> (byte) n;
                    default -> throw err(c, "conversion to " + to);
                });
            }
            H h = (H) v;
            int wt = widthOf(to);
            UInt u = (UInt) h.d();
            if (u.width() > wt) return new H(u.trunc(wt), to);
            return promote(h, to);
        }
    }
}
