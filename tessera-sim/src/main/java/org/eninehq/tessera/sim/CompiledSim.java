package org.eninehq.tessera.sim;

import static java.lang.constant.ConstantDescs.CD_Object;
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
import java.util.List;
import org.eninehq.tessera.ir.OpKind;

/**
 * A simulator that compiles the netlist to JVM bytecode (via the ClassFile API) and lets the
 * JIT optimise it. Instructions are split into small static methods (the JIT does not
 * compile huge methods) grouped into hidden classes.
 */
public final class CompiledSim extends AbstractSim {
    static final int INSNS_PER_METHOD = 150;
    static final int METHODS_PER_CLASS = 100;

    private static final OpKind[] KINDS = OpKind.values();
    private static final ClassDesc CD_CHUNK = ClassDesc.of(CompiledChunk.class.getName());
    private static final ClassDesc CD_OPS = ClassDesc.of(SimOps.class.getName());
    private static final ClassDesc CD_LONG_ARR = CD_long.arrayType();
    private static final MethodTypeDesc MTD_EVAL = MethodTypeDesc.of(CD_void, CD_LONG_ARR, CD_long.arrayType(2));
    private static final MethodTypeDesc MTD_J_JJ = MethodTypeDesc.of(CD_long, CD_long, CD_long);
    private static final MethodTypeDesc MTD_J_JJJ = MethodTypeDesc.of(CD_long, CD_long, CD_long, CD_long);
    private static final MethodTypeDesc MTD_J_J = MethodTypeDesc.of(CD_long, CD_long);
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
        this.chunks = compile(net);
    }

    @Override
    protected void eval() {
        for (var c : chunks) c.eval(v, mems);
    }

    // ------------------------------------------------------------ code generation

    private static CompiledChunk[] compile(Netlist net) {
        var insns = net.insns();
        int perClass = INSNS_PER_METHOD * METHODS_PER_CLASS;
        var out = new ArrayList<CompiledChunk>();
        var lookup = MethodHandles.lookup();
        for (int start = 0; start < insns.size(); start += perClass) {
            var part = insns.subList(start, Math.min(insns.size(), start + perClass));
            var wide = new ArrayList<Netlist.Insn>();
            byte[] bytes = generateClass(part, wide);
            try {
                Class<?> cls = lookup.defineHiddenClassWithClassData(bytes, wide.toArray(), true).lookupClass();
                out.add((CompiledChunk) cls.getConstructor().newInstance());
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("failed to load generated simulator class", e);
            }
        }
        return out.toArray(CompiledChunk[]::new);
    }

    private static byte[] generateClass(List<Netlist.Insn> insns, List<Netlist.Insn> wide) {
        ClassDesc self = ClassDesc.of(CompiledSim.class.getPackageName() + ".GeneratedChunk");
        int methods = (insns.size() + INSNS_PER_METHOD - 1) / INSNS_PER_METHOD;
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
                var part = insns.subList(k * INSNS_PER_METHOD, Math.min(insns.size(), (k + 1) * INSNS_PER_METHOD));
                cb.withMethodBody("m" + k, MTD_EVAL, ClassFile.ACC_PRIVATE | ClassFile.ACC_STATIC, code -> {
                    for (var in : part) {
                        if (in.wide()) {
                            code.ldc(CLASS_DATA).loadConstant(wide.size()).aaload().checkcast(CD_INSN)
                                    .aload(0).aload(1).invokestatic(CD_WIDE, "exec", MTD_WIDE);
                            wide.add(in);
                        } else {
                            emit(code, in);
                        }
                    }
                    code.return_();
                });
            }
        });
    }

    /** Push v[slot]. */
    private static void load(CodeBuilder c, int slot) {
        c.aload(0).loadConstant(slot).laload();
    }

    /** Push operand k, sign-extended to 64 bits when signed. */
    private static void arg(CodeBuilder c, Netlist.Insn in, int k) {
        load(c, in.args()[k]);
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

    private static void emit(CodeBuilder c, Netlist.Insn in) {
        int w = in.width();
        c.aload(0).loadConstant(in.dst());
        if (in.op() == Netlist.MEMREAD) {
            c.aload(1).loadConstant(in.p0()).aaload();
            load(c, in.args()[0]);
            ops(c, "memRead", MTD_MEMREAD);
            c.lastore();
            return;
        }
        switch (KINDS[in.op()]) {
            case ADD -> { arg(c, in, 0); arg(c, in, 1); c.ladd(); applyMask(c, w); }
            case SUB -> { arg(c, in, 0); arg(c, in, 1); c.lsub(); applyMask(c, w); }
            case MUL -> { arg(c, in, 0); arg(c, in, 1); c.lmul(); applyMask(c, w); }
            case AND -> { arg(c, in, 0); arg(c, in, 1); c.land(); applyMask(c, w); }
            case OR -> { arg(c, in, 0); arg(c, in, 1); c.lor(); applyMask(c, w); }
            case XOR -> { arg(c, in, 0); arg(c, in, 1); c.lxor(); applyMask(c, w); }
            case NOT -> { load(c, in.args()[0]); c.loadConstant(-1L).lxor(); applyMask(c, w); }
            case SHL -> { load(c, in.args()[0]); c.loadConstant(in.p0()).lshl(); applyMask(c, w); }
            case SHR -> {
                if (in.p0() >= 64) c.loadConstant(0L);
                else { load(c, in.args()[0]); c.loadConstant(in.p0()).lushr(); applyMask(c, w); }
            }
            case SRA -> {
                load(c, in.args()[0]);
                int aw = in.argW()[0];
                if (aw < 64) c.loadConstant(64 - aw).lshl().loadConstant(64 - aw).lshr();
                c.loadConstant(Math.min(in.p0(), 63)).lshr();
                applyMask(c, w);
            }
            case DSHL -> { load(c, in.args()[0]); load(c, in.args()[1]); c.loadConstant(mask(w)); ops(c, "dshl", MTD_J_JJJ); }
            case DSHR -> { load(c, in.args()[0]); load(c, in.args()[1]); c.loadConstant(mask(w)); ops(c, "dshr", MTD_J_JJJ); }
            case DSRA -> {
                load(c, in.args()[0]);
                int aw = in.argW()[0];
                if (aw < 64) c.loadConstant(64 - aw).lshl().loadConstant(64 - aw).lshr();
                load(c, in.args()[1]);
                c.loadConstant(mask(w));
                ops(c, "dsra", MTD_J_JJJ);
            }
            case EQ, NE, LT, LE, GT, GE -> {
                arg(c, in, 0);
                arg(c, in, 1);
                boolean s = in.argS()[0];
                String name = switch (KINDS[in.op()]) {
                    case EQ -> "eq";
                    case NE -> "ne";
                    case LT -> s ? "lt" : "ltu";
                    case LE -> s ? "le" : "leu";
                    case GT -> s ? "gt" : "gtu";
                    default -> s ? "ge" : "geu";
                };
                ops(c, name, MTD_J_JJ);
            }
            case MUX -> { load(c, in.args()[0]); arg(c, in, 1); arg(c, in, 2); ops(c, "mux", MTD_J_JJJ); applyMask(c, w); }
            case CAT -> {
                load(c, in.args()[0]);
                for (int k = 1; k < in.args().length; k++) {
                    c.loadConstant(in.argW()[k]).lshl();
                    load(c, in.args()[k]);
                    c.lor();
                }
                applyMask(c, w);
            }
            case SLICE -> { load(c, in.args()[0]); c.loadConstant(in.p1()).lushr(); applyMask(c, w); }
            case EXT -> { arg(c, in, 0); applyMask(c, w); }
            case CAST -> load(c, in.args()[0]);
            case ANDR -> { load(c, in.args()[0]); c.loadConstant(mask(in.argW()[0])); ops(c, "andr", MTD_J_JJ); }
            case ORR -> { load(c, in.args()[0]); ops(c, "orr", MTD_J_J); }
            case XORR -> { load(c, in.args()[0]); ops(c, "xorr", MTD_J_J); }
        }
        c.lastore();
    }
}
