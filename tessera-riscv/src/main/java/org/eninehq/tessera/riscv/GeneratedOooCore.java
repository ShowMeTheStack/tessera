package org.eninehq.tessera.riscv;

import static org.eninehq.tessera.core.Hw.mux;
import static org.eninehq.tessera.core.Hw.when;
import static org.eninehq.tessera.pipeline.InOrderModel.READ_REGS;
import static org.eninehq.tessera.pipeline.InOrderModel.WRITE_REGS;

import java.util.List;
import org.eninehq.tessera.core.Bool;
import org.eninehq.tessera.core.UInt;
import org.eninehq.tessera.pipeline.InOrderModel;
import org.eninehq.tessera.pipeline.InOrderPipeline;
import org.eninehq.tessera.pipeline.Key;
import org.eninehq.tessera.pipeline.OooSchedule;
import org.eninehq.tessera.pipeline.OutOfOrderPipeline;
import org.eninehq.tessera.pipeline.Schedule;
import org.eninehq.tessera.pipeline.StepContext;

/**
 * RV32IM (and Zicsr) as one {@link InOrderModel} whose side effects are all in steps of their own, so that
 * the same model generates an out-of-order core ({@link OutOfOrderPipeline}, with C7's organisation: memory at the
 * head of the reorder buffer, recovery at commit) and an in-order pipeline ({@link InOrderPipeline}).
 *
 * <p>The steps, with no notion of stages or units:
 * <ul>
 *   <li>{@code fetch}: the instruction;</li>
 *   <li>{@code decode}: fields, register numbers, and the static guess of C7 (JAL and backward branches taken);</li>
 *   <li>{@code readRegs};</li>
 *   <li>{@code execute}: ALU, branch and jump resolution, address generation, the multiply's partial products
 *       ({@link Rv32i#execute}, {@link Rv32i#mulDivStart}); no side effects;</li>
 *   <li>{@code memRead}: the synchronous data-memory read (block RAM);</li>
 *   <li>{@code mdFinish}: the multiply/divide result, with a sequential divider (2 bits per cycle, like
 *       {@link RvCore#sequentialDivide}, but it takes its first step in the cycle it starts and it abandons the
 *       division when its item goes away, so that a cancelled divide does not hold the unit);</li>
 *   <li>{@code memory} (side effects): CSRs, traps, interrupts and MRET ({@link Zicsr#step}), the store, the load value
 *       and the destination value;</li>
 *   <li>{@code writeRegs}, {@code retire} (RVFI; with Zicsr it marks the first instruction it reports after an
 *       interrupted one as a handler's first, rvfi_intr, where ScheduledCore captures a flag at fetch).</li>
 * </ul>
 * ScheduledCore's model differs in two places that rule it out for an out-of-order schedule: its "execute" is a
 * side-effect step under Zicsr (CSR updates and the trap target are computed there), and with block RAM it issues
 * the load's read in "execute", before older stores have written.
 */
public class GeneratedOooCore extends RvCore {
    static final Key<UInt> PC = Key.of("pc"), INST = Key.of("inst");
    static final Key<Rv32i.Decoded<UInt, Bool>> DEC = Key.of("dec");
    static final Key<UInt> RS1 = Key.of("rs1"), RS2 = Key.of("rs2"), RD = Key.of("rd");
    static final Key<Bool> USES_RS1 = Key.of("usesRs1"), USES_RS2 = Key.of("usesRs2"), WRITES = Key.of("writesRd");
    static final Key<UInt> RS1_VAL = Key.of("rs1Value"), RS2_VAL = Key.of("rs2Value");
    static final Key<Bool> IS_MD = Key.of("isMulDiv"), MEM_ACCESS = Key.of("memAccess");
    static final Key<UInt> DEC_NEXT = Key.of("decodeNext");
    static final Key<Bool> DEC_KNOWS = Key.of("decodeKnows");
    static final Key<Rv32i.Exec<UInt, Bool>> EX = Key.of("ex");
    static final Key<UInt> ALU = Key.of("aluResult"), EX_NEXT = Key.of("executeNext");
    static final Key<Bool> ALU_READY = Key.of("aluReady");
    static final Key<Rv32i.MulDivHalf<UInt, Bool>> MD_HALF = Key.of("mulDivHalf");
    static final Key<UInt> MD_VAL = Key.of("mulDivValue");
    static final Key<Bool> MD_READY = Key.of("mulDivReady");
    static final Key<UInt> SYNC_WORD = Key.of("syncWord"), MEM_WORD = Key.of("memWord");
    static final Key<Zicsr.Effect<UInt, Bool>> EFFECT = Key.of("privileged");
    static final Key<Bool> HALT = Key.of("halt"), WRITES_OK = Key.of("writesRdNoTrap"),
            COMMITS = Key.of("commits"), TRAP = Key.of("trap"), EVENT = Key.of("trapOrReturn");
    static final Key<UInt> NEXT_PC = Key.of("nextPc"), RD_VAL = Key.of("rdValue");
    /** With loads out of order: the load and store flags, the word index, byte masks, store data in lane positions. */
    static final Key<Bool> IS_LOAD = Key.of("isLoad"), IS_STORE = Key.of("isStore");
    static final Key<UInt> MEM_INDEX = Key.of("memIndex"), LOAD_MASK = Key.of("loadMask"), STORE_MASK = Key.of("storeMask"),
            STORE_DATA = Key.of("storeData"), LOAD_WORD = Key.of("loadWord"), LOAD_VAL = Key.of("loadValue");

    /** The out-of-order schedule with C7's organisation: one ALU, one multiply/divide unit, memory at the head. */
    public static OooSchedule c7Schedule(boolean bram, int robEntries, int rsEntries) {
        var s = OooSchedule.create()
                .fetch("fetch").decode("decode")
                .unit("alu", null, "execute")
                .registeredUnit("md", IS_MD, List.of("execute"), "mdFinish")
                .recompute("execute");
        // a synchronous read takes a cycle at the head before the load or store commits; an asynchronous one does not
        s = bram ? s.commitPrologue(MEM_ACCESS, "memRead") : s.recompute("memRead");
        return s.commit("memory", WRITE_REGS, "retire").window(robEntries, rsEntries);
    }

    /**
     * C7's schedule with loads out of order: a load unit computes the address, reads memory and the load value
     * ("loadRead", "loadValue") as soon as the older stores allow ({@link OooSchedule#loadStoreQueue}); stores still
     * write at commit, after a read at the head (block RAM) for their read-modify-write.
     */
    public static OooSchedule lsqSchedule(boolean bram, int robEntries, int rsEntries) {
        var s = OooSchedule.create()
                .fetch("fetch").decode("decode")
                .unit("alu", null, "execute")
                .registeredUnit("md", IS_MD, List.of("execute"), "mdFinish")
                .registeredUnit("lsu", IS_LOAD, List.of("execute", "loadRead"), "loadValue")
                .recompute("execute")
                .loadStoreQueue("lsu", IS_STORE, MEM_INDEX, LOAD_MASK, STORE_MASK, STORE_DATA, LOAD_WORD);
        s = bram ? s.commitPrologue(IS_STORE, "memRead") : s.recompute("memRead");
        return s.commit("memory", WRITE_REGS, "retire").window(robEntries, rsEntries);
    }

    /** The same model in order: the classic five stages (multiply/divide and memory in MEM). */
    public static final Schedule FIVE_STAGE = Schedule.stages("IF", "ID", "EX", "MEM", "WB")
            .place("IF", "fetch").place("ID", "decode", READ_REGS).place("EX", "execute", "memRead")
            .place("MEM", "mdFinish", "memory").place("WB", WRITE_REGS, "retire");

    public final OutOfOrderPipeline ooo;
    public final InOrderPipeline inOrder;
    public final boolean bram, seqDiv;
    /** Loads out of order ({@link #lsqSchedule}). */
    public final boolean lsq;

    /** Out of order, C7's organisation, with the given window. */
    public GeneratedOooCore(int memWords, int[] image, Config config, int robEntries, int rsEntries) {
        this(memWords, image, config, robEntries, rsEntries, false);
    }

    /** With {@code early}: branch recovery at execute ({@link OooSchedule#recoverAtExecute}), the rest as C7. */
    public GeneratedOooCore(int memWords, int[] image, Config config, int robEntries, int rsEntries, boolean early) {
        this(memWords, image, config, robEntries, rsEntries, early, false);
    }

    /** With {@code lsq}: loads out of order ({@link #lsqSchedule}); with {@code early}: recovery at execute. */
    public GeneratedOooCore(int memWords, int[] image, Config config, int robEntries, int rsEntries, boolean early, boolean lsq) {
        super(memWords, image, config);
        bram = config.bram();
        seqDiv = config.seqDiv();
        this.lsq = lsq;
        var schedule = lsq ? lsqSchedule(bram, robEntries, rsEntries) : c7Schedule(bram, robEntries, rsEntries);
        ooo = OutOfOrderPipeline.build("core", model(), early ? schedule.recoverAtExecute() : schedule);
        inOrder = null;
        halted.set(ooo.halted());
    }

    /** Out of order with C7's default window (16-entry ROB, 8 reservation stations). */
    public GeneratedOooCore(int memWords, int[] image, Config config) {
        this(memWords, image, config, OooCore.ROB_DEFAULT, OooCore.RS_DEFAULT);
    }

    /** The same model through the in-order generator. */
    public GeneratedOooCore(int memWords, int[] image, Config config, Schedule schedule) {
        super(memWords, image, config);
        bram = config.bram();
        seqDiv = config.seqDiv();
        lsq = false;
        inOrder = InOrderPipeline.build("core", model(), schedule);
        ooo = null;
        halted.set(inOrder.halted());
    }

    /** What the generator built, in words. */
    public String report() {
        return ooo != null ? ooo.report() : inOrder.report();
    }

    private InOrderModel model() {
        var d = HwDom.current();
        Zicsr.Csrs<UInt> csrs = zicsr ? csrRegisters() : null;
        // rvfi_intr: the first instruction retired after an interrupt was taken (set by the retire step itself)
        Bool intrNext = zicsr ? reg("intr_next", Bool.TYPE, 0) : null;
        UInt lastIdx = bram ? reg("last_store_idx", UInt.w(addrBits), 0) : null;
        UInt lastWord = bram ? reg("last_store_word", UInt.w(32), 0) : null;
        Bool lastValid = bram ? reg("last_store_valid", Bool.TYPE, 0) : null;
        var m = InOrderModel.create()
                .pc(PC, 32, 0, 4)
                .registerFile(regs, true)
                .mayWrite(WRITES)
                .step("fetch", c -> {
                    c.put(INST, bram ? c.registered(imem.readSync(wordIndex(c.get(PC)), c.advancing()))
                            : imem.read(wordIndex(c.get(PC))));
                })
                .step("decode", c -> {
                    var dec = Rv32i.decode(d, c.get(INST), zicsr);
                    c.put(DEC, dec);
                    c.put(RS1, dec.rs1().bits(4, 0));
                    c.put(RS2, dec.rs2().bits(4, 0));
                    c.put(RD, dec.rd().bits(4, 0));
                    c.put(USES_RS1, dec.usesRs1());
                    c.put(USES_RS2, dec.usesRs2());
                    c.put(WRITES, zicsr ? dec.writesRd().or(dec.system().and(dec.rd().ne(0))) : dec.writesRd());
                    c.put(IS_MD, dec.mulDiv());
                    c.put(MEM_ACCESS, dec.load().or(dec.store()));
                    // C7's static guess: JAL and backward branches taken
                    c.put(DEC_NEXT, c.get(PC).addWrap(dec.imm()));
                    c.put(DEC_KNOWS, dec.jal().or(dec.branch().and(dec.imm().bit(31))));
                    if (lsq) {
                        c.put(IS_LOAD, dec.load());
                        c.put(IS_STORE, dec.store());
                    }
                })
                .readPort(RS1, USES_RS1, RS1_VAL)
                .readPort(RS2, USES_RS2, RS2_VAL)
                .readRegs()
                .step("execute", c -> {
                    var dec = c.get(DEC);
                    var ex = Rv32i.execute(d, dec, c.get(PC), c.get(RS1_VAL), c.get(RS2_VAL));
                    c.put(EX, ex);
                    c.put(ALU, ex.result());
                    c.put(EX_NEXT, ex.nextPc());
                    Bool late = dec.load().or(dec.mulDiv());
                    if (zicsr) late = late.or(isCsr(dec));
                    c.put(ALU_READY, late.not());
                    c.put(MD_HALF, Rv32i.mulDivStart(d, dec, c.get(RS1_VAL), c.get(RS2_VAL), seqDiv ? 0 : 16));
                    if (lsq) {
                        var acc = Rv32i.memAccess(d, dec, ex, c.get(RS2_VAL));
                        c.put(MEM_INDEX, wordIndex(ex.memAddr()));
                        c.put(LOAD_MASK, acc.rmask().bits(3, 0));
                        c.put(STORE_MASK, acc.wmask().bits(3, 0));
                        c.put(STORE_DATA, acc.wdata());
                    }
                })
                .step("memRead", c -> {
                    if (bram) c.put(SYNC_WORD, c.registered(dmemRead(wordIndex(c.get(EX).memAddr()), c.advancing(), true)));
                });
        if (lsq)
            m = m.step("loadRead", c -> {
                        // the load unit's own read port (the head's port serves the stores' read-modify-write)
                        c.put(LOAD_WORD, bram ? c.registered(dmem.readSync(c.get(MEM_INDEX), c.advancing())) : dmem.read(c.get(MEM_INDEX)));
                    })
                    .step("loadValue", c -> c.put(LOAD_VAL, Rv32i.loadValue(d, c.get(DEC).funct3(), c.get(EX).memAddr(), c.get(LOAD_WORD))))
                    .resultAfter("loadValue", LOAD_VAL, null);
        m = m
                .step("mdFinish", c -> {
                    var dec = c.get(DEC);
                    UInt md;
                    if (seqDiv) {
                        Bool isDiv = dec.mulDiv().and(dec.funct3().bit(2)).named("is_div");
                        md = divide(c, c.valid().and(isDiv), c.get(MD_HALF));
                    } else {
                        md = Rv32i.mulDivFinish(d, c.get(MD_HALF));
                    }
                    c.put(MD_VAL, md);
                    c.put(MD_READY, dec.mulDiv());
                })
                .effectStep("memory", c -> memory(c, d, csrs, lastIdx, lastWord, lastValid))
                .writeRegs(RD, WRITES_OK, RD_VAL)
                .step("retire", c -> {
                    Bool interrupted = zicsr ? c.get(EFFECT).interrupt() : Bool.of(false);
                    Bool retires = c.fire().and(c.halted().not()).and(interrupted.not());
                    if (zicsr) {
                        // an interrupted item is not reported; the next one that is starts the handler
                        when(c.fire().and(interrupted), () -> intrNext.set(true));
                        when(retires, () -> intrNext.set(false));
                    }
                    retire(new RetireInfo(retires, c.get(INST), c.get(PC), c.get(DEC), c.get(EX), c.get(RS1_VAL), c.get(RS2_VAL),
                            c.get(RD_VAL), c.get(MEM_WORD), c.get(TRAP), c.get(HALT), zicsr ? intrNext : Bool.of(false),
                            c.get(NEXT_PC), c.get(WRITES_OK)));
                    if (zicsr) retireCsrs(retires, c.get(INST), c.get(EFFECT));
                })
                .resultAfter("execute", ALU, ALU_READY)
                .resultAfter("mdFinish", MD_VAL, MD_READY)
                .resultAfter("memory", RD_VAL, null)
                .resolve("decode", DEC_NEXT, DEC_KNOWS)
                .resolve("execute", EX_NEXT, null);
        return (zicsr ? m.resolve("memory", NEXT_PC, null, EVENT) : m.resolve("memory", NEXT_PC, null)).halt("memory", HALT);
    }

    /**
     * The sequential divider of the model: started by {@code want} (a divide is in the step), 2 restoring steps per
     * cycle from the start cycle on, 16 steps in all; the step stalls until it is done. If {@code want} drops before
     * the result is taken (the item was cancelled: out of order, a flushed item looks like a bubble), it stops.
     */
    private UInt divide(StepContext c, Bool want, Rv32i.MulDivHalf<UInt, Bool> h) {
        var d = HwDom.current();
        Bool busy = reg("div_busy", Bool.TYPE, 0), done = reg("div_done", Bool.TYPE, 0);
        UInt count = reg("div_count", UInt.w(6), 0);
        UInt rem = reg("div_rem", UInt.w(32), 0), quo = reg("div_quo", UInt.w(32), 0), dvd = reg("div_dividend", UInt.w(32), 0);
        Object[] first = Rv32i.divSteps(d, h.rem(), h.quo(), h.dividend(), h.divisor(), DIV_BITS_PER_CYCLE);
        Object[] next = Rv32i.divSteps(d, rem, quo, dvd, h.divisor(), DIV_BITS_PER_CYCLE);
        // with riscv-formal's ALTOPS the result ignores the quotient: 2 cycles keep the stall in reach of BMC
        int last = (d.altops() ? 4 : 32) - DIV_BITS_PER_CYCLE;
        when(want.and(busy.not()).and(done.not()), () -> {
            busy.set(true);
            count.set(DIV_BITS_PER_CYCLE);
            rem.set((UInt) first[0]);
            quo.set((UInt) first[1]);
            dvd.set((UInt) first[2]);
        });
        when(busy, () -> {
            rem.set((UInt) next[0]);
            quo.set((UInt) next[1]);
            dvd.set((UInt) next[2]);
            count.set(count.addWrap(DIV_BITS_PER_CYCLE));
            when(count.eq(last), () -> {
                busy.set(false);
                done.set(true);
            });
        });
        when(c.advancing(), () -> done.set(false));
        when(want.not(), () -> {
            busy.set(false);
            done.set(false);
        });
        c.stall(want.and(done.not()));
        var finished = new Rv32i.MulDivHalf<>(h.a(), h.b(), h.funct3(), h.p00(), h.p01(), h.p10(), h.p11(),
                rem, quo, dvd, h.divisor(), h.negQ(), h.negR(), h.byZero());
        return Rv32i.mulDivFinish(d, finished, 0);
    }

    private static Bool isCsr(Rv32i.Decoded<UInt, Bool> dec) {
        return dec.system().and(dec.funct3().ne(0)).and(dec.funct3().ne(4));
    }

    /** The side effects: CSRs and traps, the store, and the value written back. */
    private void memory(StepContext c, HwDom d, Zicsr.Csrs<UInt> csrs, UInt lastIdx, UInt lastWord, Bool lastValid) {
        var dec = c.get(DEC);
        var ex = c.get(EX);
        UInt addr = ex.memAddr();
        UInt idx = wordIndex(addr);
        UInt word = bram ? mux(lastValid.and(lastIdx.eq(idx)), lastWord, c.get(SYNC_WORD)) : dmemRead(idx, Bool.of(true), false);
        // loads out of order: a load's word is the one its unit read (or was forwarded); stores still read here
        if (lsq) word = mux(dec.load(), c.get(LOAD_WORD), word);
        c.put(MEM_WORD, word);
        UInt value = c.get(ALU);
        if (zicsr) {
            var eff = Zicsr.step(d, dec, ex, c.get(INST), c.get(PC), c.get(RS1_VAL), csrs, irq, c.fire());
            updateCsrs(csrs, eff.next(), c.fire());
            c.put(EFFECT, eff);
            c.put(HALT, eff.halt());
            c.put(WRITES_OK, eff.writesRd());
            c.put(COMMITS, eff.commits());
            c.put(TRAP, eff.exception());
            c.put(NEXT_PC, mux(eff.redirect(), eff.target(), ex.nextPc()));
            c.put(EVENT, eff.redirect());
            value = mux(eff.isCsr(), eff.csrValue(), value);
        } else {
            c.put(HALT, ex.trap());
            c.put(WRITES_OK, ex.writesRd());
            c.put(COMMITS, ex.trap().not());
            c.put(TRAP, ex.trap());
            c.put(NEXT_PC, ex.nextPc());
        }
        value = mux(dec.load(), Rv32i.loadValue(d, dec.funct3(), addr, word), value);
        c.put(RD_VAL, mux(dec.mulDiv(), c.get(MD_VAL), value));
        UInt merged = Rv32i.storeMerge(d, dec.funct3(), addr, word, c.get(RS2_VAL));
        Bool storing = c.fire().and(dec.store()).and(c.get(COMMITS));
        dmemWrite(idx, merged, storing);
        if (bram) {
            lastValid.set(storing);
            when(storing, () -> {
                lastIdx.set(idx);
                lastWord.set(merged);
            });
        }
    }
}
