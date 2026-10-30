package org.eninehq.tessera.sim;

import org.eninehq.tessera.ir.OpKind;

/**
 * The reference simulator: interprets the netlist instruction by instruction.
 *
 * <p>The instructions are decoded once into flat arrays (opcode, destination, operand slots,
 * sign-extension shifts, result mask) so the loop does not chase the {@link Netlist.Insn} records; the
 * arithmetic is the same expression for expression as {@link #exec}, which still evaluates the rare kinds
 * (concatenation, dynamic shifts) and is the whole interpreter with {@code -Dtessera.interp.decode=false}.
 */
public final class Interpreter extends AbstractSim {
    private static final OpKind[] KINDS = OpKind.values();
    static final boolean DECODE = !"false".equalsIgnoreCase(System.getProperty("tessera.interp.decode", "true"));

    /**
     * Decoded opcodes (dense, for a tableswitch). Comparisons are split by signedness (the signedness of
     * operand 0 picks the order, as in {@link #cmp(Netlist.Insn)}); SLOW evaluates the record with {@link #exec}.
     */
    private static final int ADD_OP = 0, SUB_OP = 1, MUL_OP = 2, AND_OP = 3, OR_OP = 4, XOR_OP = 5, NOT_OP = 6, SHL_OP = 7,
            SHR_OP = 8, SRA_OP = 9, EQ_OP = 10, NE_OP = 11, LTS = 12, LES = 13, GTS = 14, GES = 15, LTU = 16, LEU = 17, GTU = 18,
            GEU = 19, MUX_OP = 20, SLICE_OP = 21, EXT_OP = 22, CAST_OP = 23, ANDR_OP = 24, ORR_OP = 25, XORR_OP = 26,
            MEMREAD_OP = 27, SLOW = 28, WIDE = 29;
    /** Per instruction: opcode, a0, a1, a2, sign shifts of a0, a1, a2, p (p0, or p1 for SLICE, or the memory for MEMREAD). */
    private static final int STRIDE = 8;

    private final Netlist.Insn[] insns;
    private final int[] code, insnDst;
    /** Per instruction: the result mask and the mask of operand 0's width. */
    private final long[] masks;

    public Interpreter(Netlist net) {
        super(net);
        this.insns = net.insns().toArray(Netlist.Insn[]::new);
        int n = insns.length;
        code = new int[n * STRIDE];
        insnDst = new int[n];
        masks = new long[2 * n];
        for (int i = 0; i < n; i++) {
            var in = insns[i];
            int c = i * STRIDE;
            code[c] = decode(in);
            insnDst[i] = in.dst();
            masks[2 * i] = mask(in.width());
            int na = in.args().length;
            if (na > 0) { code[c + 1] = in.args()[0]; code[c + 4] = shift(in, 0); masks[2 * i + 1] = mask(in.argW()[0]); }
            if (na > 1) { code[c + 2] = in.args()[1]; code[c + 5] = shift(in, 1); }
            if (na > 2) { code[c + 3] = in.args()[2]; code[c + 6] = shift(in, 2); }
            code[c + 7] = in.op() == Netlist.MEMREAD ? in.p0() : KINDS[in.op()] == OpKind.SLICE ? in.p1() : in.p0();
        }
    }

    private static int decode(Netlist.Insn in) {
        if (in.wide()) return WIDE;
        if (in.op() == Netlist.MEMREAD) return MEMREAD_OP;
        boolean s = in.args().length > 0 && in.argS()[0];
        return switch (KINDS[in.op()]) {
            case ADD -> ADD_OP;
            case SUB -> SUB_OP;
            case MUL -> MUL_OP;
            case AND -> AND_OP;
            case OR -> OR_OP;
            case XOR -> XOR_OP;
            case NOT -> NOT_OP;
            case SHL -> SHL_OP;
            case SHR -> SHR_OP;
            case SRA -> SRA_OP;
            case EQ -> EQ_OP;
            case NE -> NE_OP;
            case LT -> s ? LTS : LTU;
            case LE -> s ? LES : LEU;
            case GT -> s ? GTS : GTU;
            case GE -> s ? GES : GEU;
            case MUX -> MUX_OP;
            case SLICE -> SLICE_OP;
            case EXT -> EXT_OP;
            case CAST -> CAST_OP;
            case ANDR -> ANDR_OP;
            case ORR -> ORR_OP;
            case XORR -> XORR_OP;
            case CAT, DSHL, DSHR, DSRA -> SLOW;
        };
    }

    /** The shift pair that sign-extends operand k as {@link #arg} does (0: no extension). */
    private static int shift(Netlist.Insn in, int k) {
        return in.argS()[k] && in.argW()[k] < 64 ? 64 - in.argW()[k] : 0;
    }

    private long arg(Netlist.Insn in, int k) {
        long x = v[in.args()[k]];
        return in.argS()[k] ? sx(x, in.argW()[k]) : x;
    }

    @Override
    protected void eval() {
        if (!DECODE) {
            for (var in : insns) {
                if (in.wide()) WideOps.exec(in, v, mems);
                else v[in.dst()] = exec(in);
            }
            return;
        }
        final long[] v = this.v;
        final int[] code = this.code;
        final long[] mk = this.masks;
        final int n = insns.length;
        for (int i = 0, c = 0; i < n; i++, c += STRIDE) {
            int o = code[c];
            long x = v[code[c + 1]];
            long r;
            switch (o) {
                case WIDE -> { WideOps.exec(insns[i], v, mems); continue; }
                case SLOW -> r = exec(insns[i]);
                case MEMREAD_OP -> {
                    long[] mem = mems[code[c + 7]];
                    r = Long.compareUnsigned(x, mem.length) < 0 ? mem[(int) x] : 0;
                }
                case ADD_OP -> r = (sx0(x, code[c + 4]) + sx0(v[code[c + 2]], code[c + 5])) & mk[2 * i];
                case SUB_OP -> r = (sx0(x, code[c + 4]) - sx0(v[code[c + 2]], code[c + 5])) & mk[2 * i];
                case MUL_OP -> r = (sx0(x, code[c + 4]) * sx0(v[code[c + 2]], code[c + 5])) & mk[2 * i];
                case AND_OP -> r = (sx0(x, code[c + 4]) & sx0(v[code[c + 2]], code[c + 5])) & mk[2 * i];
                case OR_OP -> r = (sx0(x, code[c + 4]) | sx0(v[code[c + 2]], code[c + 5])) & mk[2 * i];
                case XOR_OP -> r = (sx0(x, code[c + 4]) ^ sx0(v[code[c + 2]], code[c + 5])) & mk[2 * i];
                case NOT_OP -> r = ~x & mk[2 * i];
                case SHL_OP -> r = (x << code[c + 7]) & mk[2 * i];
                case SHR_OP -> r = code[c + 7] >= 64 ? 0 : (x >>> code[c + 7]) & mk[2 * i];
                case SRA_OP -> r = (sxw(x, mk[2 * i + 1]) >> Math.min(code[c + 7], 63)) & mk[2 * i];
                case EQ_OP -> r = sx0(x, code[c + 4]) == sx0(v[code[c + 2]], code[c + 5]) ? 1 : 0;
                case NE_OP -> r = sx0(x, code[c + 4]) != sx0(v[code[c + 2]], code[c + 5]) ? 1 : 0;
                case LTS -> r = sx0(x, code[c + 4]) < sx0(v[code[c + 2]], code[c + 5]) ? 1 : 0;
                case LES -> r = sx0(x, code[c + 4]) <= sx0(v[code[c + 2]], code[c + 5]) ? 1 : 0;
                case GTS -> r = sx0(x, code[c + 4]) > sx0(v[code[c + 2]], code[c + 5]) ? 1 : 0;
                case GES -> r = sx0(x, code[c + 4]) >= sx0(v[code[c + 2]], code[c + 5]) ? 1 : 0;
                case LTU -> r = Long.compareUnsigned(sx0(x, code[c + 4]), sx0(v[code[c + 2]], code[c + 5])) < 0 ? 1 : 0;
                case LEU -> r = Long.compareUnsigned(sx0(x, code[c + 4]), sx0(v[code[c + 2]], code[c + 5])) <= 0 ? 1 : 0;
                case GTU -> r = Long.compareUnsigned(sx0(x, code[c + 4]), sx0(v[code[c + 2]], code[c + 5])) > 0 ? 1 : 0;
                case GEU -> r = Long.compareUnsigned(sx0(x, code[c + 4]), sx0(v[code[c + 2]], code[c + 5])) >= 0 ? 1 : 0;
                case MUX_OP -> r = (x != 0 ? sx0(v[code[c + 2]], code[c + 5]) : sx0(v[code[c + 3]], code[c + 6])) & mk[2 * i];
                case SLICE_OP -> r = (x >>> code[c + 7]) & mk[2 * i];
                case EXT_OP -> r = sx0(x, code[c + 4]) & mk[2 * i];
                case CAST_OP -> r = x;
                case ANDR_OP -> r = x == mk[2 * i + 1] ? 1 : 0;
                case ORR_OP -> r = x != 0 ? 1 : 0;
                case XORR_OP -> r = Long.bitCount(x) & 1;
                default -> throw new IllegalStateException("opcode " + o);
            }
            v[insnDst[i]] = r;
        }
    }

    /** Sign-extend by the shift pair {@code sh} (0: unchanged), as {@link #arg} does. */
    private static long sx0(long x, int sh) {
        return sh == 0 ? x : (x << sh) >> sh;
    }

    /** {@code sx(x, w)} for the operand width whose mask is {@code mw}. */
    private static long sxw(long x, long mw) {
        int w = 64 - Long.numberOfLeadingZeros(mw);
        return sx(x, w);
    }

    long exec(Netlist.Insn in) {
        int w = in.width();
        long m = mask(w);
        if (in.op() == Netlist.MEMREAD) {
            long[] mem = mems[in.p0()];
            long a = v[in.args()[0]];
            return Long.compareUnsigned(a, mem.length) < 0 ? mem[(int) a] : 0;
        }
        return switch (KINDS[in.op()]) {
            case ADD -> (arg(in, 0) + arg(in, 1)) & m;
            case SUB -> (arg(in, 0) - arg(in, 1)) & m;
            case MUL -> (arg(in, 0) * arg(in, 1)) & m;
            case AND -> (arg(in, 0) & arg(in, 1)) & m;
            case OR -> (arg(in, 0) | arg(in, 1)) & m;
            case XOR -> (arg(in, 0) ^ arg(in, 1)) & m;
            case NOT -> ~v[in.args()[0]] & m;
            case SHL -> (v[in.args()[0]] << in.p0()) & m;
            case SHR -> in.p0() >= 64 ? 0 : (v[in.args()[0]] >>> in.p0()) & m;
            case SRA -> (sx(v[in.args()[0]], in.argW()[0]) >> Math.min(in.p0(), 63)) & m;
            case DSHL -> {
                long amt = v[in.args()[1]];
                yield Long.compareUnsigned(amt, 64) >= 0 ? 0 : (v[in.args()[0]] << amt) & m;
            }
            case DSHR -> {
                long amt = v[in.args()[1]];
                yield Long.compareUnsigned(amt, 64) >= 0 ? 0 : (v[in.args()[0]] >>> amt) & m;
            }
            case DSRA -> {
                long amt = v[in.args()[1]];
                long x = sx(v[in.args()[0]], in.argW()[0]);
                yield (Long.compareUnsigned(amt, 63) >= 0 ? x >> 63 : x >> amt) & m;
            }
            case EQ -> arg(in, 0) == arg(in, 1) ? 1 : 0;
            case NE -> arg(in, 0) != arg(in, 1) ? 1 : 0;
            case LT -> cmp(in) < 0 ? 1 : 0;
            case LE -> cmp(in) <= 0 ? 1 : 0;
            case GT -> cmp(in) > 0 ? 1 : 0;
            case GE -> cmp(in) >= 0 ? 1 : 0;
            case MUX -> (v[in.args()[0]] != 0 ? arg(in, 1) : arg(in, 2)) & m;
            case CAT -> {
                long acc = 0;
                for (int k = 0; k < in.args().length; k++) acc = (acc << in.argW()[k]) | v[in.args()[k]];
                yield acc & m;
            }
            case SLICE -> (v[in.args()[0]] >>> in.p1()) & m;
            case EXT -> arg(in, 0) & m;
            case CAST -> v[in.args()[0]];
            case ANDR -> v[in.args()[0]] == mask(in.argW()[0]) ? 1 : 0;
            case ORR -> v[in.args()[0]] != 0 ? 1 : 0;
            case XORR -> Long.bitCount(v[in.args()[0]]) & 1;
        };
    }

    private int cmp(Netlist.Insn in) {
        long a = arg(in, 0), b = arg(in, 1);
        return in.argS()[0] ? Long.compare(a, b) : Long.compareUnsigned(a, b);
    }
}
