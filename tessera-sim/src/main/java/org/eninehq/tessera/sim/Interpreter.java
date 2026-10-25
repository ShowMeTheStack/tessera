package org.eninehq.tessera.sim;

import org.eninehq.tessera.ir.OpKind;

/** The reference simulator: interprets the netlist instruction by instruction. */
public final class Interpreter extends AbstractSim {
    private static final OpKind[] KINDS = OpKind.values();
    private final Netlist.Insn[] insns;

    public Interpreter(Netlist net) {
        super(net);
        this.insns = net.insns().toArray(Netlist.Insn[]::new);
    }

    private long arg(Netlist.Insn in, int k) {
        long x = v[in.args()[k]];
        return in.argS()[k] ? sx(x, in.argW()[k]) : x;
    }

    @Override
    protected void eval() {
        for (var in : insns) {
            if (in.wide()) WideOps.exec(in, v, mems);
            else v[in.dst()] = exec(in);
        }
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
