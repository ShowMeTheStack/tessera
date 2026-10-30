package org.eninehq.tessera.sim;

import static java.lang.constant.ConstantDescs.CD_Object;
import static java.lang.constant.ConstantDescs.CD_int;
import static java.lang.constant.ConstantDescs.CD_long;
import static java.lang.constant.ConstantDescs.CD_void;
import static java.lang.constant.ConstantDescs.INIT_NAME;
import static java.lang.constant.ConstantDescs.MTD_void;

import java.lang.classfile.ClassFile;
import java.lang.classfile.CodeBuilder;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.Arrays;

/**
 * {@link BatchSim}'s gate program as JVM bytecode, one straight-line {@code v[d] = op(v[a], v[b], v[c])}
 * per gate, with a call back to {@link BatchSim#readPort} where a memory read sits in the program. Split into
 * methods small enough for the JIT, grouped into hidden classes; identical programs share the classes.
 * A gate result read only by later gates of the same method, and not observable outside the gate program,
 * stays in a JVM local instead of {@code v[]}; a gate nothing reads is not emitted
 * ({@code -Dtessera.batch.locals=false}: every result is stored, every gate emitted).
 */
final class BatchCode {
    private BatchCode() {}

    static final int GATES_PER_METHOD = 200;
    static final int METHODS_PER_CLASS = 100;
    private static final int AND = 0, OR = 1, XOR = 2, NOT = 3, MUX = 4;

    private static final ClassDesc CD_CHUNK = ClassDesc.of(BatchChunk.class.getName());
    private static final ClassDesc CD_SIM = ClassDesc.of(BatchSim.class.getName());
    private static final MethodTypeDesc MTD_EVAL = MethodTypeDesc.of(CD_void, CD_long.arrayType(), CD_SIM);
    private static final MethodTypeDesc MTD_READ = MethodTypeDesc.of(CD_void, CD_int);
    static final boolean LOCALS = !"false".equalsIgnoreCase(System.getProperty("tessera.batch.locals", "true"));

    private static final class Key {
        final byte[][] classes;
        final int hash;

        Key(byte[][] classes) {
            this.classes = classes;
            this.hash = Arrays.deepHashCode(classes);
        }

        @Override public boolean equals(Object o) {
            return o instanceof Key k && k.hash == hash && Arrays.deepEquals(k.classes, classes);
        }

        @Override public int hashCode() { return hash; }
    }

    private static final CodeCache<Key, BatchChunk[]> CACHE = new CodeCache<>();

    static BatchChunk[] compile(int ngates, int[] op, int[] dst, int[] a, int[] b, int[] c, int[] readAt, boolean[] observable) {
        int perClass = GATES_PER_METHOD * METHODS_PER_CLASS;
        // per variable: the gate that computes it; whether it must be in v[]; whether a same-method reader uses a local
        int nv = observable.length;
        int[] def = new int[nv];
        java.util.Arrays.fill(def, -1);
        for (int g = 0; g < ngates; g++) def[dst[g]] = g;
        boolean[] store = new boolean[nv], local = new boolean[nv];
        for (int x = 0; x < nv; x++) store[x] = observable[x] || !LOCALS;
        for (int g = 0; g < ngates; g++) {
            int m = g / GATES_PER_METHOD;
            int nargs = op[g] == NOT ? 1 : op[g] == MUX ? 3 : op[g] <= XOR ? 2 : 1;
            int[] args = {a[g], b[g], c[g]};
            for (int k = 0; k < nargs; k++) {
                int u = args[k];
                if (def[u] < 0) continue;
                if (def[u] / GATES_PER_METHOD == m && LOCALS) local[u] = true;
                else store[u] = true;
            }
        }
        var plan = new GatePlan(def, store, local);
        var classes = new ArrayList<byte[]>();
        int nextRead = 0;
        for (int start = 0; start < ngates || start == 0; start += perClass) {
            int end = Math.min(ngates, start + perClass);
            boolean last = end >= ngates;
            int firstRead = nextRead;
            while (nextRead < readAt.length && (readAt[nextRead] < end || last)) nextRead++;
            classes.add(generate(plan, start, end, op, dst, a, b, c, readAt, firstRead, nextRead));
            if (last) break;
        }
        var key = CompiledSim.shareClasses() ? new Key(classes.toArray(byte[][]::new)) : null;
        if (key != null) {
            var have = CACHE.get(key);
            if (have != null) return have;
        }
        var lookup = MethodHandles.lookup();
        var out = new BatchChunk[classes.size()];
        try {
            for (int k = 0; k < out.length; k++)
                out[k] = (BatchChunk) lookup.defineHiddenClass(classes.get(k), true).lookupClass().getConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("failed to load generated batch simulator class", e);
        }
        if (key != null) CACHE.put(key, out);
        return out;
    }

    /** Per variable: defining gate (-1: none), stored to v[], kept in a local for same-method readers. */
    private record GatePlan(int[] def, boolean[] store, boolean[] local) {}

    private static int localOf(int g) {
        return 2 + 2 * (g % GATES_PER_METHOD);
    }

    /** Gates [from, to) and reads [r0, r1) (each read runs just before the gate at its index; the rest after the last gate). */
    private static byte[] generate(GatePlan p, int from, int to, int[] op, int[] dst, int[] a, int[] b, int[] c, int[] readAt, int r0, int r1) {
        ClassDesc self = ClassDesc.of(BatchSim.class.getPackageName() + ".GeneratedGates");
        int methods = Math.max(1, (to - from + GATES_PER_METHOD - 1) / GATES_PER_METHOD);
        return ClassFile.of().build(self, cb -> {
            cb.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_FINAL);
            cb.withInterfaceSymbols(CD_CHUNK);
            cb.withMethodBody(INIT_NAME, MTD_void, ClassFile.ACC_PUBLIC,
                    code -> code.aload(0).invokespecial(CD_Object, INIT_NAME, MTD_void).return_());
            cb.withMethodBody("eval", MTD_EVAL, ClassFile.ACC_PUBLIC, code -> {
                for (int k = 0; k < methods; k++) code.aload(1).aload(2).invokestatic(self, "g" + k, MTD_EVAL);
                code.return_();
            });
            int[] ri = {r0};
            for (int k = 0; k < methods; k++) {
                int lo = from + k * GATES_PER_METHOD, hi = Math.min(to, lo + GATES_PER_METHOD);
                boolean lastMethod = k == methods - 1;
                cb.withMethodBody("g" + k, MTD_EVAL, ClassFile.ACC_PRIVATE | ClassFile.ACC_STATIC, code -> {
                    for (int g = lo; g < hi; g++) {
                        while (ri[0] < r1 && readAt[ri[0]] == g) code.aload(1).loadConstant(ri[0]++).invokevirtual(CD_SIM, "readPort", MTD_READ);
                        int d = dst[g];
                        boolean st = p.store()[d], loc = p.local()[d];
                        if (!st && !loc) continue; // nothing reads it
                        if (!loc) code.aload(0).loadConstant(d);
                        final int gg = g;
                        java.util.function.IntConsumer ld = x -> load(code, p, x, gg);
                        ld.accept(a[g]);
                        switch (op[g]) {
                            case AND -> { ld.accept(b[g]); code.land(); }
                            case OR -> { ld.accept(b[g]); code.lor(); }
                            case XOR -> { ld.accept(b[g]); code.lxor(); }
                            case NOT -> code.loadConstant(-1L).lxor();
                            case MUX -> {
                                // (s & b) | (~s & c)
                                ld.accept(b[g]);
                                code.land();
                                ld.accept(a[g]);
                                code.loadConstant(-1L).lxor();
                                ld.accept(c[g]);
                                code.land().lor();
                            }
                            default -> {}
                        }
                        if (!loc) code.lastore();
                        else {
                            code.lstore(localOf(g));
                            if (st) code.aload(0).loadConstant(d).lload(localOf(g)).lastore();
                        }
                    }
                    if (lastMethod) while (ri[0] < r1) code.aload(1).loadConstant(ri[0]++).invokevirtual(CD_SIM, "readPort", MTD_READ);
                    code.return_();
                });
            }
        });
    }

    /** Push variable x, read by gate {@code at}: a constant, a local of this method, or v[x]. */
    private static void load(CodeBuilder c, GatePlan p, int x, int at) {
        if (LOCALS && x == 0) { c.lconst_0(); return; }
        if (LOCALS && x == 1) { c.loadConstant(-1L); return; }
        int d = p.def()[x];
        if (p.local()[x] && d >= 0 && d / GATES_PER_METHOD == at / GATES_PER_METHOD) c.lload(localOf(d));
        else c.aload(0).loadConstant(x).laload();
    }
}
