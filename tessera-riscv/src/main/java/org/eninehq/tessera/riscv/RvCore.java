package org.eninehq.tessera.riscv;

import static org.eninehq.tessera.core.Hw.mux;

import org.eninehq.tessera.core.Bool;
import org.eninehq.tessera.core.Hw;
import org.eninehq.tessera.core.Mem;
import org.eninehq.tessera.core.Module;
import org.eninehq.tessera.core.UInt;
import org.eninehq.tessera.stdlib.LiveValueTable;

/**
 * Common interface of the RV32I cores: Harvard memories initialised from the same image, a
 * register file, a halt flag (set when an instruction traps), and a RISC-V Formal Interface
 * (RVFI) port that reports every retired instruction, for riscv-formal and for comparison
 * with {@link Rv32iIss}.
 */
public abstract class RvCore extends Module {
    public final Bool halted = output("halted", Bool.TYPE);

    public final Bool rvfiValid = output("rvfi_valid", Bool.TYPE);
    public final UInt rvfiOrder = output("rvfi_order", UInt.w(64));
    public final UInt rvfiInsn = output("rvfi_insn", UInt.w(32));
    public final Bool rvfiTrap = output("rvfi_trap", Bool.TYPE);
    public final Bool rvfiHalt = output("rvfi_halt", Bool.TYPE);
    public final Bool rvfiIntr = output("rvfi_intr", Bool.TYPE);
    public final UInt rvfiMode = output("rvfi_mode", UInt.w(2));
    public final UInt rvfiIxl = output("rvfi_ixl", UInt.w(2));
    public final UInt rvfiRs1Addr = output("rvfi_rs1_addr", UInt.w(5));
    public final UInt rvfiRs2Addr = output("rvfi_rs2_addr", UInt.w(5));
    public final UInt rvfiRs1Rdata = output("rvfi_rs1_rdata", UInt.w(32));
    public final UInt rvfiRs2Rdata = output("rvfi_rs2_rdata", UInt.w(32));
    public final UInt rvfiRdAddr = output("rvfi_rd_addr", UInt.w(5));
    public final UInt rvfiRdWdata = output("rvfi_rd_wdata", UInt.w(32));
    public final UInt rvfiPcRdata = output("rvfi_pc_rdata", UInt.w(32));
    public final UInt rvfiPcWdata = output("rvfi_pc_wdata", UInt.w(32));
    public final UInt rvfiMemAddr = output("rvfi_mem_addr", UInt.w(32));
    public final UInt rvfiMemRmask = output("rvfi_mem_rmask", UInt.w(4));
    public final UInt rvfiMemWmask = output("rvfi_mem_wmask", UInt.w(4));
    public final UInt rvfiMemRdata = output("rvfi_mem_rdata", UInt.w(32));
    public final UInt rvfiMemWdata = output("rvfi_mem_wdata", UInt.w(32));

    /** Short names used by the simulation tests. */
    public final Bool retireValid = rvfiValid;
    public final UInt retirePc = rvfiPcRdata, retireInsn = rvfiInsn, retireRd = rvfiRdAddr, retireRdValue = rvfiRdWdata;

    public final Mem<UInt> imem, dmem, regs;
    protected final int addrBits;
    /** Machine-mode CSRs, traps and interrupts ({@link Zicsr}); otherwise every trap halts. */
    public final boolean zicsr;
    /** The external interrupt input (only with Zicsr). */
    public final Bool irq;

    /** @param memWords words of instruction and data memory (a power of two); image may be null. */
    protected RvCore(int memWords, int[] image) {
        this(memWords, image, false);
    }

    /**
     * Build options: machine mode ({@link Zicsr}), and a debug port through which a host
     * (an FPGA's ARM, a JTAG module) loads programs and reads results while the core is held in reset;
     * {@code seqDiv} divides 2 bits per cycle, stalling, instead of with 32 subtractor stages
     * (which limit an FPGA to about 13 MHz).
     */
    public record Config(boolean zicsr, boolean bram, boolean debug, boolean seqDiv) {
        public static final Config DEFAULT = new Config(false, false, false, false);

        public Config(boolean zicsr, boolean bram, boolean debug) {
            this(zicsr, bram, debug, false);
        }

        /** The ZedBoard build: machine mode, block RAM, debug port, and a sequential divider. */
        public static Config fpga() {
            return new Config(true, true, true, true);
        }
    }

    /** Debug port (only with {@code Config.debug}): {@code dbg_en} gives the memories to the host. */
    public final Bool dbgEn, dbgWe, dbgSelDmem;
    public final UInt dbgAddr, dbgWdata, dbgRdata, dbgRegData;

    protected RvCore(int memWords, int[] image, boolean zicsr) {
        this(memWords, image, new Config(zicsr, false, false));
    }

    protected RvCore(int memWords, int[] image, Config config) {
        this(memWords, image, config, false);
    }

    /**
     * The register file as a live-value table ({@link LiveValueTable}): {@link #lvtRegs} instead of
     * {@link #regs} (null then). The subclass declares its reads and writes and then closes it.
     */
    public final LiveValueTable lvtRegs;

    protected RvCore(int memWords, int[] image, Config config, boolean lvt) {
        boolean zicsr = config.zicsr();
        this.zicsr = zicsr;
        this.irq = zicsr ? input("irq", Bool.TYPE) : null;
        boolean dbg = config.debug();
        int aw = Math.max(1, Integer.numberOfTrailingZeros(memWords));
        dbgEn = dbg ? input("dbg_en", Bool.TYPE) : null;
        dbgWe = dbg ? input("dbg_we", Bool.TYPE) : null;
        dbgSelDmem = dbg ? input("dbg_sel_dmem", Bool.TYPE) : null;
        dbgAddr = dbg ? input("dbg_addr", UInt.w(aw)) : null;
        dbgWdata = dbg ? input("dbg_wdata", UInt.w(32)) : null;
        dbgRdata = dbg ? output("dbg_rdata", UInt.w(32)) : null;
        dbgRegData = dbg ? output("dbg_reg_rdata", UInt.w(32)) : null;
        if (Integer.bitCount(memWords) != 1) throw new IllegalArgumentException("memWords must be a power of two");
        imem = mem("imem", UInt.w(32), memWords);
        dmem = mem("dmem", UInt.w(32), memWords);
        regs = lvt ? null : mem("regs", UInt.w(32), 32);
        lvtRegs = lvt ? new LiveValueTable("regs", 32, 32, false) : null;
        if (image != null) {
            long[] init = new long[Math.min(image.length, memWords)];
            for (int i = 0; i < init.length; i++) init[i] = Integer.toUnsignedLong(image[i]);
            imem.init(init);
            dmem.init(init);
        }
        addrBits = imem.addrWidth();
        if (dbg) {
            imem.write(dbgAddr, dbgWdata, dbgEn.and(dbgWe).and(dbgSelDmem.not()));
            UInt ra = dbgAddr.width() >= 5 ? dbgAddr.bits(4, 0) : dbgAddr.zext(5);
            dbgRegData.set(lvt ? lvtRegs.read(ra) : regs.read(ra));
        }
    }

    /**
     * Data memory read: synchronous ({@code sync}) or asynchronous; with a debug port the host
     * uses the same read port while {@code dbg_en}. Returns the core's view (inside a pipeline,
     * tagged with the current stage).
     */
    protected UInt dmemRead(UInt idx, Bool en, boolean sync) {
        if (dbgEn == null) return sync ? dmem.readSync(idx, en) : dmem.read(idx);
        UInt addr = mux(dbgEn, dbgAddr, idx);
        UInt v = sync ? dmem.readSync(addr, en.or(dbgEn)) : dmem.read(addr);
        dbgRdata.set(v);
        return v;
    }

    /** Data memory write; with a debug port the host writes through the same port while {@code dbg_en}. */
    protected void dmemWrite(UInt idx, UInt data, Bool en) {
        if (dbgEn == null) {
            dmem.write(idx, data, en);
            return;
        }
        Bool host = dbgEn.and(dbgWe).and(dbgSelDmem);
        dmem.write(mux(dbgEn, dbgAddr, idx), mux(dbgEn, dbgWdata, data), mux(dbgEn, host, en));
    }

    /**
     * One port of the data memory as a true dual-port RAM ({@link Mem#port}): {@code port} 0 or 1. With a debug port
     * the host uses port 0 while {@code dbg_en} (the core is held in reset then). Returns the synchronous read.
     */
    protected UInt dmemPort(int port, UInt idx, Bool en, Bool we, UInt data) {
        if (dbgEn == null || port == 1) return dmem.port(idx, en, we, data);
        Bool host = dbgEn.and(dbgWe).and(dbgSelDmem);
        UInt v = dmem.port(mux(dbgEn, dbgAddr, idx), en.or(dbgEn), mux(dbgEn, host, we), mux(dbgEn, dbgWdata, data));
        dbgRdata.set(v);
        return v;
    }

    /** Word index of a byte address (wraps around the memory size). */
    protected UInt wordIndex(UInt byteAddr) {
        return byteAddr.bits(addrBits + 1, 2);
    }

    /**
     * Read a register; x0 reads as zero whatever the register file holds (its contents are
     * arbitrary after reset in hardware and in formal verification).
     */
    protected UInt readReg(UInt idx) {
        return mux(idx.eq(0), UInt.lit(0, 32), regs.read(idx));
    }

    /**
     * Everything RVFI reports about one retiring instruction. {@code trap}, {@code halt},
     * {@code intr}, {@code nextPc} and {@code writesRd} default to what {@code ex} says (no Zicsr).
     */
    protected record RetireInfo(Bool valid, UInt insn, UInt pc, Rv32i.Decoded<UInt, Bool> dec, Rv32i.Exec<UInt, Bool> ex,
                                UInt rs1Value, UInt rs2Value, UInt rdValue, UInt memWord,
                                Bool trap, Bool halt, Bool intr, UInt nextPc, Bool writesRd) {
        public RetireInfo(Bool valid, UInt insn, UInt pc, Rv32i.Decoded<UInt, Bool> dec, Rv32i.Exec<UInt, Bool> ex,
                          UInt rs1Value, UInt rs2Value, UInt rdValue, UInt memWord) {
            this(valid, insn, pc, dec, ex, rs1Value, rs2Value, rdValue, memWord, ex.trap(), ex.trap(), Bool.of(false),
                    ex.nextPc(), ex.writesRd());
        }
    }

    private static Rv32i.MemAccess<UInt> gateMemAccess(Rv32i.MemAccess<UInt> m, Bool off) {
        UInt z = UInt.lit(0, 32);
        return new Rv32i.MemAccess<>(m.addr(), mux(off, z, m.rmask()), mux(off, z, m.wmask()), mux(off, z, m.wdata()));
    }

    /** The CSRs reported on RVFI ({@code rvfi_csr_<name>_*}): name, address, and whether it is 64 bits (with an h half). */
    public record RvfiCsr(String name, int addr, boolean wide) {}

    public static final java.util.List<RvfiCsr> RVFI_CSRS = java.util.List.of(
            new RvfiCsr("mstatus", Zicsr.MSTATUS, false), new RvfiCsr("misa", Zicsr.MISA, false),
            new RvfiCsr("mie", Zicsr.MIE, false), new RvfiCsr("mtvec", Zicsr.MTVEC, false),
            new RvfiCsr("mscratch", Zicsr.MSCRATCH, false), new RvfiCsr("mepc", Zicsr.MEPC, false),
            new RvfiCsr("mcause", Zicsr.MCAUSE, false), new RvfiCsr("mtval", Zicsr.MTVAL, false),
            new RvfiCsr("mcycle", Zicsr.MCYCLE, true), new RvfiCsr("minstret", Zicsr.MINSTRET, true));

    /** True once {@link #retireCsrs} has created the RVFI CSR ports. */
    public boolean hasRvfiCsrs;

    /**
     * Drive the RVFI CSR ports for a retiring CSR instruction (Zicsr cores, next to {@link #retire}).
     * A CSR instruction reports full read and write masks, the old value and the value it wrote
     * before WARL masking, as riscv-formal's csrw check expects.
     */
    protected void retireCsrs(Bool valid, UInt insn, Zicsr.Effect<UInt, Bool> eff) {
        hasRvfiCsrs = true;
        UInt addr = insn.bits(31, 20);
        Bool access = valid.and(eff.isCsr()).and(eff.commits());
        Bool writes = eff.csrWrites();
        for (var c : RVFI_CSRS) {
            int w = c.wide() ? 64 : 32;
            UInt rmask = output("rvfi_csr_" + c.name() + "_rmask", UInt.w(w));
            UInt wmask = output("rvfi_csr_" + c.name() + "_wmask", UInt.w(w));
            UInt rdata = output("rvfi_csr_" + c.name() + "_rdata", UInt.w(w));
            UInt wdata = output("rvfi_csr_" + c.name() + "_wdata", UInt.w(w));
            Bool lo = access.and(addr.eq(c.addr()));
            UInt zero = UInt.lit(0, w);
            if (c.addr() == Zicsr.MISA) {
                // misa is constant, and the specification reads it for jumps, branches and M (alignment, extensions)
                UInt ones = UInt.lit(0xFFFF_FFFFL, 32);
                rmask.set(mux(valid, ones, zero));
                wmask.set(mux(lo.and(writes), ones, zero));
                rdata.set(mux(valid, UInt.lit(Zicsr.MISA_VALUE, 32), zero));
                wdata.set(mux(lo.and(writes), eff.csrWritten(), zero));
            } else if (!c.wide()) {
                UInt ones = UInt.lit(0xFFFF_FFFFL, 32);
                rmask.set(mux(lo, ones, zero));
                wmask.set(mux(lo.and(writes), ones, zero));
                rdata.set(mux(lo, eff.csrValue(), zero));
                wdata.set(mux(lo.and(writes), eff.csrWritten(), zero));
            } else {
                Bool hi = access.and(addr.eq(c.addr() + 0x80));
                UInt z32 = UInt.lit(0, 32), ones = UInt.lit(0xFFFF_FFFFL, 32);
                UInt loMask = z32.cat(ones), hiMask = ones.cat(z32);
                UInt old = mux(hi, eff.csrValue().cat(z32), z32.cat(eff.csrValue()));
                UInt neu = mux(hi, eff.csrWritten().cat(z32), z32.cat(eff.csrWritten()));
                UInt mask = mux(hi, hiMask, mux(lo, loMask, zero));
                rmask.set(mask);
                wmask.set(mux(writes, mask, zero));
                rdata.set(mux(lo.or(hi), old, zero));
                wdata.set(mux(lo.or(hi).and(writes), neu, zero));
            }
        }
    }

    static final int DIV_BITS_PER_CYCLE = 2;

    /** A sequential divider's result, whether the instruction must wait for it, and the unit's control state (for proofs). */
    protected record SeqDiv(UInt value, Bool stall, Bool busy, Bool done, UInt count) {}

    /**
     * The M result with division done by a small sequential unit (see {@link Config#seqDiv}):
     * a divide waiting where the result is needed ({@code want}) is held there for 16 cycles
     * while the unit runs 2 restoring steps per cycle; {@code advancing} says it moves on.
     * Multiplies take the result at once.
     */
    protected SeqDiv sequentialDivide(Bool want, Bool advancing, Rv32i.MulDivHalf<UInt, Bool> h) {
        return sequentialDivide(want, advancing, h, false);
    }

    /** With {@code divOnly}, the value is the divide result only ({@link Rv32i#divFinish}): multiplies finish elsewhere. */
    protected SeqDiv sequentialDivide(Bool want, Bool advancing, Rv32i.MulDivHalf<UInt, Bool> h, boolean divOnly) {
        var d = HwDom.current();
        Bool busy = reg("div_busy", Bool.TYPE, 0), done = reg("div_done", Bool.TYPE, 0);
        UInt count = reg("div_count", UInt.w(6), 0);
        UInt rem = reg("div_rem", UInt.w(32), 0), quo = reg("div_quo", UInt.w(32), 0), dvd = reg("div_dividend", UInt.w(32), 0);
        Hw.when(want.and(busy.not()).and(done.not()), () -> {
            busy.set(true);
            count.set(0);
            rem.set(h.rem());
            quo.set(h.quo());
            dvd.set(h.dividend());
        });
        Hw.when(busy, () -> {
            Object[] st = Rv32i.divSteps(d, rem, quo, dvd, h.divisor(), DIV_BITS_PER_CYCLE);
            rem.set((UInt) st[0]);
            quo.set((UInt) st[1]);
            dvd.set((UInt) st[2]);
            count.set(count.addWrap(DIV_BITS_PER_CYCLE));
            // with riscv-formal's ALTOPS the result ignores the quotient: 2 cycles keep the stall in reach of BMC
            Hw.when(count.eq((d.altops() ? 4 : 32) - DIV_BITS_PER_CYCLE), () -> {
                busy.set(false);
                done.set(true);
            });
        });
        Hw.when(advancing, () -> done.set(false));
        var finished = new Rv32i.MulDivHalf<>(h.a(), h.b(), h.funct3(), h.p00(), h.p01(), h.p10(), h.p11(),
                rem, quo, dvd, h.divisor(), h.negQ(), h.negR(), h.byZero());
        return new SeqDiv(divOnly ? Rv32i.divFinish(d, finished, 0) : Rv32i.mulDivFinish(d, finished, 0), want.and(done.not()), busy, done, count);
    }

    /** The multiply/divide unit's result bus: its tag, value, operands, and its state (for proofs). */
    protected record MdUnit(Bool bus, UInt value, UInt tag, UInt a, UInt b, UInt count, Bool isDiv) {}

    /**
     * The out-of-order core's multiply/divide unit: an issued operation's operands are captured; a multiply is on the bus the next cycle,
     * a divide after 16 cycles of 2 restoring steps (count 0 to 32). {@code mdBusy} is made by the caller, which picks
     * only while it is clear and clears it on a flush.
     */
    protected MdUnit mulDivUnit(Bool mdBusy, Bool mdIssue, UInt issueTag, UInt mInst, UInt mA, UInt mB, boolean zicsr) {
        var d = HwDom.current();
        var mHalf = Rv32i.mulDivStart(d, Rv32i.decode(d, mInst, zicsr), mA, mB, 0);
        UInt mdTag = reg("md_tag", UInt.w(issueTag.width()), 0);
        UInt mdCount = reg("md_count", UInt.w(6), 0);
        Bool mdIsDiv = reg("md_div", Bool.TYPE, 0);
        UInt[] mdRegs = new UInt[11];
        String[] mdNames = {"a", "b", "funct3", "p00", "p01", "p10", "p11", "rem", "quo", "dividend", "divisor"};
        UInt[] mdNow = {mHalf.a(), mHalf.b(), mHalf.funct3(), mHalf.p00(), mHalf.p01(), mHalf.p10(), mHalf.p11(),
                mHalf.rem(), mHalf.quo(), mHalf.dividend(), mHalf.divisor()};
        for (int k = 0; k < mdRegs.length; k++) mdRegs[k] = reg("md_" + mdNames[k], UInt.w(mdNow[k].width()), 0);
        Bool mdNegQ = reg("md_neg_q", Bool.TYPE, 0), mdNegR = reg("md_neg_r", Bool.TYPE, 0), mdByZero = reg("md_by_zero", Bool.TYPE, 0);
        Hw.when(mdIssue, () -> {
            mdBusy.set(true);
            mdTag.set(issueTag);
            mdCount.set(0);
            mdIsDiv.set(mInst.bit(14));
            for (int k = 0; k < mdRegs.length; k++) mdRegs[k].set(mdNow[k]);
            mdNegQ.set(mHalf.negQ());
            mdNegR.set(mHalf.negR());
            mdByZero.set(mHalf.byZero());
        });
        Hw.when(mdBusy.and(mdIsDiv).and(mdCount.ne(32)), () -> {
            Object[] st = Rv32i.divSteps(d, mdRegs[7], mdRegs[8], mdRegs[9], mdRegs[10], 2);
            mdRegs[7].set((UInt) st[0]);
            mdRegs[8].set((UInt) st[1]);
            mdRegs[9].set((UInt) st[2]);
            mdCount.set(mdCount.addWrap(2));
        });
        // with riscv-formal's ALTOPS the result is a simple function of the operands: no iterations
        Bool mdBus = (d.altops() ? mdBusy : mdBusy.and(mdIsDiv.not().or(mdCount.eq(32)))).named("md_bus");
        UInt mdVal = Rv32i.mulDivFinish(d, new Rv32i.MulDivHalf<>(mdRegs[0], mdRegs[1], mdRegs[2], mdRegs[3], mdRegs[4],
                mdRegs[5], mdRegs[6], mdRegs[7], mdRegs[8], mdRegs[9], mdRegs[10], mdNegQ, mdNegR, mdByZero), 0)
                .named("md_value");
        Hw.when(mdBus, () -> mdBusy.set(false));
        return new MdUnit(mdBus, mdVal, mdTag, mdRegs[0], mdRegs[1], mdCount, mdIsDiv);
    }

    /** The multiply/divide unit's branch mask and whether this cycle cancels its operation (for proofs). */
    protected record MdSquash(Bool[] mask, Bool cancel) {}

    /**
     * Branch-mask bookkeeping for {@link #mulDivUnit} in a core that squashes at execute ({@link OooSpecCore}): the
     * operation in the unit carries the mask of the unresolved branches older than it ({@code issueMask} when it
     * issues). When branch {@code branch} (one-hot) resolves, a {@code squash} cancels the operation only if the operation
     * is younger than that branch (its mask bit is set), also when it issues in the same cycle; a correct resolution
     * ({@code clear}) clears the bit. Call after {@link #mulDivUnit}, so the cancel overrides its issue.
     */
    protected MdSquash mulDivSquash(Bool mdBusy, Bool mdIssue, Bool[] issueMask, Bool squash, Bool clear, Bool[] branch) {
        int n = issueMask.length;
        Bool[] mask = new Bool[n];
        for (int k = 0; k < n; k++) mask[k] = reg("md_bm" + k, Bool.TYPE, 0);
        Bool younger = Bool.of(false);
        for (int k = 0; k < n; k++) younger = younger.or(branch[k].and(mux(mdIssue, issueMask[k], mask[k])));
        Bool cancel = squash.and(younger).named("md_cancel");
        for (int k = 0; k < n; k++) {
            final int b = k;
            Hw.when(mdIssue, () -> mask[b].set(issueMask[b]));
            Hw.when(clear.and(branch[b]), () -> mask[b].set(false));
        }
        Hw.when(cancel, () -> mdBusy.set(false));
        return new MdSquash(mask, cancel);
    }

    /** Machine CSRs as registers, reset like {@link Zicsr#reset}. */
    protected Zicsr.Csrs<UInt> csrRegisters() {
        java.util.function.BiFunction<String, Long, UInt> r = (n, v) -> reg(n, UInt.w(32), v);
        return new Zicsr.Csrs<>(r.apply("mstatus", 0x1800L), r.apply("mie", 0L), r.apply("mtvec", 0L), r.apply("mscratch", 0L),
                r.apply("mepc", 0L), r.apply("mcause", 0L), r.apply("mtval", 0L), r.apply("mip", 0L), r.apply("mcycle", 0L),
                r.apply("mcycleh", 0L), r.apply("minstret", 0L), r.apply("minstreth", 0L));
    }

    /** Assign every CSR register its next value. */
    protected static void updateCsrs(Zicsr.Csrs<UInt> regs, Zicsr.Csrs<UInt> next, Bool en) {
        var a = regs;
        var b = next;
        UInt[] ra = {a.mstatus(), a.mie(), a.mtvec(), a.mscratch(), a.mepc(), a.mcause(), a.mtval(), a.mip(), a.mcycle(),
                a.mcycleh(), a.minstret(), a.minstreth()};
        UInt[] rb = {b.mstatus(), b.mie(), b.mtvec(), b.mscratch(), b.mepc(), b.mcause(), b.mtval(), b.mip(), b.mcycle(),
                b.mcycleh(), b.minstret(), b.minstreth()};
        for (int i = 0; i < ra.length; i++) {
            UInt dst = ra[i], src = rb[i];
            Hw.when(en, () -> dst.set(src));
        }
    }

    /** One RVFI channel's ports. */
    public record Channel(Bool valid, UInt order, UInt insn, Bool trap, Bool halt, Bool intr, UInt mode, UInt ixl,
                          UInt rs1Addr, UInt rs2Addr, UInt rs1Rdata, UInt rs2Rdata, UInt rdAddr, UInt rdWdata,
                          UInt pcRdata, UInt pcWdata, UInt memAddr, UInt memRmask, UInt memWmask, UInt memRdata, UInt memWdata) {}

    /**
     * The second RVFI channel ({@code rvfi1_*}) of a dual-issue core, or null: the younger of two instructions
     * retiring in one cycle. riscv-formal sees the two channels as one NRET=2 interface ({@link RvfiExport}).
     */
    public Channel rvfi1;

    /** Create the second RVFI channel's ports (dual-issue cores, before {@link #retire1}). */
    protected void addChannel1() {
        rvfi1 = new Channel(output("rvfi1_valid", Bool.TYPE), output("rvfi1_order", UInt.w(64)), output("rvfi1_insn", UInt.w(32)),
                output("rvfi1_trap", Bool.TYPE), output("rvfi1_halt", Bool.TYPE), output("rvfi1_intr", Bool.TYPE),
                output("rvfi1_mode", UInt.w(2)), output("rvfi1_ixl", UInt.w(2)), output("rvfi1_rs1_addr", UInt.w(5)),
                output("rvfi1_rs2_addr", UInt.w(5)), output("rvfi1_rs1_rdata", UInt.w(32)), output("rvfi1_rs2_rdata", UInt.w(32)),
                output("rvfi1_rd_addr", UInt.w(5)), output("rvfi1_rd_wdata", UInt.w(32)), output("rvfi1_pc_rdata", UInt.w(32)),
                output("rvfi1_pc_wdata", UInt.w(32)), output("rvfi1_mem_addr", UInt.w(32)), output("rvfi1_mem_rmask", UInt.w(4)),
                output("rvfi1_mem_wmask", UInt.w(4)), output("rvfi1_mem_rdata", UInt.w(32)), output("rvfi1_mem_wdata", UInt.w(32)));
    }

    /** What a test needs of one retiring instruction, per channel, in program order. */
    public record RetirePort(Bool valid, UInt pc, UInt insn, UInt rd, UInt rdValue, Bool intr) {}

    /** The retire ports in program order: channel 0, then channel 1 if the core has one. */
    public java.util.List<RetirePort> retirePorts() {
        var ch0 = new RetirePort(rvfiValid, rvfiPcRdata, rvfiInsn, rvfiRdAddr, rvfiRdWdata, rvfiIntr);
        if (rvfi1 == null) return java.util.List.of(ch0);
        return java.util.List.of(ch0, new RetirePort(rvfi1.valid(), rvfi1.pcRdata(), rvfi1.insn(), rvfi1.rdAddr(), rvfi1.rdWdata(), rvfi1.intr()));
    }

    /** Channel 0's order register and valid bit, for {@link #retire1} (not fields of Data type: those name signals). */
    private Object[] channel0;

    /** Drive the RVFI port. Call once, in the stage where instructions retire. */
    protected void retire(RetireInfo r) {
        drive(new Channel(rvfiValid, rvfiOrder, rvfiInsn, rvfiTrap, rvfiHalt, rvfiIntr, rvfiMode, rvfiIxl, rvfiRs1Addr, rvfiRs2Addr,
                rvfiRs1Rdata, rvfiRs2Rdata, rvfiRdAddr, rvfiRdWdata, rvfiPcRdata, rvfiPcWdata, rvfiMemAddr, rvfiMemRmask,
                rvfiMemWmask, rvfiMemRdata, rvfiMemWdata), r);
        UInt order = Hw.register("rvfi_order", UInt.w(64), 0L);
        Hw.when(r.valid(), () -> order.set(order.addWrap(1)));
        rvfiOrder.set(order);
        channel0 = new Object[]{order, r.valid()};
    }

    /**
     * Drive the second RVFI channel with the younger instruction retiring beside channel 0's (after {@link
     * #retire}): its order follows channel 0's, and the order counter advances by both.
     */
    protected void retire1(RetireInfo r) {
        drive(rvfi1, r);
        UInt order = (UInt) channel0[0];
        Bool valid0 = (Bool) channel0[1];
        UInt one = UInt.lit(1, 64), zero = UInt.lit(0, 64);
        UInt n0 = mux(valid0, one, zero), n1 = mux(r.valid(), one, zero);
        rvfi1.order().set(order.addWrap(n0));
        order.set(order.addWrap(n0).addWrap(n1));
    }

    private void drive(Channel ch, RetireInfo r) {
        var d = HwDom.INSTANCE;
        var dec = r.dec();
        var ex = r.ex();
        UInt zero5 = UInt.lit(0, 5), zero32 = UInt.lit(0, 32);
        UInt rs1 = mux(dec.usesRs1(), dec.rs1().bits(4, 0), zero5);
        UInt rs2 = mux(dec.usesRs2(), dec.rs2().bits(4, 0), zero5);
        ch.valid().set(r.valid());
        ch.insn().set(r.insn());
        ch.trap().set(r.trap());
        ch.halt().set(r.halt());
        ch.intr().set(r.intr());
        ch.mode().set(3);
        ch.ixl().set(1);
        ch.rs1Addr().set(rs1);
        ch.rs2Addr().set(rs2);
        ch.rs1Rdata().set(mux(rs1.eq(0), zero32, r.rs1Value()));
        ch.rs2Rdata().set(mux(rs2.eq(0), zero32, r.rs2Value()));
        ch.rdAddr().set(mux(r.writesRd(), dec.rd().bits(4, 0), zero5));
        ch.rdWdata().set(mux(r.writesRd(), r.rdValue(), zero32));
        ch.pcRdata().set(r.pc());
        ch.pcWdata().set(r.nextPc());
        var mem = Rv32i.memAccess(d, dec, ex, r.rs2Value());
        if (zicsr) mem = gateMemAccess(mem, r.trap().or(r.halt()));
        ch.memAddr().set(mem.addr());
        ch.memRmask().set(mem.rmask().bits(3, 0));
        ch.memWmask().set(mem.wmask().bits(3, 0));
        ch.memRdata().set(mux(mem.rmask().bits(3, 0).orR(), r.memWord(), zero32));
        ch.memWdata().set(mem.wdata());
    }

    /**
     * Channel 1's RVFI CSR ports ({@code rvfi1_csr_*}): its instruction never accesses a CSR (lane 1 takes none),
     * but jumps and branches read misa, as on channel 0 ({@link #retireCsrs}).
     */
    protected void retireCsrs1(Bool valid) {
        for (var c : RVFI_CSRS) {
            int w = c.wide() ? 64 : 32;
            UInt rmask = output("rvfi1_csr_" + c.name() + "_rmask", UInt.w(w));
            UInt wmask = output("rvfi1_csr_" + c.name() + "_wmask", UInt.w(w));
            UInt rdata = output("rvfi1_csr_" + c.name() + "_rdata", UInt.w(w));
            UInt wdata = output("rvfi1_csr_" + c.name() + "_wdata", UInt.w(w));
            UInt zero = UInt.lit(0, w);
            if (c.addr() == Zicsr.MISA) {
                rmask.set(mux(valid, UInt.lit(0xFFFF_FFFFL, 32), zero));
                rdata.set(mux(valid, UInt.lit(Zicsr.MISA_VALUE, 32), zero));
            } else {
                rmask.set(zero);
                rdata.set(zero);
            }
            wmask.set(zero);
            wdata.set(zero);
        }
    }
}
