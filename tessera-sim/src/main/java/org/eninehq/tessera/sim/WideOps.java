package org.eninehq.tessera.sim;

import org.eninehq.tessera.ir.OpKind;

/** Executes netlist instructions that have an operand or result wider than 64 bits. */
public final class WideOps {
    private WideOps() {}

    private static final OpKind[] KINDS = OpKind.values();

    private static long[] arg(Netlist.Insn in, long[] v, int k, int w) {
        return BitVec.load(v, in.args()[k], in.argW()[k], in.argS()[k], w);
    }

    /** Evaluate {@code in} and store its result at {@code v[in.dst()..]}. */
    public static void exec(Netlist.Insn in, long[] v, long[][] mems) {
        int w = in.width();
        long[] r;
        if (in.op() == Netlist.MEMREAD) {
            long[] mem = mems[in.p0()];
            int nw = BitVec.words(w);
            long[] addrW = BitVec.load(v, in.args()[0], in.argW()[0], false, Math.max(64, in.argW()[0]));
            r = new long[nw];
            boolean big = false;
            for (int k = 1; k < addrW.length; k++) big |= addrW[k] != 0;
            long a = addrW[0];
            if (!big && Long.compareUnsigned(a, mem.length / nw) < 0) System.arraycopy(mem, (int) a * nw, r, 0, nw);
            BitVec.store(v, in.dst(), r);
            return;
        }
        switch (KINDS[in.op()]) {
            case ADD -> r = BitVec.add(arg(in, v, 0, w), arg(in, v, 1, w), w);
            case SUB -> r = BitVec.sub(arg(in, v, 0, w), arg(in, v, 1, w), w);
            case MUL -> r = BitVec.mul(arg(in, v, 0, w), arg(in, v, 1, w), w);
            case AND -> r = BitVec.and(arg(in, v, 0, w), arg(in, v, 1, w));
            case OR -> r = BitVec.or(arg(in, v, 0, w), arg(in, v, 1, w));
            case XOR -> r = BitVec.xor(arg(in, v, 0, w), arg(in, v, 1, w));
            case NOT -> r = BitVec.not(arg(in, v, 0, w), w);
            case SHL -> r = BitVec.shl(arg(in, v, 0, in.argW()[0]), in.p0(), w);
            case SHR -> r = BitVec.shr(arg(in, v, 0, in.argW()[0]), in.argW()[0], in.p0(), false);
            case SRA -> r = BitVec.shr(arg(in, v, 0, in.argW()[0]), in.argW()[0], in.p0(), true);
            case DSHL, DSHR, DSRA -> {
                int aw = in.argW()[0];
                long[] a = arg(in, v, 0, aw);
                long[] amtW = BitVec.load(v, in.args()[1], in.argW()[1], false, Math.max(64, in.argW()[1]));
                boolean big = Long.compareUnsigned(amtW[0], Integer.MAX_VALUE) > 0;
                for (int k = 1; k < amtW.length; k++) big |= amtW[k] != 0;
                int amt = big ? Integer.MAX_VALUE : (int) amtW[0];
                r = switch (KINDS[in.op()]) {
                    case DSHL -> amt >= aw ? new long[BitVec.words(w)] : BitVec.shl(a, amt, aw + amt);
                    case DSHR -> BitVec.shr(a, aw, Math.min(amt, aw), false);
                    default -> BitVec.shr(a, aw, Math.min(amt, aw), true);
                };
                if (KINDS[in.op()] == OpKind.DSHL && amt < aw) r = BitVec.extract(r, 0, w);
            }
            case EQ, NE, LT, LE, GT, GE -> {
                int mw = Math.max(in.argW()[0], in.argW()[1]);
                long[] a = arg(in, v, 0, mw), b = arg(in, v, 1, mw);
                int c = in.argS()[0] ? BitVec.compareSigned(a, b, mw) : BitVec.compareUnsigned(a, b);
                boolean res = switch (KINDS[in.op()]) {
                    case EQ -> c == 0;
                    case NE -> c != 0;
                    case LT -> c < 0;
                    case LE -> c <= 0;
                    case GT -> c > 0;
                    default -> c >= 0;
                };
                r = new long[]{res ? 1 : 0};
            }
            case MUX -> r = v[in.args()[0]] != 0 ? arg(in, v, 1, w) : arg(in, v, 2, w);
            case CAT -> {
                r = new long[BitVec.words(w)];
                int pos = w;
                for (int k = 0; k < in.args().length; k++) {
                    int aw = in.argW()[k];
                    pos -= aw;
                    BitVec.deposit(r, BitVec.load(v, in.args()[k], aw, false, aw), pos, aw);
                }
            }
            case SLICE -> r = BitVec.extract(arg(in, v, 0, in.argW()[0]), in.p1(), w);
            case EXT -> r = arg(in, v, 0, w);
            case CAST -> r = arg(in, v, 0, w);
            case ANDR -> {
                int aw = in.argW()[0];
                long[] a = arg(in, v, 0, aw);
                r = new long[]{BitVec.isZero(BitVec.not(a, aw)) ? 1 : 0};
            }
            case ORR -> r = new long[]{BitVec.isZero(arg(in, v, 0, in.argW()[0])) ? 0 : 1};
            case XORR -> {
                long[] a = arg(in, v, 0, in.argW()[0]);
                int c = 0;
                for (long x : a) c += Long.bitCount(x);
                r = new long[]{c & 1};
            }
            default -> throw new IllegalStateException();
        }
        BitVec.store(v, in.dst(), r);
    }
}
