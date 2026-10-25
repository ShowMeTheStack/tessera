package org.eninehq.tessera.riscv;

/**
 * RV32IM semantics, written once for any {@link Dom}: the same methods are the golden
 * instruction-set simulator (with {@link SwDom}) and the datapath of every core (with
 * {@link HwDom}). Illegal instructions, ECALL, EBREAK, misaligned loads and stores and
 * misaligned jump or branch targets trap; a trap halts the machine (no trap handlers in
 * RV32I-only mode), retires without side effects and is reported on RVFI.
 */
public final class Rv32i {
    private Rv32i() {}

    public static final int OP_LUI = 0x37, OP_AUIPC = 0x17, OP_JAL = 0x6F, OP_JALR = 0x67, OP_BRANCH = 0x63,
            OP_LOAD = 0x03, OP_STORE = 0x23, OP_IMM = 0x13, OP_OP = 0x33, OP_FENCE = 0x0F, OP_SYSTEM = 0x73;

    /** Decoded fields and instruction-class flags. */
    public record Decoded<V, B>(
            V rd, V rs1, V rs2, V funct3, V imm, B alt,
            B lui, B auipc, B jal, B jalr, B branch, B load, B store, B opImm, B op, B fence, B system,
            B writesRd, B usesRs1, B usesRs2, B halt, B illegal, B mulDiv,
            B ecall, B ebreak, B mret, B wfi) {}

    public static <V, B> Decoded<V, B> decode(Dom<V, B> d, V inst) {
        return decode(d, inst, false);
    }

    /**
     * Decode {@code inst}. Without Zicsr every SYSTEM instruction with funct3 = 0 halts and CSR
     * instructions are illegal; with Zicsr (see {@link Zicsr}) CSR instructions, ECALL, MRET and
     * WFI are legal and only EBREAK halts.
     */
    public static <V, B> Decoded<V, B> decode(Dom<V, B> d, V inst, boolean zicsr) {
        V opcode = d.bits(inst, 6, 0);
        V rd = d.bits(inst, 11, 7), funct3 = d.bits(inst, 14, 12);
        V rs1 = d.bits(inst, 19, 15), rs2 = d.bits(inst, 24, 20);
        B alt = d.bit(inst, 30);

        B lui = d.is(opcode, OP_LUI), auipc = d.is(opcode, OP_AUIPC), jal = d.is(opcode, OP_JAL);
        B jalr = d.band(d.is(opcode, OP_JALR), d.is(funct3, 0));
        B branch = d.band(d.is(opcode, OP_BRANCH), d.bnot(d.bor(d.is(funct3, 2), d.is(funct3, 3))));
        B load = d.band(d.is(opcode, OP_LOAD), d.bnot(d.bor(d.is(funct3, 3), d.bor(d.is(funct3, 6), d.is(funct3, 7)))));
        B store = d.band(d.is(opcode, OP_STORE), d.ltu(funct3, d.k(3)));
        B opImm = d.is(opcode, OP_IMM);
        V funct7 = d.bits(inst, 31, 25);
        B mulDiv = d.band(d.is(opcode, OP_OP), d.is(funct7, 1));
        B op = d.band(d.is(opcode, OP_OP), d.bor(d.is(funct7, 0), d.band(d.is(funct7, 0x20), d.bor(d.is(funct3, 0), d.is(funct3, 5)))));
        B fence = d.is(opcode, OP_FENCE);
        B system = d.is(opcode, OP_SYSTEM);
        B ecall = d.is(inst, 0x0000_0073L), ebreak = d.is(inst, 0x0010_0073L);
        B mret = d.is(inst, 0x3020_0073L), wfi = d.is(inst, 0x1050_0073L);
        B csr = d.band(system, d.bnot(d.bor(d.is(funct3, 0), d.is(funct3, 4))));
        B halt = zicsr ? ebreak : d.band(system, d.is(funct3, 0));

        V immI = d.sext(d.bits(inst, 31, 20), 12);
        V immS = d.sext(d.or(d.shl(d.bits(inst, 31, 25), 5), d.bits(inst, 11, 7)), 12);
        V immB = d.sext(d.or(d.or(d.shl(d.bits(inst, 31, 31), 12), d.shl(d.bits(inst, 7, 7), 11)),
                d.or(d.shl(d.bits(inst, 30, 25), 5), d.shl(d.bits(inst, 11, 8), 1))), 13);
        V immU = d.and(inst, d.k(0xFFFF_F000L));
        V immJ = d.sext(d.or(d.or(d.shl(d.bits(inst, 31, 31), 20), d.shl(d.bits(inst, 19, 12), 12)),
                d.or(d.shl(d.bits(inst, 20, 20), 11), d.shl(d.bits(inst, 30, 21), 1))), 21);
        V imm = d.mux(d.bor(lui, auipc), immU, d.mux(jal, immJ, d.mux(branch, immB, d.mux(store, immS, immI))));

        B known = zicsr ? any(d, lui, auipc, jal, jalr, branch, load, store, opImm, op, fence, mulDiv, ecall, ebreak, mret, wfi, csr)
                : any(d, lui, auipc, jal, jalr, branch, load, store, opImm, op, fence, halt, mulDiv);
        B writes = d.band(any(d, lui, auipc, jal, jalr, load, opImm, op, mulDiv), d.ne(rd, d.k(0)));
        B usesRs1 = any(d, jalr, branch, load, store, opImm, op, mulDiv, zicsr ? d.band(csr, d.bnot(d.bit(funct3, 2))) : d.bk(false));
        B usesRs2 = any(d, branch, store, op, mulDiv);
        return new Decoded<>(rd, rs1, rs2, funct3, imm, alt, lui, auipc, jal, jalr, branch, load, store, opImm, op,
                fence, system, writes, usesRs1, usesRs2, zicsr ? halt : d.bor(halt, d.bnot(known)), d.bnot(known), mulDiv,
                ecall, ebreak, mret, wfi);
    }

    @SafeVarargs
    private static <V, B> B any(Dom<V, B> d, B... bs) {
        B acc = bs[0];
        for (int i = 1; i < bs.length; i++) acc = d.bor(acc, bs[i]);
        return acc;
    }

    /**
     * The result of the execute step. {@code trap}: the instruction traps (it writes no
     * register and no memory); {@code writesRd} is the decoded write gated by the trap;
     * {@code nextPc} is the architectural next pc.
     */
    public record Exec<V, B>(V result, B redirect, V target, V memAddr, B trap, B writesRd, V nextPc) {}

    /** ALU, branch resolution and address generation; {@code a}/{@code b} are rs1/rs2 values. */
    public static <V, B> Exec<V, B> execute(Dom<V, B> d, Decoded<V, B> dec, V pc, V a, V b) {
        V f3 = dec.funct3();
        V opB = d.mux(dec.op(), b, dec.imm());
        B sub = d.band(dec.op(), dec.alt());
        B sra = dec.alt();
        V alu = sel8(d, f3,
                d.mux(sub, d.sub(a, opB), d.add(a, opB)),
                d.sll(a, opB),
                d.fromBool(d.lt(a, opB)),
                d.fromBool(d.ltu(a, opB)),
                d.xor(a, opB),
                d.mux(sra, d.sra(a, opB), d.srl(a, opB)),
                d.or(a, opB),
                d.and(a, opB));
        V pc4 = d.add(pc, d.k(4));
        V result = d.mux(dec.lui(), dec.imm(),
                d.mux(dec.auipc(), d.add(pc, dec.imm()),
                d.mux(d.bor(dec.jal(), dec.jalr()), pc4, alu)));
        B eq = d.eq(a, b), lt = d.lt(a, b), ltu = d.ltu(a, b);
        B cond = d.bmux(d.is(f3, 0), eq, d.bmux(d.is(f3, 1), d.bnot(eq),
                d.bmux(d.is(f3, 4), lt, d.bmux(d.is(f3, 5), d.bnot(lt),
                d.bmux(d.is(f3, 6), ltu, d.bnot(ltu))))));
        B redirect = any(d, dec.jal(), dec.jalr(), d.band(dec.branch(), cond));
        V target = d.mux(dec.jalr(), d.and(d.add(a, dec.imm()), d.k(~1L)), d.add(pc, dec.imm()));
        V memAddr = d.add(a, dec.imm());
        B misTarget = d.band(redirect, d.bit(target, 1));
        V size = d.bits(f3, 1, 0);
        B misMem = d.band(d.bor(dec.load(), dec.store()),
                d.bor(d.band(d.is(size, 1), d.bit(memAddr, 0)), d.band(d.is(size, 2), d.ne(d.bits(memAddr, 1, 0), d.k(0)))));
        B trap = any(d, dec.halt(), misTarget, misMem);
        V nextPc = d.mux(redirect, target, pc4);
        return new Exec<>(result, redirect, target, memAddr, trap, d.band(dec.writesRd(), d.bnot(trap)), nextPc);
    }

    /** The RVFI view of a memory access: word-aligned address, byte masks, data in lane positions. */
    public record MemAccess<V>(V addr, V rmask, V wmask, V wdata) {}

    /** RVFI memory fields for an aligned access (zero masks when the instruction traps or does not access memory). */
    public static <V, B> MemAccess<V> memAccess(Dom<V, B> d, Decoded<V, B> dec, Exec<V, B> ex, V rs2) {
        V size = d.bits(dec.funct3(), 1, 0);
        V bytes = d.mux(d.is(size, 0), d.k(1), d.mux(d.is(size, 1), d.k(3), d.k(15)));
        V off = d.bits(ex.memAddr(), 1, 0);
        V lanes = d.sll(bytes, off);
        B ok = d.bnot(ex.trap());
        V rmask = d.mux(d.band(dec.load(), ok), lanes, d.k(0));
        V wmask = d.mux(d.band(dec.store(), ok), lanes, d.k(0));
        V wdata = d.mux(d.band(dec.store(), ok), d.sll(rs2, d.shl(off, 3)), d.k(0));
        return new MemAccess<>(d.and(ex.memAddr(), d.k(~3L)), rmask, wmask, wdata);
    }

    private static <V, B> V sel8(Dom<V, B> d, V sel, V v0, V v1, V v2, V v3, V v4, V v5, V v6, V v7) {
        B s0 = d.bit(sel, 0), s1 = d.bit(sel, 1), s2 = d.bit(sel, 2);
        V lo = d.mux(s1, d.mux(s0, v3, v2), d.mux(s0, v1, v0));
        V hi = d.mux(s1, d.mux(s0, v7, v6), d.mux(s0, v5, v4));
        return d.mux(s2, hi, lo);
    }

    private static <V, B> V sel4(Dom<V, B> d, V sel, V v0, V v1, V v2, V v3) {
        B s0 = d.bit(sel, 0), s1 = d.bit(sel, 1);
        return d.mux(s1, d.mux(s0, v3, v2), d.mux(s0, v1, v0));
    }

    /** Extract a load result from the 32-bit memory word containing {@code addr}. */
    public static <V, B> V loadValue(Dom<V, B> d, V funct3, V addr, V word) {
        V shifted = d.srl(word, d.shl(d.bits(addr, 1, 0), 3));
        V sizeBits = d.bits(funct3, 1, 0);
        V signedV = d.mux(d.is(sizeBits, 0), d.sext(shifted, 8), d.mux(d.is(sizeBits, 1), d.sext(shifted, 16), word));
        V unsignedV = d.mux(d.is(sizeBits, 0), d.bits(shifted, 7, 0), d.bits(shifted, 15, 0));
        return d.mux(d.bit(funct3, 2), unsignedV, signedV);
    }

    /** Merge store data into the old memory word. */
    public static <V, B> V storeMerge(Dom<V, B> d, V funct3, V addr, V oldWord, V data) {
        V sizeBits = d.bits(funct3, 1, 0);
        V mask = d.mux(d.is(sizeBits, 0), d.k(0xFF), d.mux(d.is(sizeBits, 1), d.k(0xFFFF), d.k(0xFFFF_FFFFL)));
        V sh = d.shl(d.bits(addr, 1, 0), 3);
        V m = d.sll(mask, sh);
        return d.or(d.and(oldWord, d.xor(m, d.k(0xFFFF_FFFFL))), d.and(d.sll(data, sh), m));
    }

    // ------------------------------------------------------------------ M extension

    /**
     * The first half of the multiply/divide unit: four 16x16 partial products and the first 16
     * steps of a restoring division. Pipelines can put the two halves in different stages.
     */
    public record MulDivHalf<V, B>(V a, V b, V funct3, V p00, V p01, V p10, V p11,
                                   V rem, V quo, V dividend, V divisor, B negQ, B negR, B byZero) {}

    /** {@code a} for unsigned operations, |a| for signed ones. */
    private static <V, B> V magnitude(Dom<V, B> d, V a, B signed) {
        return d.mux(d.band(signed, d.lt(a, d.k(0))), d.sub(d.k(0), a), a);
    }

    /** {@code steps} iterations of restoring division, consuming dividend bits from the top. */
    static <V, B> V[] divSteps(Dom<V, B> d, V rem, V quo, V dividend, V divisor, int steps) {
        for (int i = 0; i < steps; i++) {
            B top = d.bit(rem, 31);
            V shifted = d.or(d.shl(rem, 1), d.bits(dividend, 31, 31));
            dividend = d.shl(dividend, 1);
            B take = d.bor(top, d.bnot(d.ltu(shifted, divisor)));
            rem = d.mux(take, d.sub(shifted, divisor), shifted);
            quo = d.or(d.shl(quo, 1), d.fromBool(take));
        }
        @SuppressWarnings("unchecked")
        V[] out = (V[]) new Object[]{rem, quo, dividend};
        return out;
    }

    public static <V, B> MulDivHalf<V, B> mulDivStart(Dom<V, B> d, Decoded<V, B> dec, V a, V b) {
        return mulDivStart(d, dec, a, b, 16);
    }

    /** The first half with {@code divSteps} division steps (0 when a sequential divider runs them). */
    public static <V, B> MulDivHalf<V, B> mulDivStart(Dom<V, B> d, Decoded<V, B> dec, V a, V b, int divSteps) {
        V f3 = dec.funct3();
        V a0 = d.bits(a, 15, 0), a1 = d.bits(a, 31, 16), b0 = d.bits(b, 15, 0), b1 = d.bits(b, 31, 16);
        B signedDiv = d.bor(d.is(f3, 4), d.is(f3, 6));
        V da = magnitude(d, a, signedDiv), db = magnitude(d, b, signedDiv);
        B negA = d.band(signedDiv, d.lt(a, d.k(0))), negB = d.band(signedDiv, d.lt(b, d.k(0)));
        V[] st = divSteps(d, d.k(0), d.k(0), da, db, divSteps);
        return new MulDivHalf<>(a, b, f3, d.mul16(a0, b0), d.mul16(a0, b1), d.mul16(a1, b0), d.mul16(a1, b1),
                st[0], st[1], st[2], db, d.bor(d.band(negA, d.bnot(negB)), d.band(d.bnot(negA), negB)), negA, d.eq(b, d.k(0)));
    }

    private static <V, B> V carry(Dom<V, B> d, V x, V y) {
        return d.fromBool(d.ltu(d.add(x, y), x));
    }

    /** The second half: the 64-bit product and the last 16 division steps; the M instruction's result. */
    public static <V, B> V mulDivFinish(Dom<V, B> d, MulDivHalf<V, B> h) {
        return mulDivFinish(d, h, 16);
    }

    /** The second half running the last {@code divSteps} division steps (0: the quotient is complete). */
    public static <V, B> V mulDivFinish(Dom<V, B> d, MulDivHalf<V, B> h, int divSteps) {
        V f3 = h.funct3();
        if (d.altops()) {
            // riscv-formal RISCV_FORMAL_ALTOPS: (rs1 +/- rs2) ^ mask, per instruction
            V sum = d.add(h.a(), h.b()), diff = d.sub(h.a(), h.b());
            return sel8(d, f3,
                    d.xor(sum, d.k(0x5876063eL)), d.xor(sum, d.k(0xf6583fb7L)), d.xor(diff, d.k(0xecfbe137L)),
                    d.xor(sum, d.k(0x949ce5e8L)), d.xor(diff, d.k(0x7f8529ecL)), d.xor(diff, d.k(0x10e8fd70L)),
                    d.xor(diff, d.k(0x8da68fa5L)), d.xor(diff, d.k(0x3138d0e1L)));
        }
        // product: p00 + (p01 + p10) << 16 + p11 << 32
        V mid1 = d.shl(h.p01(), 16), mid2 = d.shl(h.p10(), 16);
        V lo1 = d.add(h.p00(), mid1);
        V c1 = carry(d, h.p00(), mid1);
        V lo = d.add(lo1, mid2);
        V c2 = carry(d, lo1, mid2);
        V hi = d.add(d.add(h.p11(), d.srl(h.p01(), d.k(16))), d.add(d.srl(h.p10(), d.k(16)), d.add(c1, c2)));
        V mulh = d.sub(d.sub(hi, d.mux(d.lt(h.a(), d.k(0)), h.b(), d.k(0))), d.mux(d.lt(h.b(), d.k(0)), h.a(), d.k(0)));
        V mulhsu = d.sub(hi, d.mux(d.lt(h.a(), d.k(0)), h.b(), d.k(0)));
        // quotient and remainder
        V[] st = divSteps(d, h.rem(), h.quo(), h.dividend(), h.divisor(), divSteps);
        V rem = st[0], quo = st[1];
        V q = d.mux(h.byZero(), d.k(0xFFFF_FFFFL), d.mux(h.negQ(), d.sub(d.k(0), quo), quo));
        V r = d.mux(h.byZero(), h.a(), d.mux(h.negR(), d.sub(d.k(0), rem), rem));
        return sel8(d, f3, lo, mulh, mulhsu, hi, q, q, r, r);
    }

    /**
     * The multiply half of {@link #mulDivFinish}: the result of MUL, MULH, MULHSU or MULHU (funct3 0-3).
     * With {@link #divFinish}, {@code mux(funct3[2], divFinish, mulFinish)} is {@code mulDivFinish}; a schedule can
     * then finish multiplies and divides in different stages.
     */
    public static <V, B> V mulFinish(Dom<V, B> d, MulDivHalf<V, B> h) {
        V f3 = h.funct3();
        if (d.altops()) {
            V sum = d.add(h.a(), h.b()), diff = d.sub(h.a(), h.b());
            return sel4(d, f3, d.xor(sum, d.k(0x5876063eL)), d.xor(sum, d.k(0xf6583fb7L)), d.xor(diff, d.k(0xecfbe137L)),
                    d.xor(sum, d.k(0x949ce5e8L)));
        }
        V mid1 = d.shl(h.p01(), 16), mid2 = d.shl(h.p10(), 16);
        V lo1 = d.add(h.p00(), mid1);
        V c1 = carry(d, h.p00(), mid1);
        V lo = d.add(lo1, mid2);
        V c2 = carry(d, lo1, mid2);
        V hi = d.add(d.add(h.p11(), d.srl(h.p01(), d.k(16))), d.add(d.srl(h.p10(), d.k(16)), d.add(c1, c2)));
        V mulh = d.sub(d.sub(hi, d.mux(d.lt(h.a(), d.k(0)), h.b(), d.k(0))), d.mux(d.lt(h.b(), d.k(0)), h.a(), d.k(0)));
        V mulhsu = d.sub(hi, d.mux(d.lt(h.a(), d.k(0)), h.b(), d.k(0)));
        return sel4(d, f3, lo, mulh, mulhsu, hi);
    }

    /** The divide half of {@link #mulDivFinish}: DIV, DIVU, REM or REMU (funct3 4-7), after the last {@code divSteps} steps. */
    public static <V, B> V divFinish(Dom<V, B> d, MulDivHalf<V, B> h, int divSteps) {
        V f3 = h.funct3();
        if (d.altops()) {
            V diff = d.sub(h.a(), h.b());
            return sel4(d, f3, d.xor(diff, d.k(0x7f8529ecL)), d.xor(diff, d.k(0x10e8fd70L)), d.xor(diff, d.k(0x8da68fa5L)),
                    d.xor(diff, d.k(0x3138d0e1L)));
        }
        V[] st = divSteps(d, h.rem(), h.quo(), h.dividend(), h.divisor(), divSteps);
        V rem = st[0], quo = st[1];
        V q = d.mux(h.byZero(), d.k(0xFFFF_FFFFL), d.mux(h.negQ(), d.sub(d.k(0), quo), quo));
        V r = d.mux(h.byZero(), h.a(), d.mux(h.negR(), d.sub(d.k(0), rem), rem));
        return sel4(d, f3, q, q, r, r);
    }

    /** The multiply's 64-bit sum ({@link #mulSum}): the low and high words before the signed corrections. */
    public record MulSum<V>(V lo, V hi, V a, V b, V funct3) {}

    /**
     * The first half of {@link #mulFinish}: the partial products summed into the unsigned 64-bit product. With
     * {@link #mulFix} a schedule can put the long carry chains and the corrections in different stages.
     * (The same arithmetic as {@link #mulFinish}, kept separate so that cores using that are unchanged.)
     */
    public static <V, B> MulSum<V> mulSum(Dom<V, B> d, MulDivHalf<V, B> h) {
        if (d.altops()) return new MulSum<>(h.a(), h.b(), h.a(), h.b(), h.funct3());
        V mid1 = d.shl(h.p01(), 16), mid2 = d.shl(h.p10(), 16);
        V lo1 = d.add(h.p00(), mid1);
        V c1 = carry(d, h.p00(), mid1);
        V lo = d.add(lo1, mid2);
        V c2 = carry(d, lo1, mid2);
        V hi = d.add(d.add(h.p11(), d.srl(h.p01(), d.k(16))), d.add(d.srl(h.p10(), d.k(16)), d.add(c1, c2)));
        return new MulSum<>(lo, hi, h.a(), h.b(), h.funct3());
    }

    /** The second half of {@link #mulFinish}: MULH's and MULHSU's corrections, and the result select. */
    public static <V, B> V mulFix(Dom<V, B> d, MulSum<V> m) {
        V f3 = m.funct3();
        if (d.altops()) {
            V sum = d.add(m.a(), m.b()), diff = d.sub(m.a(), m.b());
            return sel4(d, f3, d.xor(sum, d.k(0x5876063eL)), d.xor(sum, d.k(0xf6583fb7L)), d.xor(diff, d.k(0xecfbe137L)),
                    d.xor(sum, d.k(0x949ce5e8L)));
        }
        V hi = m.hi();
        V mulh = d.sub(d.sub(hi, d.mux(d.lt(m.a(), d.k(0)), m.b(), d.k(0))), d.mux(d.lt(m.b(), d.k(0)), m.a(), d.k(0)));
        V mulhsu = d.sub(hi, d.mux(d.lt(m.a(), d.k(0)), m.b(), d.k(0)));
        return sel4(d, f3, m.lo(), mulh, mulhsu, hi);
    }
}
