package org.eninehq.tessera.riscv;

/**
 * Machine-mode privileged architecture for RV32IM cores, written once for any {@link Dom}
 * like {@link Rv32i}: the Zicsr instructions, exceptions (illegal instruction, misaligned
 * fetch/load/store, ECALL), MRET, WFI and interrupts (external, through the {@code irq} input,
 * and software, through {@code mip.MSIP}). EBREAK halts the machine (a debugger breakpoint).
 *
 * <p>Supported CSRs: mstatus (MIE, MPIE; MPP reads as M), misa, mie, mtvec (direct mode),
 * mscratch, mepc, mcause, mtval, mip, mcycle(h), minstret(h), their user read-only aliases
 * cycle(h)/instret(h), and mvendorid/marchid/mimpid/mhartid (zero).
 */
public final class Zicsr {
    private Zicsr() {}

    public static final int MSTATUS = 0x300, MISA = 0x301, MIE = 0x304, MTVEC = 0x305, MSCRATCH = 0x340, MEPC = 0x341,
            MCAUSE = 0x342, MTVAL = 0x343, MIP = 0x344, MCYCLE = 0xB00, MINSTRET = 0xB02, MCYCLEH = 0xB80,
            MINSTRETH = 0xB82, CYCLE = 0xC00, INSTRET = 0xC02, CYCLEH = 0xC80, INSTRETH = 0xC82,
            MVENDORID = 0xF11, MARCHID = 0xF12, MIMPID = 0xF13, MHARTID = 0xF14;

    /** RV32IM in misa. */
    public static final long MISA_VALUE = 0x4000_1100L;

    public static final int CAUSE_MISALIGNED_FETCH = 0, CAUSE_ILLEGAL = 2, CAUSE_MISALIGNED_LOAD = 4,
            CAUSE_MISALIGNED_STORE = 6, CAUSE_ECALL_M = 11;

    /** The machine state that is not in the register file. */
    public record Csrs<V>(V mstatus, V mie, V mtvec, V mscratch, V mepc, V mcause, V mtval, V mip,
                          V mcycle, V mcycleh, V minstret, V minstreth) {}

    public static <V, B> Csrs<V> reset(Dom<V, B> d) {
        V z = d.k(0);
        return new Csrs<>(d.k(0x1800), z, z, z, z, z, z, z, z, z, z, z);
    }

    /**
     * What an instruction does to the machine state, beyond the RV32IM datapath.
     *
     * @param next      CSRs afterwards (including one more cycle, and one more instret if it retires)
     * @param writesRd  the instruction writes rd: a CSR read or the normal result, and it commits
     * @param csrValue  rd value of a CSR instruction (the old CSR value)
     * @param isCsr     the instruction is a CSR instruction
     * @param redirect  control goes to {@code target} (trap, interrupt or MRET)
     * @param exception an exception is taken (rvfi_trap)
     * @param interrupt an interrupt is taken before this instruction executes
     * @param commits   the instruction has its normal effects (stores, register writes)
     * @param halt      EBREAK: the machine stops
     * @param csrWrites a CSR instruction that writes its CSR (and commits)
     * @param csrWritten the value it writes, before WARL fields are masked (what RVFI reports)
     */
    public record Effect<V, B>(Csrs<V> next, B writesRd, V csrValue, B isCsr, B redirect, V target,
                               B exception, B interrupt, B commits, B halt, B csrWrites, V csrWritten) {}

    private static <V, B> B any(Dom<V, B> d, B a, B b) {
        return d.bor(a, b);
    }

    /**
     * The effect of instruction {@code inst} at {@code pc}. {@code dec} must come from
     * {@link Rv32i#decode(Dom, Object, boolean)} with Zicsr enabled. {@code irq} is the external
     * interrupt line (MEIP); {@code cycle} says whether this call also advances mcycle.
     */
    public static <V, B> Effect<V, B> step(Dom<V, B> d, Rv32i.Decoded<V, B> dec, Rv32i.Exec<V, B> ex, V inst, V pc,
                                           V rs1v, Csrs<V> c, B irq, B valid) {
        return step(d, dec, ex, inst, pc, rs1v, c, irq, valid, null, null);
    }

    /**
     * As {@link #step(Dom, Rv32i.Decoded, Rv32i.Exec, Object, Object, Object, Csrs, Object, Object)}, for a core that
     * also executes a second instruction in the same cycle: {@code moreCycle} and {@code moreRetire} (null: none)
     * count it in mcycle and minstret, in the same addition as this instruction (one carry chain, not two).
     */
    public static <V, B> Effect<V, B> step(Dom<V, B> d, Rv32i.Decoded<V, B> dec, Rv32i.Exec<V, B> ex, V inst, V pc,
                                           V rs1v, Csrs<V> c, B irq, B valid, B moreCycle, B moreRetire) {
        V f3 = dec.funct3();
        V addr = d.bits(inst, 31, 20);
        V rs1Field = d.bits(inst, 19, 15);
        B isCsr = d.band(dec.system(), d.bnot(d.bor(d.is(f3, 0), d.is(f3, 4))));
        V src = d.mux(d.bit(f3, 2), rs1Field, rs1v);
        V op = d.bits(f3, 1, 0);
        B writes = d.bor(d.is(op, 1), d.ne(rs1Field, d.k(0)));

        // CSR read (and whether the address exists)
        V mipNow = d.or(d.and(c.mip(), d.k(~(1L << 11) & 0xFFFF_FFFFL)), d.mux(irq, d.k(1L << 11), d.k(0)));
        V read = d.k(0);
        B legal = d.bk(false);
        long[] addrs = {MSTATUS, MISA, MIE, MTVEC, MSCRATCH, MEPC, MCAUSE, MTVAL, MIP, MCYCLE, MINSTRET, MCYCLEH, MINSTRETH,
                CYCLE, INSTRET, CYCLEH, INSTRETH, MVENDORID, MARCHID, MIMPID, MHARTID};
        java.util.List<V> values = java.util.List.of(c.mstatus(), d.k(MISA_VALUE), c.mie(), c.mtvec(), c.mscratch(), c.mepc(),
                c.mcause(), c.mtval(), mipNow, c.mcycle(), c.minstret(), c.mcycleh(), c.minstreth(),
                c.mcycle(), c.minstret(), c.mcycleh(), c.minstreth(), d.k(0), d.k(0), d.k(0), d.k(0));
        for (int i = addrs.length - 1; i >= 0; i--) {
            B hit = d.is(addr, addrs[i]);
            read = d.mux(hit, values.get(i), read);
            legal = d.bor(legal, hit);
        }
        B readOnly = d.is(d.bits(addr, 11, 10), 3);
        B illegalCsr = d.band(isCsr, d.bor(d.bnot(legal), d.band(writes, readOnly)));
        V written = d.mux(d.is(op, 1), src, d.mux(d.is(op, 2), d.or(read, src), d.and(read, d.xor(src, d.k(0xFFFF_FFFFL)))));

        // exceptions (the RV32IM ones, minus EBREAK which halts) and interrupts
        B ebreak = dec.halt();
        B baseException = any(d, d.bor(d.band(ex.trap(), d.bnot(ebreak)), dec.ecall()), dec.illegal());
        B exception = d.band(valid, any(d, baseException, illegalCsr));
        B mie = d.bit(c.mstatus(), 3);
        V pending = d.and(mipNow, c.mie());
        B interrupt = d.band(valid, d.band(mie, d.ne(d.and(pending, d.k(0x888)), d.k(0))));
        V intCause = d.mux(d.bit(pending, 11), d.k(0x8000_000BL), d.mux(d.bit(pending, 3), d.k(0x8000_0003L), d.k(0x8000_0007L)));
        B misMem = d.band(d.bor(dec.load(), dec.store()), d.bnot(dec.illegal()));
        V excCause = d.mux(d.bor(dec.illegal(), illegalCsr), d.k(CAUSE_ILLEGAL),
                d.mux(dec.ecall(), d.k(CAUSE_ECALL_M),
                d.mux(misMem, d.mux(dec.load(), d.k(CAUSE_MISALIGNED_LOAD), d.k(CAUSE_MISALIGNED_STORE)), d.k(CAUSE_MISALIGNED_FETCH))));
        V excTval = d.mux(d.bor(dec.illegal(), illegalCsr), inst,
                d.mux(dec.ecall(), d.k(0), d.mux(misMem, ex.memAddr(), ex.target())));
        B trap = d.bor(interrupt, exception);
        B mret = d.band(valid, d.band(dec.mret(), d.bnot(interrupt)));
        B commits = d.band(valid, d.bnot(d.bor(trap, ebreak)));

        // CSR updates: traps, MRET, CSR writes, counters
        V mstatus = c.mstatus();
        V trapStatus = d.or(d.and(mstatus, d.k(~0x88L & 0xFFFF_FFFFL)), d.mux(mie, d.k(0x80), d.k(0)));
        V mretStatus = d.or(d.and(mstatus, d.k(~0x88L & 0xFFFF_FFFFL)), d.or(d.k(0x80), d.mux(d.bit(mstatus, 7), d.k(0x8), d.k(0))));
        B doWrite = d.band(commits, d.band(isCsr, writes));
        Csrs<V> w = write(d, c, addr, written, doWrite);
        V nextStatus = d.mux(trap, trapStatus, d.mux(mret, mretStatus, w.mstatus()));
        V nextMepc = d.mux(trap, pc, w.mepc());
        V nextCause = d.mux(trap, d.mux(interrupt, intCause, excCause), w.mcause());
        V nextTval = d.mux(trap, d.mux(interrupt, d.k(0), excTval), w.mtval());
        // counters: one more cycle per call when valid; one more instret when the instruction retires
        V instret = w.minstret(), instreth = w.minstreth();
        V cyc = w.mcycle(), cych = w.mcycleh();
        V nextInstret, nextInstreth, nextCycle, nextCycleh;
        if (moreRetire == null) {
            V instretInc = d.add(instret, d.k(1));
            B instretWrap = d.band(commits, d.is(instret, 0xFFFF_FFFFL));
            nextInstret = d.mux(commits, instretInc, instret);
            nextInstreth = d.mux(instretWrap, d.add(instreth, d.k(1)), instreth);
            nextCycle = d.mux(valid, d.add(cyc, d.k(1)), cyc);
            nextCycleh = d.mux(d.band(valid, d.is(cyc, 0xFFFF_FFFFL)), d.add(cych, d.k(1)), cych);
        } else {
            // +1 and +2 and their carries come from the old values; the late "this one and the other one count"
            // signals only choose among them (as for one instruction, no carry chain after them)
            nextInstret = plus012(d, instret, commits, moreRetire);
            nextInstreth = d.mux(carry012(d, instret, commits, moreRetire), d.add(instreth, d.k(1)), instreth);
            nextCycle = plus012(d, cyc, valid, moreCycle);
            nextCycleh = d.mux(carry012(d, cyc, valid, moreCycle), d.add(cych, d.k(1)), cych);
        }
        var next = new Csrs<>(nextStatus, w.mie(), w.mtvec(), w.mscratch(), nextMepc, nextCause, nextTval, w.mip(),
                nextCycle, nextCycleh, nextInstret, nextInstreth);
        B redirect = d.bor(trap, mret);
        V target = d.mux(trap, d.and(c.mtvec(), d.k(~3L & 0xFFFF_FFFFL)), c.mepc());
        B writesRd = d.band(commits, d.bor(d.band(isCsr, d.ne(dec.rd(), d.k(0))), ex.writesRd()));
        return new Effect<>(next, writesRd, read, isCsr, redirect, target, d.band(exception, d.bnot(interrupt)), interrupt,
                commits, d.band(valid, d.band(ebreak, d.bnot(interrupt))), doWrite, written);
    }

    /** {@code v} plus one for each of {@code a} and {@code b}. */
    private static <V, B> V plus012(Dom<V, B> d, V v, B a, B b) {
        return d.mux(d.band(a, b), d.add(v, d.k(2)), d.mux(d.bor(a, b), d.add(v, d.k(1)), v));
    }

    /** Whether {@link #plus012} wraps around 32 bits. */
    private static <V, B> B carry012(Dom<V, B> d, V v, B a, B b) {
        return d.bor(d.band(d.bor(a, b), d.is(v, 0xFFFF_FFFFL)), d.band(d.band(a, b), d.is(v, 0xFFFF_FFFEL)));
    }

    /** CSRs after a CSR instruction writes {@code value} to {@code addr} (WARL fields masked). */
    private static <V, B> Csrs<V> write(Dom<V, B> d, Csrs<V> c, V addr, V value, B en) {
        java.util.function.BiFunction<Long, V, V> upd = (a, old) -> d.mux(d.band(en, d.is(addr, a)), value, old);
        V mstatus = d.mux(d.band(en, d.is(addr, MSTATUS)), d.or(d.and(value, d.k(0x88)), d.k(0x1800)), c.mstatus());
        V mie = d.mux(d.band(en, d.is(addr, MIE)), d.and(value, d.k(0x888)), c.mie());
        V mtvec = d.mux(d.band(en, d.is(addr, MTVEC)), d.and(value, d.k(~3L & 0xFFFF_FFFFL)), c.mtvec());
        V mepc = d.mux(d.band(en, d.is(addr, MEPC)), d.and(value, d.k(~3L & 0xFFFF_FFFFL)), c.mepc());
        V mip = d.mux(d.band(en, d.is(addr, MIP)), d.and(value, d.k(0x8)), c.mip());
        return new Csrs<>(mstatus, mie, mtvec, upd.apply((long) MSCRATCH, c.mscratch()), mepc,
                upd.apply((long) MCAUSE, c.mcause()), upd.apply((long) MTVAL, c.mtval()), mip,
                upd.apply((long) MCYCLE, c.mcycle()), upd.apply((long) MCYCLEH, c.mcycleh()),
                upd.apply((long) MINSTRET, c.minstret()), upd.apply((long) MINSTRETH, c.minstreth()));
    }
}
