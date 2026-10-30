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
import java.util.List;
import java.util.Set;
import org.eninehq.tessera.ir.OpKind;

/**
 * A simulator that compiles the netlist to JVM bytecode (via the ClassFile API) and lets the
 * JIT optimise it. Instructions are split into small static methods (the JIT does not
 * compile huge methods) grouped into hidden classes.
 *
 * <p>The generated classes are stateless (they work on the arrays passed in), so simulators of
 * the same netlist share them: a second {@code Sim.create} of a design runs code the JIT has
 * already compiled (turn off with {@code -Dtessera.sim.shareClasses=false}).
 *
 * <p>Code generation, each step switchable off with {@code -Dtessera.sim.disable=<list>}
 * so that it can be measured alone ({@code all} turns all of them off). {@code ops} and {@code mux} are
 * off by default (on with {@code -Dtessera.sim.enable=ops,mux}): on the cores they made the generated
 * code retire more instructions, not fewer.
 * <ul>
 * <li>{@code consts}: constant operands are bytecode literals instead of {@code v[]} loads, so the JIT folds them;
 * <li>{@code locals}: a value used only inside the method that computes it stays in a JVM local and is not stored to {@code v[]};
 * <li>{@code dce}: instructions whose value nothing observes are not emitted;
 * <li>{@code ops}: comparisons and reductions as inline bytecode ({@code lcmp}) instead of {@link SimOps} calls;
 * <li>{@code mux}: a 1-bit-select multiplexer as {@code f ^ ((t ^ f) & -(s != 0))} instead of {@link SimOps#mux};
 * <li>{@code memread}: a memory read whose address is known to be masked below the depth is a plain array load;
 * <li>{@code commit}: the register update at a clock edge is generated code too;
 * <li>{@code masks}: no {@code & mask} where the operands already are below {@code 2^width} (results of masking
 *     instructions or literals; never memory contents, inputs or registers, whose values a user can set).
 * </ul>
 * Values a user can observe (named signals, register inputs, memory-port operands, properties) are always stored.
 */
public final class CompiledSim extends AbstractSim {
    static final int INSNS_PER_METHOD = 150;
    static final int METHODS_PER_CLASS = 100;
    static final int REGS_PER_METHOD = 200;

    private static volatile boolean share = !"false".equalsIgnoreCase(System.getProperty("tessera.sim.shareClasses", "true"));

    /** Whether new simulators (compiled and {@link BatchSim}) reuse the generated classes of an identical earlier one. */
    public static boolean shareClasses() { return share; }

    /**
     * Turn class sharing on or off for simulators created from now on (the benchmark measures both:
     * off defines new classes for every {@code Sim.create}).
     */
    public static void setShareClasses(boolean on) { share = on; }
    static final Set<String> DISABLED = Set.of(System.getProperty("tessera.sim.disable", "").split(","));
    static final Set<String> ENABLED = Set.of(System.getProperty("tessera.sim.enable", "").split(","));
    /** Steps that are off unless named in {@code -Dtessera.sim.enable}: measured to retire more instructions. */
    static final Set<String> OFF_BY_DEFAULT = Set.of("ops", "mux");

    static boolean on(String opt) {
        if (DISABLED.contains(opt) || DISABLED.contains("all")) return false;
        return !OFF_BY_DEFAULT.contains(opt) || ENABLED.contains(opt);
    }

    private static final OpKind[] KINDS = OpKind.values();
    private static final ClassDesc CD_CHUNK = ClassDesc.of(CompiledChunk.class.getName());
    private static final ClassDesc CD_COMMIT = ClassDesc.of(CompiledCommit.class.getName());
    private static final ClassDesc CD_OPS = ClassDesc.of(SimOps.class.getName());
    private static final ClassDesc CD_LONG_ARR = CD_long.arrayType();
    private static final MethodTypeDesc MTD_EVAL = MethodTypeDesc.of(CD_void, CD_LONG_ARR, CD_long.arrayType(2));
    private static final MethodTypeDesc MTD_COMMIT = MethodTypeDesc.of(CD_void, CD_LONG_ARR, CD_LONG_ARR);
    private static final MethodTypeDesc MTD_J_JJ = MethodTypeDesc.of(CD_long, CD_long, CD_long);
    private static final MethodTypeDesc MTD_J_JJJ = MethodTypeDesc.of(CD_long, CD_long, CD_long, CD_long);
    private static final MethodTypeDesc MTD_J_J = MethodTypeDesc.of(CD_long, CD_long);
    private static final MethodTypeDesc MTD_I_J = MethodTypeDesc.of(CD_int, CD_long);
    private static final MethodTypeDesc MTD_MEMREAD = MethodTypeDesc.of(CD_long, CD_LONG_ARR, CD_long);
    private static final ClassDesc CD_INSN = ClassDesc.ofDescriptor(Netlist.Insn.class.descriptorString());
    private static final ClassDesc CD_WIDE = ClassDesc.of(WideOps.class.getName());
    private static final MethodTypeDesc MTD_WIDE = MethodTypeDesc.of(CD_void, CD_INSN, CD_LONG_ARR, CD_long.arrayType(2));
    private static final java.lang.constant.DynamicConstantDesc<Object[]> CLASS_DATA =
            java.lang.constant.DynamicConstantDesc.ofNamed(java.lang.constant.ConstantDescs.BSM_CLASS_DATA,
                    java.lang.constant.ConstantDescs.DEFAULT_NAME, CD_Object.arrayType());

    private final CompiledChunk[] chunks;

    public CompiledSim(Netlist net) {
        super(net);
        var code = code(net, null, narrowRegs(), !hasWideRegs());
        this.chunks = code.chunks();
        this.fastCommit = code.commit();
    }

    @Override
    protected void eval() {
        for (var c : chunks) c.eval(v, mems);
    }

    // ------------------------------------------------------------ class sharing

    /** Loaded code for one netlist: the evaluation chunks and the register update (null when not generated). */
    record Code(CompiledChunk[] chunks, CompiledCommit commit) {}

    /** Identifies generated code by its exact content: the class files plus the wide instructions they hand to {@link WideOps}. */
    private static final class Key {
        final byte[][] classes;
        final long[] wide;
        final int hash;

        Key(byte[][] classes, long[] wide) {
            this.classes = classes;
            this.wide = wide;
            this.hash = 31 * Arrays.deepHashCode(classes) + Arrays.hashCode(wide);
        }

        @Override public boolean equals(Object o) {
            return o instanceof Key k && k.hash == hash && Arrays.equals(k.wide, wide) && Arrays.deepEquals(k.classes, classes);
        }

        @Override public int hashCode() { return hash; }
    }

    private static final CodeCache<Key, Code> CACHE = new CodeCache<>();

    /** Number of simulators created from shared classes (for tests and the benchmark). */
    private static final java.util.concurrent.atomic.AtomicLong SHARED_HITS = new java.util.concurrent.atomic.AtomicLong();

    /** How many simulators so far reused the classes of an earlier one. */
    public static long sharedHits() { return SHARED_HITS.get(); }

    /**
     * Generate and load (or reuse) the code for the instructions {@code order} (indices into the netlist's
     * instructions, in topological order; null for all of them), plus the register update when
     * {@code commit} is set and the step is on.
     */
    static Code code(Netlist net, int[] order, Netlist.Reg[] regs, boolean commitWanted) {
        var plan = new Plan(net, order);
        var insns = net.insns();
        int count = plan.order.length;
        int perClass = INSNS_PER_METHOD * METHODS_PER_CLASS;
        var classes = new ArrayList<byte[]>();
        var wides = new ArrayList<Object[]>();
        for (int start = 0; start < count; start += perClass) {
            var wide = new ArrayList<Netlist.Insn>();
            classes.add(generateClass(plan, start, Math.min(count, start + perClass), wide));
            wides.add(wide.toArray());
        }
        byte[] commit = on("commit") && commitWanted ? generateCommit(plan, regs) : null;
        if (commit != null) classes.add(commit);
        Key key = null;
        if (share) {
            key = new Key(classes.toArray(byte[][]::new), encodeWide(Arrays.stream(plan.order).mapToObj(insns::get).toList()));
            var have = CACHE.get(key);
            if (have != null) {
                SHARED_HITS.incrementAndGet();
                return have;
            }
        }
        var lookup = MethodHandles.lookup();
        var chunks = new CompiledChunk[wides.size()];
        CompiledCommit cc = null;
        try {
            for (int k = 0; k < chunks.length; k++)
                chunks[k] = (CompiledChunk) lookup.defineHiddenClassWithClassData(classes.get(k), wides.get(k), true).lookupClass()
                        .getConstructor().newInstance();
            if (commit != null) cc = (CompiledCommit) lookup.defineHiddenClass(commit, true).lookupClass().getConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("failed to load generated simulator class", e);
        }
        var code = new Code(chunks, cc);
        if (key != null) CACHE.put(key, code);
        return code;
    }

    private static long[] encodeWide(List<Netlist.Insn> insns) {
        var out = new ArrayList<Long>();
        for (var in : insns) {
            if (!in.wide()) continue;
            out.add((long) in.op());
            out.add((long) in.dst());
            out.add((long) in.width());
            out.add((long) in.p0());
            out.add((long) in.p1());
            out.add((long) in.args().length);
            for (int k = 0; k < in.args().length; k++) {
                out.add((long) in.args()[k]);
                out.add((long) in.argW()[k]);
                out.add(in.argS()[k] ? 1L : 0L);
            }
        }
        long[] r = new long[out.size()];
        for (int i = 0; i < r.length; i++) r[i] = out.get(i);
        return r;
    }

    /** Counts for a netlist under the current settings: instructions live/dead, results stored/local-only, constant operands. */
    public static String planStats(Netlist net) {
        var p = new Plan(net, null);
        int live = 0, stored = 0, localOnly = 0, constArgs = 0, args = 0, signal = 0;
        for (int i = 0; i < p.order.length; i++) {
            var in = p.insn(i);
            if (!p.live[i]) continue;
            live++;
            if (p.store[in.dst()]) stored++;
            else localOnly++;
            if (net.isSignalSlot(in.dst())) signal++;
            for (int a : in.args()) {
                args++;
                if (p.constant[a]) constArgs++;
            }
        }
        return "insns=" + net.insns().size() + " live=" + live + " stored=" + stored + " localOnly=" + localOnly + " signalDst=" + signal
                + " args=" + args + " constArgs=" + constArgs;
    }

    // ------------------------------------------------------------ analysis

    /**
     * Which slots must live in {@code v[]}, which values stay in locals, which instructions are dead, for
     * the instructions {@code order} (positions index this array; null = every instruction in netlist order).
     */
    static final class Plan {
        final Netlist net;
        final int[] order;
        final long[] init;
        final boolean[] constant, store, local, live;
        /** Per slot: the position of the instruction that computes it, or -1. */
        final int[] defInsn;

        Netlist.Insn insn(int pos) { return net.insns().get(order[pos]); }

        Plan(Netlist net, int[] order) {
            this.net = net;
            int n = net.slotCount();
            if (order == null) {
                order = new int[net.insns().size()];
                for (int i = 0; i < order.length; i++) order[i] = i;
            }
            this.order = order;
            var insns = new java.util.AbstractList<Netlist.Insn>() {
                @Override public Netlist.Insn get(int i) { return insn(i); }
                @Override public int size() { return Plan.this.order.length; }
            };
            init = net.initialValues();
            constant = new boolean[n];
            for (int s = 0; s < n; s++) constant[s] = on("consts") && net.isConstant(s) && net.slotWidth(s) > 0 && net.slotWidth(s) <= 64;
            // observable: anything outside the evaluation code reads it
            boolean[] observable = new boolean[n];
            for (int s = 0; s < n; s++) observable[s] = net.isSignalSlot(s);
            for (var r : net.regs()) {
                markWords(observable, r.slot(), r.width());
                markWords(observable, r.next(), r.width());
                if (r.hasReset()) observable[r.resetSlot()] = true;
            }
            for (var w : net.memWrites()) {
                markWords(observable, w.addr(), net.slotWidth(w.addr()));
                markWords(observable, w.data(), net.mems().get(w.mem()).width());
                observable[w.en()] = true;
            }
            for (var c : net.checks()) {
                observable[c.cond()] = true;
                observable[c.en()] = true;
            }
            defInsn = new int[n];
            Arrays.fill(defInsn, -1);
            for (int i = 0; i < insns.size(); i++) if (!insns.get(i).wide()) defInsn[insns.get(i).dst()] = i;
            // liveness, backwards over the topological order
            live = new boolean[insns.size()];
            boolean[] read = new boolean[n];
            for (int i = insns.size() - 1; i >= 0; i--) {
                var in = insns.get(i);
                live[i] = in.wide() || !on("dce") || observable[in.dst()] || read[in.dst()];
                if (live[i]) for (int a : in.args()) read[a] = true;
            }
            store = new boolean[n];
            local = new boolean[n];
            for (int s = 0; s < n; s++) store[s] = observable[s] || !on("locals");
            for (int i = 0; i < insns.size(); i++) {
                if (!live[i]) continue;
                var in = insns.get(i);
                int m = i / INSNS_PER_METHOD;
                for (int k = 0; k < in.args().length; k++) {
                    int a = in.args()[k];
                    int d = defInsn[a];
                    if (d < 0) continue;
                    if (in.wide() || d / INSNS_PER_METHOD != m) store[a] = true;
                    else local[a] = true;
                }
            }
            if (!on("locals")) Arrays.fill(local, false);
            // wide results are written by WideOps; mark every word of a wide operand as stored
            for (var in : insns) if (in.wide()) for (int k = 0; k < in.args().length; k++) markWords(store, in.args()[k], in.argW()[k]);
        }

        private static void markWords(boolean[] b, int slot, int width) {
            for (int k = 0; k < BitVec.words(width); k++) b[slot + k] = true;
        }

        /** The JVM local holding the value of the instruction at index {@code i} (two words per long). */
        static int localOf(int i) {
            return 2 + 2 * (i % INSNS_PER_METHOD);
        }

        /** Two scratch long locals after the instruction locals. */
        static final int SCRATCH = 2 + 2 * INSNS_PER_METHOD;
    }

    // ------------------------------------------------------------ code generation

    /** A class for the instructions at positions [from, to) of the plan. */
    private static byte[] generateClass(Plan plan, int from, int to, List<Netlist.Insn> wide) {
        ClassDesc self = ClassDesc.of(CompiledSim.class.getPackageName() + ".GeneratedChunk");
        int methods = (to - from + INSNS_PER_METHOD - 1) / INSNS_PER_METHOD;
        return ClassFile.of().build(self, cb -> {
            cb.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_FINAL);
            cb.withInterfaceSymbols(CD_CHUNK);
            cb.withMethodBody(INIT_NAME, MTD_void, ClassFile.ACC_PUBLIC,
                    code -> code.aload(0).invokespecial(CD_Object, INIT_NAME, MTD_void).return_());
            cb.withMethodBody("eval", MTD_EVAL, ClassFile.ACC_PUBLIC, code -> {
                for (int k = 0; k < methods; k++) code.aload(1).aload(2).invokestatic(self, "m" + k, MTD_EVAL);
                code.return_();
            });
            for (int k = 0; k < methods; k++) {
                int lo = from + k * INSNS_PER_METHOD, hi = Math.min(to, lo + INSNS_PER_METHOD);
                cb.withMethodBody("m" + k, MTD_EVAL, ClassFile.ACC_PRIVATE | ClassFile.ACC_STATIC, code -> {
                    for (int i = lo; i < hi; i++) {
                        var in = plan.insn(i);
                        if (!plan.live[i]) continue;
                        if (in.wide()) {
                            code.ldc(CLASS_DATA).loadConstant(wide.size()).aaload().checkcast(CD_INSN)
                                    .aload(0).aload(1).invokestatic(CD_WIDE, "exec", MTD_WIDE);
                            wide.add(in);
                        } else {
                            emit(code, plan, in, i);
                        }
                    }
                    code.return_();
                });
            }
        });
    }

    /** Push the value of {@code slot}, read by the instruction at index {@code at}. */
    private static void load(CodeBuilder c, Plan p, int slot, int at) {
        if (p.constant[slot]) {
            c.loadConstant(p.init[slot]);
            return;
        }
        int d = p.defInsn[slot];
        if (p.local[slot] && d >= 0 && d / INSNS_PER_METHOD == at / INSNS_PER_METHOD) c.lload(Plan.localOf(d));
        else c.aload(0).loadConstant(slot).laload();
    }

    /** Push operand k, sign-extended to 64 bits when signed. */
    private static void arg(CodeBuilder c, Plan p, Netlist.Insn in, int k, int at) {
        load(c, p, in.args()[k], at);
        if (in.argS()[k] && in.argW()[k] < 64) {
            int sh = 64 - in.argW()[k];
            c.loadConstant(sh).lshl().loadConstant(sh).lshr();
        }
    }

    private static void applyMask(CodeBuilder c, int w) {
        if (w < 64) c.loadConstant(mask(w)).land();
    }

    private static void ops(CodeBuilder c, String name, MethodTypeDesc t) {
        c.invokestatic(CD_OPS, name, t);
    }

    /** Emit instruction {@code in} (index {@code i}): its value goes to {@code v[dst]}, a local, or both. */
    private static void emit(CodeBuilder c, Plan p, Netlist.Insn in, int i) {
        int dst = in.dst();
        boolean store = p.store[dst], local = p.local[dst];
        if (!local) {
            c.aload(0).loadConstant(dst);
            value(c, p, in, i);
            c.lastore();
            return;
        }
        value(c, p, in, i);
        c.lstore(Plan.localOf(i));
        if (store) c.aload(0).loadConstant(dst).lload(Plan.localOf(i)).lastore();
    }

    /** {@code lcmp} of the two operands (a, b), or (b, a) when {@code swap}; leaves an int -1/0/1. */
    private static void cmp(CodeBuilder c, Plan p, Netlist.Insn in, int at, boolean swap, boolean unsigned) {
        boolean flip = unsigned; // unsigned order = signed order with the top bits flipped
        for (int k : swap ? new int[]{1, 0} : new int[]{0, 1}) {
            arg(c, p, in, k, at);
            if (flip) c.loadConstant(Long.MIN_VALUE).lxor();
        }
        c.lcmp();
    }

    /**
     * True when the value in {@code slot} is known to be below {@code 2^width}: a literal, or the result
     * of an instruction that masks it. (Memory contents and casts are not known to be masked: a test
     * may store any long in a memory array.)
     */
    private static boolean maskedBelow(Plan p, int slot, int width) {
        if (width >= 64) return true;
        if (p.net.isConstant(slot)) return Long.compareUnsigned(p.init[slot], 1L << width) < 0;
        int d = p.defInsn[slot];
        if (d < 0) return false;
        var in = p.insn(d);
        if (in.op() == Netlist.MEMREAD) return false;
        if (KINDS[in.op()] == OpKind.CAST) return maskedBelow(p, in.args()[0], width);
        return in.width() <= width;
    }

    /** Operand k, as {@link #arg} pushes it, is known to be below {@code 2^width} (so a mask to width is redundant). */
    private static boolean argBelow(Plan p, Netlist.Insn in, int k, int width) {
        return on("masks") && (width >= 64 || !in.argS()[k] && maskedBelow(p, in.args()[k], width));
    }

    private static boolean rawBelow(Plan p, int slot, int width) {
        return on("masks") && maskedBelow(p, slot, width);
    }

    private static void value(CodeBuilder c, Plan p, Netlist.Insn in, int at) {
        int w = in.width();
        if (in.op() == Netlist.MEMREAD) {
            var mem = p.net.mems().get(in.p0());
            long len = (long) mem.depth() * mem.words();
            c.aload(1).loadConstant(in.p0()).aaload();
            load(c, p, in.args()[0], at);
            if (on("memread") && maskedBelow(p, in.args()[0], in.argW()[0]) && in.argW()[0] < 31 && (1L << in.argW()[0]) <= len)
                c.l2i().laload();
            else ops(c, "memRead", MTD_MEMREAD);
            return;
        }
        boolean inl = on("ops");
        switch (KINDS[in.op()]) {
            case ADD -> { arg(c, p, in, 0, at); arg(c, p, in, 1, at); c.ladd(); applyMask(c, w); }
            case SUB -> { arg(c, p, in, 0, at); arg(c, p, in, 1, at); c.lsub(); applyMask(c, w); }
            case MUL -> { arg(c, p, in, 0, at); arg(c, p, in, 1, at); c.lmul(); applyMask(c, w); }
            case AND -> {
                arg(c, p, in, 0, at); arg(c, p, in, 1, at); c.land();
                if (!(argBelow(p, in, 0, w) || argBelow(p, in, 1, w))) applyMask(c, w);
            }
            case OR -> {
                arg(c, p, in, 0, at); arg(c, p, in, 1, at); c.lor();
                if (!(argBelow(p, in, 0, w) && argBelow(p, in, 1, w))) applyMask(c, w);
            }
            case XOR -> {
                arg(c, p, in, 0, at); arg(c, p, in, 1, at); c.lxor();
                if (!(argBelow(p, in, 0, w) && argBelow(p, in, 1, w))) applyMask(c, w);
            }
            case NOT -> { load(c, p, in.args()[0], at); c.loadConstant(-1L).lxor(); applyMask(c, w); }
            case SHL -> {
                load(c, p, in.args()[0], at); c.loadConstant(in.p0()).lshl();
                if (!(in.p0() < w && rawBelow(p, in.args()[0], w - in.p0()))) applyMask(c, w);
            }
            case SHR -> {
                if (in.p0() >= 64) c.loadConstant(0L);
                else {
                    load(c, p, in.args()[0], at); c.loadConstant(in.p0()).lushr();
                    if (!(w + in.p0() <= 64 && rawBelow(p, in.args()[0], w + in.p0()))) applyMask(c, w);
                }
            }
            case SRA -> {
                load(c, p, in.args()[0], at);
                int aw = in.argW()[0];
                if (aw < 64) c.loadConstant(64 - aw).lshl().loadConstant(64 - aw).lshr();
                c.loadConstant(Math.min(in.p0(), 63)).lshr();
                applyMask(c, w);
            }
            case DSHL -> { load(c, p, in.args()[0], at); load(c, p, in.args()[1], at); c.loadConstant(mask(w)); ops(c, "dshl", MTD_J_JJJ); }
            case DSHR -> { load(c, p, in.args()[0], at); load(c, p, in.args()[1], at); c.loadConstant(mask(w)); ops(c, "dshr", MTD_J_JJJ); }
            case DSRA -> {
                load(c, p, in.args()[0], at);
                int aw = in.argW()[0];
                if (aw < 64) c.loadConstant(64 - aw).lshl().loadConstant(64 - aw).lshr();
                load(c, p, in.args()[1], at);
                c.loadConstant(mask(w));
                ops(c, "dsra", MTD_J_JJJ);
            }
            case EQ, NE, LT, LE, GT, GE -> {
                var kind = KINDS[in.op()];
                boolean s = in.argS()[0];
                if (inl) {
                    // lcmp gives -1/0/1: equality is bit 0, "less" is the sign bit
                    switch (kind) {
                        case EQ -> { cmp(c, p, in, at, false, false); c.iconst_1().iand().iconst_1().ixor(); }
                        case NE -> { cmp(c, p, in, at, false, false); c.iconst_1().iand(); }
                        case LT -> { cmp(c, p, in, at, false, !s); c.bipush(31).iushr(); }
                        case GE -> { cmp(c, p, in, at, false, !s); c.bipush(31).iushr().iconst_1().ixor(); }
                        case GT -> { cmp(c, p, in, at, true, !s); c.bipush(31).iushr(); }
                        default -> { cmp(c, p, in, at, true, !s); c.bipush(31).iushr().iconst_1().ixor(); }
                    }
                    c.i2l();
                } else {
                    arg(c, p, in, 0, at);
                    arg(c, p, in, 1, at);
                    String name = switch (kind) {
                        case EQ -> "eq";
                        case NE -> "ne";
                        case LT -> s ? "lt" : "ltu";
                        case LE -> s ? "le" : "leu";
                        case GT -> s ? "gt" : "gtu";
                        default -> s ? "ge" : "geu";
                    };
                    ops(c, name, MTD_J_JJ);
                }
            }
            case MUX -> {
                if (on("mux") && in.argW()[0] == 1) {
                    // the select is 0 or 1: f ^ ((t ^ f) & -s)
                    int t = Plan.SCRATCH, f = Plan.SCRATCH + 2;
                    arg(c, p, in, 1, at);
                    c.lstore(t);
                    arg(c, p, in, 2, at);
                    c.lstore(f);
                    c.lload(t).lload(f).lxor();
                    load(c, p, in.args()[0], at);
                    c.lconst_0().lcmp().iconst_1().iand().i2l().lneg(); // -(s != 0), exact even for an unmasked s
                    c.land().lload(f).lxor();
                } else {
                    load(c, p, in.args()[0], at);
                    arg(c, p, in, 1, at);
                    arg(c, p, in, 2, at);
                    ops(c, "mux", MTD_J_JJJ);
                }
                if (!(argBelow(p, in, 1, w) && argBelow(p, in, 2, w))) applyMask(c, w);
            }
            case CAT -> {
                load(c, p, in.args()[0], at);
                for (int k = 1; k < in.args().length; k++) {
                    c.loadConstant(in.argW()[k]).lshl();
                    load(c, p, in.args()[k], at);
                    c.lor();
                }
                boolean parts = true;
                int total = 0;
                for (int k = 0; k < in.args().length; k++) {
                    parts &= rawBelow(p, in.args()[k], in.argW()[k]);
                    total += in.argW()[k];
                }
                if (!(parts && total <= w)) applyMask(c, w);
            }
            case SLICE -> {
                load(c, p, in.args()[0], at); c.loadConstant(in.p1()).lushr();
                if (!(in.p1() + w <= 64 && rawBelow(p, in.args()[0], in.p1() + w))) applyMask(c, w);
            }
            case EXT -> { arg(c, p, in, 0, at); if (!argBelow(p, in, 0, w)) applyMask(c, w); }
            case CAST -> load(c, p, in.args()[0], at);
            case ANDR -> {
                load(c, p, in.args()[0], at);
                c.loadConstant(mask(in.argW()[0]));
                if (inl) c.lcmp().iconst_1().iand().iconst_1().ixor().i2l();
                else ops(c, "andr", MTD_J_JJ);
            }
            case ORR -> {
                load(c, p, in.args()[0], at);
                if (inl) c.lconst_0().lcmp().iconst_1().iand().i2l();
                else ops(c, "orr", MTD_J_J);
            }
            case XORR -> {
                load(c, p, in.args()[0], at);
                if (inl) c.invokestatic(ClassDesc.of("java.lang.Long"), "bitCount", MTD_I_J).iconst_1().iand().i2l();
                else ops(c, "xorr", MTD_J_J);
            }
        }
    }

    /**
     * The register update for an edge of every clock: {@code next = reset active ? reset value : v[next]}.
     * A register whose slot no other register reads (as its next value or reset) is written at once;
     * the others go through {@code tmp} and are written after all next values have been read.
     */
    private static byte[] generateCommit(Plan p, Netlist.Reg[] regs) {
        return generateCommit(p, regs, null, 0);
    }

    /**
     * For {@link ParallelSim}: the register update of the registers {@code include} only, split in two:
     * {@code mode} 1 computes every included next value into {@code tmp} (reads only), mode 2 writes them.
     */
    static CompiledCommit commitPart(Netlist net, Netlist.Reg[] regs, boolean[] include, int mode) {
        byte[] b = generateCommit(new Plan(net, new int[0]), regs, include, mode);
        try {
            return (CompiledCommit) MethodHandles.lookup().defineHiddenClass(b, true).lookupClass().getConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("failed to load generated commit class", e);
        }
    }

    /** mode 0: the whole update; 1: next values into tmp only; 2: tmp into the registers only. */
    private static byte[] generateCommit(Plan p, Netlist.Reg[] regs, boolean[] include, int mode) {
        int n = p.net.slotCount();
        int[] readers = new int[n];
        for (var r : regs) {
            readers[r.next()]++;
            if (r.hasReset()) readers[r.resetSlot()]++;
        }
        boolean[] viaTmp = new boolean[regs.length];
        boolean[] skip = new boolean[regs.length];
        for (int i = 0; i < regs.length; i++) {
            var r = regs[i];
            int own = (r.next() == r.slot() ? 1 : 0) + (r.hasReset() && r.resetSlot() == r.slot() ? 1 : 0);
            viaTmp[i] = mode != 0 || readers[r.slot()] - own > 0;
            skip[i] = r.next() == r.slot() && !r.hasReset() || include != null && !include[i];
        }
        ClassDesc self = ClassDesc.of(CompiledSim.class.getPackageName() + ".GeneratedCommit");
        int methods = (regs.length + REGS_PER_METHOD - 1) / REGS_PER_METHOD;
        return ClassFile.of().build(self, cb -> {
            cb.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_FINAL);
            cb.withInterfaceSymbols(CD_COMMIT);
            cb.withMethodBody(INIT_NAME, MTD_void, ClassFile.ACC_PUBLIC,
                    code -> code.aload(0).invokespecial(CD_Object, INIT_NAME, MTD_void).return_());
            cb.withMethodBody("commit", MTD_COMMIT, ClassFile.ACC_PUBLIC, code -> {
                if (mode != 2) for (int k = 0; k < methods; k++) code.aload(1).aload(2).invokestatic(self, "n" + k, MTD_COMMIT);
                if (mode != 1) for (int k = 0; k < methods; k++) code.aload(1).aload(2).invokestatic(self, "w" + k, MTD_COMMIT);
                code.return_();
            });
            for (int k = 0; k < methods; k++) {
                int lo = k * REGS_PER_METHOD, hi = Math.min(regs.length, lo + REGS_PER_METHOD);
                cb.withMethodBody("n" + k, MTD_COMMIT, ClassFile.ACC_PRIVATE | ClassFile.ACC_STATIC, code -> {
                    for (int i = lo; i < hi; i++) {
                        if (skip[i]) continue;
                        var r = regs[i];
                        if (viaTmp[i]) code.aload(1).loadConstant(i);
                        else code.aload(0).loadConstant(r.slot());
                        if (r.hasReset()) {
                            var notReset = code.newLabel();
                            var done = code.newLabel();
                            commitLoad(code, p, r.resetSlot());
                            code.loadConstant(r.resetActive()).lcmp().ifne(notReset);
                            code.loadConstant(r.resetValue()).goto_(done);
                            code.labelBinding(notReset);
                            commitLoad(code, p, r.next());
                            code.labelBinding(done);
                        } else {
                            commitLoad(code, p, r.next());
                        }
                        code.lastore();
                    }
                    code.return_();
                });
                cb.withMethodBody("w" + k, MTD_COMMIT, ClassFile.ACC_PRIVATE | ClassFile.ACC_STATIC, code -> {
                    for (int i = lo; i < hi; i++)
                        if (viaTmp[i] && !skip[i]) code.aload(0).loadConstant(regs[i].slot()).aload(1).loadConstant(i).laload().lastore();
                    code.return_();
                });
            }
        });
    }

    private static void commitLoad(CodeBuilder c, Plan p, int slot) {
        if (p.constant[slot]) c.loadConstant(p.init[slot]);
        else c.aload(0).loadConstant(slot).laload();
    }
}
