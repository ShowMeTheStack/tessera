package org.eninehq.tessera.riscv;

import static org.eninehq.tessera.core.Hw.mux;
import static org.eninehq.tessera.core.Hw.when;
import static org.eninehq.tessera.pipeline.InOrderModel.READ_REGS;
import static org.eninehq.tessera.pipeline.InOrderModel.WRITE_REGS;

import org.eninehq.tessera.core.Bool;
import org.eninehq.tessera.core.UInt;
import org.eninehq.tessera.pipeline.DualIssuePipeline;
import org.eninehq.tessera.pipeline.Forward;
import org.eninehq.tessera.pipeline.InOrderModel;
import org.eninehq.tessera.pipeline.InOrderPipeline;
import org.eninehq.tessera.pipeline.Key;
import org.eninehq.tessera.pipeline.Schedule;

/**
 * R5: RV32I cores generated from one model and a schedule (Level-2 pipelines). The model
 * below says what each step computes; {@link #FIVE_STAGE}, {@link #THREE_STAGE} and the
 * others say where it happens. Forwarding, stalls and flushes are inferred.
 */
public class ScheduledCore extends RvCore {
    static final Key<UInt> PC = Key.of("pc"), INST = Key.of("inst");
    static final Key<Rv32i.Decoded<UInt, Bool>> DEC = Key.of("dec");
    static final Key<UInt> RS1 = Key.of("rs1"), RS2 = Key.of("rs2"), RD = Key.of("rd");
    static final Key<Bool> USES_RS1 = Key.of("usesRs1"), USES_RS2 = Key.of("usesRs2"), WRITES = Key.of("writesRd");
    static final Key<UInt> RS1_VAL = Key.of("rs1Value"), RS2_VAL = Key.of("rs2Value");
    static final Key<Rv32i.Exec<UInt, Bool>> EX = Key.of("ex");
    static final Key<UInt> MEM_WORD = Key.of("memWord");
    static final Key<Rv32i.MulDivHalf<UInt, Bool>> MD_HALF = Key.of("mulDivHalf");
    static final Key<Bool> WRITES_OK = Key.of("writesRdNoTrap");
    static final Key<Bool> REDIRECT = Key.of("redirect"), HALT = Key.of("halt"), ALU_READY = Key.of("aluReady");
    static final Key<UInt> TARGET = Key.of("target"), ALU = Key.of("aluResult"), RD_VAL = Key.of("rdValue");

    /** The classic five stages. */
    public static final Schedule FIVE_STAGE = Schedule.stages("IF", "ID", "EX", "MEM", "WB")
            .place("IF", "fetch").place("ID", "decode", READ_REGS).place("EX", "execute")
            .place("MEM", "memory").place("WB", WRITE_REGS, "retire");

    /** Fetch, everything else, write back. */
    public static final Schedule THREE_STAGE = Schedule.stages("F", "X", "W")
            .place("F", "fetch").place("X", "decode", READ_REGS, "execute", "memory").place("W", WRITE_REGS, "retire");

    /** Execute and memory share a stage: no load-use stall. */
    public static final Schedule FOUR_STAGE = Schedule.stages("IF", "ID", "EXM", "WB")
            .place("IF", "fetch").place("ID", "decode", READ_REGS).place("EXM", "execute", "memory")
            .place("WB", WRITE_REGS, "retire");

    /** A separate register-read stage: back-to-back dependent instructions need forwarding from deeper. */
    public static final Schedule SIX_STAGE = Schedule.stages("IF", "ID", "RF", "EX", "MEM", "WB")
            .place("IF", "fetch").place("ID", "decode").place("RF", READ_REGS).place("EX", "execute")
            .place("MEM", "memory").place("WB", WRITE_REGS, "retire");

    /**
     * Block-RAM versions of the three- and four-stage schedules: a synchronous read made in
     * "execute" returns in the next stage, so "memory" moves into the last stage with write-back.
     */
    public static final Schedule THREE_STAGE_BRAM = Schedule.stages("F", "X", "W")
            .place("F", "fetch").place("X", "decode", READ_REGS, "execute").place("W", "memory", WRITE_REGS, "retire");

    public static final Schedule FOUR_STAGE_BRAM = Schedule.stages("IF", "ID", "EX", "MW")
            .place("IF", "fetch").place("ID", "decode", READ_REGS).place("EX", "execute")
            .place("MW", "memory", WRITE_REGS, "retire");

    static final Key<Zicsr.Effect<UInt, Bool>> EFFECT = Key.of("privileged");
    static final Key<Bool> COMMITS = Key.of("commits"), INTR = Key.of("handlerStart"), TRAP = Key.of("trap");
    static final Key<UInt> NEXT_PC = Key.of("nextPc"), SYNC_WORD = Key.of("syncWord");
    /** The result forwarded from "memory": loads and ALU results; multiply/divide results come from "retire". */
    static final Key<UInt> MEM_FWD = Key.of("memForward");
    static final Key<Bool> MEM_READY = Key.of("memReady");
    /**
     * With a "mulFinish" step: the divide result (finished in "memory"), the value "memory" hands on for
     * write-back (the divide result or the load/ALU value), and whether that value is final (not a multiply).
     */
    static final Key<UInt> DIV_VAL = Key.of("divValue"), MD_FWD = Key.of("memResult");
    static final Key<Bool> MD_READY = Key.of("memResultReady");

    /**
     * The step that finishes multiplies. A schedule that places it (see {@link #FIVE_STAGE_MUL_WB}) splits the
     * multiply/divide unit's second half: divides finish in "memory", multiplies in this step, and the multiply
     * result is forwarded only from the pipeline register after it ({@link Forward#NEXT_STAGE}).
     */
    public static final String MUL_FINISH = "mulFinish";

    /**
     * With this step placed as well, the multiply finish is itself split: this step sums the partial products
     * ({@link Rv32i#mulSum}), "mulFinish" applies the corrections and selects ({@link Rv32i#mulFix}).
     */
    public static final String MUL_SUM = "mulSum";
    static final Key<Rv32i.MulSum<UInt>> MSUM = Key.of("mulSumValue");

    private static Schedule five(String mem, String mf, String wb) {
        var st = mf == null ? Schedule.stages("IF", "ID", "EX", "MEM", "WB") : Schedule.stages("IF", "ID", "EX", "MEM", "MF", "WB");
        st.place("IF", "fetch").place("ID", "decode", READ_REGS).place("EX", "execute").place("MEM", split(mem));
        if (mf != null) st.place("MF", split(mf));
        return st.place("WB", split(wb));
    }

    private static Schedule six(String mem, String mf, String wb) {
        var st = mf == null ? Schedule.stages("IF", "ID", "RF", "EX", "MEM", "WB") : Schedule.stages("IF", "ID", "RF", "EX", "MEM", "MF", "WB");
        st.place("IF", "fetch").place("ID", "decode").place("RF", READ_REGS).place("EX", "execute").place("MEM", split(mem));
        if (mf != null) st.place("MF", split(mf));
        return st.place("WB", split(wb));
    }

    private static String[] split(String steps) {
        return steps.isBlank() ? new String[0] : steps.split(" ");
    }

    /** The multiply summed in MEM, corrected in WB (not forwarded). */
    public static final Schedule FIVE_STAGE_MUL_SUM_WB = five("memory mulSum", null, "mulFinish writeRegs retire");
    /** The multiply summed in MEM, corrected in MF. */
    public static final Schedule FIVE_STAGE_MUL_SUM_MF = five("memory mulSum", "mulFinish", "writeRegs retire");
    /** The multiply summed in MF, corrected in WB (not forwarded). */
    public static final Schedule FIVE_STAGE_MUL_MF_WB = five("memory", "mulSum", "mulFinish writeRegs retire");
    public static final Schedule SIX_STAGE_MUL_SUM_WB = six("memory mulSum", null, "mulFinish writeRegs retire");
    public static final Schedule SIX_STAGE_MUL_SUM_MF = six("memory mulSum", "mulFinish", "writeRegs retire");
    public static final Schedule SIX_STAGE_MUL_MF_WB = six("memory", "mulSum", "mulFinish writeRegs retire");

    /** {@link #FIVE_STAGE} with multiplies finished in "memory" as before, as a separate step: the same pipeline. */
    public static final Schedule FIVE_STAGE_MUL_MEM = Schedule.stages("IF", "ID", "EX", "MEM", "WB")
            .place("IF", "fetch").place("ID", "decode", READ_REGS).place("EX", "execute")
            .place("MEM", "memory", MUL_FINISH).place("WB", WRITE_REGS, "retire");

    /** Multiplies finish in write-back; their result is not forwarded, so a dependent instruction waits a cycle more. */
    public static final Schedule FIVE_STAGE_MUL_WB = Schedule.stages("IF", "ID", "EX", "MEM", "WB")
            .place("IF", "fetch").place("ID", "decode", READ_REGS).place("EX", "execute")
            .place("MEM", "memory").place("WB", MUL_FINISH, WRITE_REGS, "retire");

    /** Multiplies finish in a stage of their own, MF, between MEM and WB; their result is forwarded from WB. */
    public static final Schedule FIVE_STAGE_MUL_MF = Schedule.stages("IF", "ID", "EX", "MEM", "MF", "WB")
            .place("IF", "fetch").place("ID", "decode", READ_REGS).place("EX", "execute")
            .place("MEM", "memory").place("MF", MUL_FINISH).place("WB", WRITE_REGS, "retire");

    /** {@link #SIX_STAGE} with multiplies finished in write-back. */
    public static final Schedule SIX_STAGE_MUL_WB = Schedule.stages("IF", "ID", "RF", "EX", "MEM", "WB")
            .place("IF", "fetch").place("ID", "decode").place("RF", READ_REGS).place("EX", "execute")
            .place("MEM", "memory").place("WB", MUL_FINISH, WRITE_REGS, "retire");

    /** {@link #SIX_STAGE} with multiplies finished in a stage of their own, MF. */
    public static final Schedule SIX_STAGE_MUL_MF = Schedule.stages("IF", "ID", "RF", "EX", "MEM", "MF", "WB")
            .place("IF", "fetch").place("ID", "decode").place("RF", READ_REGS).place("EX", "execute")
            .place("MEM", "memory").place("MF", MUL_FINISH).place("WB", WRITE_REGS, "retire");

    /**
     * Branch prediction. {@code btbEntries} > 0: a branch target buffer with 2-bit counters in fetch (taken when the
     * entry hits and its counter says so), trained in execute; JAL is also resolved in decode, where its target is
     * known. {@code staticDecode}: decode guesses JAL and backward branches taken. Execute checks every guess.
     */
    public record Predictor(boolean staticDecode, int btbEntries, boolean decodeJal, int ghrBits, int rasEntries) {
        public static final Predictor NONE = new Predictor(false, 0, false);
        public static final Predictor STATIC = new Predictor(true, 0, true);

        public Predictor(boolean staticDecode, int btbEntries, boolean decodeJal) {
            this(staticDecode, btbEntries, decodeJal, 0, 0);
        }

        public static Predictor btb(int entries) {
            if (entries < 2 || Integer.bitCount(entries) != 1) throw new IllegalArgumentException("BTB entries must be a power of two >= 2");
            return new Predictor(false, entries, true);
        }

        /** The same BTB without decode's check of JAL: jumps the BTB misses are found in execute (one bubble more). */
        public Predictor withoutDecode() {
            if (staticDecode) throw new IllegalArgumentException("the static predictor works in decode");
            return new Predictor(false, btbEntries, false, ghrBits, rasEntries);
        }

        /**
         * gshare on top of the BTB: a global history of {@code historyBits} conditional-branch outcomes, updated
         * speculatively in fetch and repaired on a redirect, and a table of 2^historyBits 2-bit counters indexed by
         * pc xor history, trained in execute. The BTB still says which pcs are branches and supplies the targets.
         */
        public Predictor withGshare(int historyBits) {
            if (btbEntries == 0) throw new IllegalArgumentException("gshare needs a BTB (its targets and branch marks)");
            if (historyBits < 2 || historyBits > 16) throw new IllegalArgumentException("gshare history must be 2..16 bits");
            return new Predictor(false, btbEntries, decodeJal, historyBits, rasEntries);
        }

        /**
         * A return-address stack of {@code entries} (a power of two): calls push pc+4 and returns pop and predict their
         * target, following the RISC-V link-register hints (x1, x5). Its top pointer and top entry are speculative and
         * repaired on a redirect.
         */
        public Predictor withRas(int entries) {
            if (btbEntries == 0) throw new IllegalArgumentException("the return-address stack needs a BTB (its call and return marks)");
            if (entries < 2 || Integer.bitCount(entries) != 1) throw new IllegalArgumentException("RAS entries must be a power of two >= 2");
            return new Predictor(false, btbEntries, decodeJal, ghrBits, entries);
        }

        /** A BTB entry with a kind field (branch, jump, call, return): gshare and the RAS need it. */
        boolean rich() { return ghrBits > 0 || rasEntries > 0; }

        boolean on() { return staticDecode || btbEntries > 0; }

        boolean inDecode() { return staticDecode || decodeJal; }
    }

    /**
     * Dual issue: what lane 1 may do with the data memory. Lane 0 always loads and stores; M stays in lane 0.
     */
    public enum Lane1Memory {
        /** No loads or stores in lane 1 (C9, C10): the data memory has one read and one write port. */
        NONE,
        /**
         * Loads and stores in lane 1 too, each lane on its own port of a true dual-port data memory; a pair whose first
         * instruction stores and whose second loads or stores is not issued together (the pair rule, in decode keys).
         */
        SPLIT,
        /**
         * As {@link #SPLIT}, but such a pair issues; lane 1 is replayed (refetched, and it comes back in lane 0) when
         * lane 0 stores to the word it accesses, found in execute from the two addresses.
         */
        REPLAY
    }

    /** Dual issue with a symmetric memory lane: lane 0 stores, lane 1 accesses memory (the pair rule of {@link Lane1Memory#SPLIT}). */
    static final Key<Bool> MEM_STORE = Key.of("memStore"), MEM_ACCESS = Key.of("memAccess");

    static final Key<UInt> PRED_NEXT = Key.of("predictedNext"), DEC_NEXT = Key.of("decodeNext");
    /** Dual issue: the next pc when no trap, interrupt, return or replay happens (what the guess is compared with). */
    static final Key<UInt> NEXT_PLAIN = Key.of("plainNextPc");
    static final Key<Bool> PRED_TAKEN = Key.of("predictedTaken"), DEC_KNOWS = Key.of("decodeKnows"), EVENT = Key.of("trapOrReturn");
    /** Dual issue: the instruction may go in lane 1 (ALU, LUI/AUIPC, jumps, branches); it issues alone (SYSTEM, FENCE). */
    static final Key<Bool> LANE1_OK = Key.of("lane1Ok"), SERIAL = Key.of("serializes");
    /** Tag bits kept per BTB entry; a false hit is only a misprediction, which execute corrects. */
    static final int BTB_TAG_BITS = 8;

    public final InOrderPipeline pipeline;
    /** The two-lane pipeline when the schedule has two lanes ({@link Schedule#withLanes}); then {@link #pipeline} is null. */
    public final DualIssuePipeline dualPipeline;
    /** Dual issue: every step runs once per lane, and lane 1 takes only ALU instructions, jumps and branches. */
    public final boolean dual;
    public final Predictor predictor;
    /** Synchronous (block RAM) instruction and data memories, as FPGAs need. */
    public final boolean bram;
    /** Division 2 bits per cycle in "memory", which stalls meanwhile (see {@link Config#seqDiv}). */
    public final boolean seqDiv;

    public ScheduledCore(int memWords, int[] image, Schedule schedule) {
        this(memWords, image, schedule, false, false);
    }

    /** With {@code zicsr}: machine-mode CSRs, traps to mtvec, MRET and the {@code irq} interrupt input. */
    public ScheduledCore(int memWords, int[] image, Schedule schedule, boolean zicsr) {
        this(memWords, image, schedule, zicsr, false);
    }

    /**
     * With {@code bram}: memories are read synchronously, like FPGA block RAM. The instruction
     * fetched in one stage arrives in the next; loads read in "execute" and get their data in
     * "memory", so the schedule must put "memory" in the stage after "execute".
     */
    public ScheduledCore(int memWords, int[] image, Schedule schedule, boolean zicsr, boolean bram) {
        this(memWords, image, schedule, new Config(zicsr, bram, false));
    }

    public ScheduledCore(int memWords, int[] image, Schedule schedule, Config config) {
        this(memWords, image, schedule, config, Predictor.NONE);
    }

    /** Dual issue: what lane 1 does with data memory ({@link Lane1Memory#NONE} for single issue). */
    public final Lane1Memory lane1Memory;

    public ScheduledCore(int memWords, int[] image, Schedule schedule, Config config, Predictor predictor) {
        this(memWords, image, schedule, config, predictor, Lane1Memory.NONE);
    }

    /** Deliberately broken predictors, for the negative controls of the predictor tests. */
    enum TestBug {
        NONE,
        /** The repair does nothing: the history and the RAS keep the wrong path's updates (only accuracy suffers). */
        NO_REPAIR,
        /** Execute does not check returns whose target came from the RAS: the core trusts its predictor (wrong results). */
        RAS_TRUSTED
    }

    final TestBug bug;

    ScheduledCore(int memWords, int[] image, Schedule schedule, Config config, Predictor predictor, TestBug bug) {
        this(memWords, image, schedule, config, predictor, Lane1Memory.NONE, false, bug);
    }

    /**
     * With {@code lane1Memory} other than NONE (a two-lane schedule and block RAM): lane 1 loads and stores too, on
     * the second port of a true dual-port data memory (the symmetric lane).
     */
    public ScheduledCore(int memWords, int[] image, Schedule schedule, Config config, Predictor predictor, Lane1Memory lane1Memory) {
        this(memWords, image, schedule, config, predictor, lane1Memory, false);
    }

    /**
     * With {@code lvt} (a two-lane schedule): the register file is a live-value table ({@link RvCore#lvtRegs}: one-write
     * LUT RAMs per write port and read port, and a table of last writers) instead of a memory built from registers.
     * Same-cycle writes of one register cannot happen (the pair check splits them); if they did, lane 1 wins.
     */
    public ScheduledCore(int memWords, int[] image, Schedule schedule, Config config, Predictor predictor, Lane1Memory lane1Memory,
                         boolean lvt) {
        this(memWords, image, schedule, config, predictor, lane1Memory, lvt, TestBug.NONE);
    }

    private ScheduledCore(int memWords, int[] image, Schedule schedule, Config config, Predictor predictor, Lane1Memory lane1Memory,
                          boolean lvt, TestBug bug) {
        super(memWords, image, config, lvt);
        if (lvt && schedule.lanes() != 2)
            throw org.eninehq.tessera.core.TesseraException.here("the live-value-table register file is for two lanes (two write ports)");
        this.lane1Memory = lane1Memory;
        if (lane1Memory != Lane1Memory.NONE && (schedule.lanes() != 2 || !config.bram()))
            throw org.eninehq.tessera.core.TesseraException.here("loads and stores in lane 1 need a two-lane schedule and block RAM"
                    + " (the data memory becomes a true dual-port RAM)");
        this.predictor = predictor;
        this.bug = bug;
        this.bram = config.bram();
        this.seqDiv = config.seqDiv();
        if (bram) {
            var stages = schedule.stageNames();
            if (stages.indexOf(schedule.stageOf("memory")) != stages.indexOf(schedule.stageOf("execute")) + 1)
                throw org.eninehq.tessera.core.TesseraException.here("with block RAM, \"memory\" must be in the stage after \"execute\""
                        + " (a synchronous read returns a cycle later); see THREE_STAGE_BRAM and FOUR_STAGE_BRAM");
        }
        this.dual = schedule.lanes() == 2;
        if (dual) {
            addChannel1();
            dualPipeline = DualIssuePipeline.build("core", model(schedule.stageOf(MUL_FINISH) != null, schedule.stageOf(MUL_SUM) != null), schedule);
            pipeline = null;
            halted.set(dualPipeline.halted());
        } else {
            pipeline = InOrderPipeline.build("core", model(schedule.stageOf(MUL_FINISH) != null, schedule.stageOf(MUL_SUM) != null), schedule);
            dualPipeline = null;
            halted.set(pipeline.halted());
        }
        if (lvtRegs != null) lvtRegs.close();
    }

    /**
     * RV32IM (and optionally Zicsr) as a sequence of steps, with no notion of stages. With {@code mulStep},
     * multiplies finish in a step of their own ({@link #MUL_FINISH}).
     */
    private InOrderModel model(boolean mulStep, boolean sumStep) {
        var d = HwDom.current();
        // no initial contents: riscv-formal starts it in any state, so the core is checked for every prediction
        btb = predictor.btbEntries() > 0 ? mem("btb", UInt.w(1 + BTB_TAG_BITS + 30 + kindBits() + 2), predictor.btbEntries()) : null;
        // gshare and the return-address stack: no reset either, so riscv-formal checks every starting state
        int h = predictor.ghrBits();
        ghr = h > 0 ? reg("ghr", UInt.w(h)) : null;
        pht = h > 0 ? mem("pht", UInt.w(2), 1 << h) : null;
        if (predictor.rasEntries() > 0) {
            rasPtr = reg("ras_ptr", UInt.w(rasPtrBits()));
            ras = new java.util.ArrayList<>();
            for (int i = 0; i < predictor.rasEntries(); i++) ras.add(reg("ras_" + i, UInt.w(30)));
            // the repair is applied a cycle late: the redirect's values are registered here, and in the next cycle fetch
            // sees the repaired stack (rasView) and builds on it; so the redirect does not fan out to every RAS register
            fixPend = reg("ras_fix_pending", Bool.TYPE);
            fixPtr = reg("ras_fix_ptr", UInt.w(rasPtrBits()));
            fixTop = reg("ras_fix_top", UInt.w(30));
            fixPush = reg("ras_fix_push", UInt.w(30));
            fixCall = reg("ras_fix_call", Bool.TYPE);
            fixRet = reg("ras_fix_ret", Bool.TYPE);
            Ras fixed = new Ras(rasPtr, ras).restore(fixPtr, fixTop).pop(fixRet).push(fixCall, fixPush);
            Ras plain = new Ras(rasPtr, ras);
            var e = new java.util.ArrayList<UInt>();
            for (int i = 0; i < ras.size(); i++) e.add(mux(fixPend, fixed.entries().get(i), plain.entries().get(i)));
            rasView = new Ras(mux(fixPend, fixed.ptr(), plain.ptr()), e);
        }
        // with Zicsr, CSRs change in "execute", which makes it a step with side effects
        Zicsr.Csrs<UInt> csrs = zicsr ? csrRegisters() : null;
        Bool handlerNext = zicsr ? reg("handler_next", Bool.TYPE, 0) : null;
        // dual issue with Zicsr: lane 1's instruction also counts in mcycle and minstret (lane 0 updates the CSRs)
        Bool lane1Fire = dual && zicsr ? wire("lane1_fire", Bool.TYPE) : null;
        Bool lane1Commit = dual && zicsr ? wire("lane1_commit", Bool.TYPE) : null;
        boolean sym = lane1Memory != Lane1Memory.NONE;
        if (sym) {
            // each lane's port of the data memory serves its "memory" stage's store or else its "execute" read; what
            // "memory" does is made later, so it reaches the port through these wires
            for (int l = 0; l < 2; l++) {
                symHoldsStore[l] = wire("dmem" + l + "_holds_store", Bool.TYPE);
                symStoring[l] = wire("dmem" + l + "_storing", Bool.TYPE);
                symStoreIdx[l] = wire("dmem" + l + "_store_idx", UInt.w(addrBits));
                symStoreWord[l] = wire("dmem" + l + "_store_word", UInt.w(32));
                // the store bypass, one entry per lane: the word each lane stored while the item now in "memory" read
                symLastValid[l] = reg("last_store" + l + "_valid", Bool.TYPE, 0);
                symLastIdx[l] = reg("last_store" + l + "_idx", UInt.w(addrBits), 0);
                symLastWord[l] = reg("last_store" + l + "_word", UInt.w(32), 0);
            }
        }
        var m = InOrderModel.create()
                .pc(PC, 32, 0, 4)
                .registerFile(lvtRegs != null ? lvtFile(lvtRegs) : org.eninehq.tessera.pipeline.RegisterFile.of(regs), true)
                .step("fetch", c -> {
                    c.put(INST, bram ? c.registered(imem.readSync(wordIndex(c.get(PC)), c.advancing()))
                            : imem.read(wordIndex(c.get(PC))));
                    if (btb != null && predictor.rich()) {
                        richFetch(c);
                    } else if (btb != null) {
                        UInt pc = c.get(PC), e = btb.read(btbIndex(pc));
                        Bool taken = btbValid(e).and(btbTag(e).eq(btbTagOf(pc))).and(e.bit(1));
                        c.put(PRED_TAKEN, taken);
                        c.put(PRED_NEXT, mux(taken, btbTarget(e), pc.addWrap(4)));
                    }
                    if (zicsr && c.lane() == 1) {
                        // the handler's first instruction is the first of its fetch pair
                        c.put(INTR, Bool.of(false));
                    } else if (zicsr) {
                        // the first instruction fetched after an interrupt starts the handler (rvfi_intr)
                        c.put(INTR, c.capture(handlerNext));
                        when(c.fire(), () -> handlerNext.set(false));
                    }
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
                    if (predictor.inDecode()) {
                        // a JAL's target is known here; the static predictor also guesses backward branches taken
                        c.put(DEC_NEXT, c.get(PC).addWrap(dec.imm()));
                        c.put(DEC_KNOWS, predictor.staticDecode() ? dec.jal().or(dec.branch().and(dec.imm().bit(31))) : dec.jal());
                    }
                    if (ras != null) {
                        Bool[] k = linkKinds(dec);
                        c.put(IS_CALL, k[0]);
                        c.put(IS_RET, k[1]);
                    }
                    if (dual) {
                        Bool alu = dec.opImm().or(dec.op()).or(dec.lui()).or(dec.auipc());
                        Bool ok1 = alu.or(dec.jal()).or(dec.jalr()).or(dec.branch());
                        if (sym) ok1 = ok1.or(dec.load()).or(dec.store());
                        c.put(LANE1_OK, ok1.and(dec.illegal().not()));
                        c.put(SERIAL, dec.system().or(dec.fence()));
                        if (lane1Memory == Lane1Memory.SPLIT) {
                            c.put(MEM_STORE, dec.store());
                            c.put(MEM_ACCESS, dec.load().or(dec.store()));
                        }
                    }
                })
                .readPort(RS1, USES_RS1, RS1_VAL)
                .readPort(RS2, USES_RS2, RS2_VAL)
                .readRegs();
        java.util.function.Consumer<org.eninehq.tessera.pipeline.StepContext> execute = c -> {
            var dec = c.get(DEC);
            var ex = Rv32i.execute(d, dec, c.get(PC), c.get(RS1_VAL), c.get(RS2_VAL));
            c.put(EX, ex);
            if (bug == TestBug.RAS_TRUSTED) c.put(CHECKED, c.get(FETCH_RET).not());
            if (c.lane() == 1) {
                // lane 1: no loads, M or CSRs. A trap here (a misaligned jump target) is replayed: the instruction does
                // not retire, fetch returns to it, and it comes back in lane 0, where the trap is taken
                Bool replay = ex.trap();
                if (sym) {
                    // loads and stores: port 1 of the data memory; a load's value is ready in "memory"
                    symRead(c, 1, dec, ex);
                    if (lane1Memory == Lane1Memory.REPLAY) {
                        // lane 0 stores to the word lane 1 accesses: lane 1 would read the word before that store
                        Bool store0 = (Bool) pendingMem[0];
                        UInt idx0 = (UInt) pendingMem[1];
                        Bool same = store0.and(dec.load().or(dec.store())).and(idx0.eq(wordIndex(ex.memAddr())));
                        replay = replay.or(same.named("lane1_same_word"));
                    }
                }
                replay = replay.named("lane1_replay");
                c.put(ALU_READY, sym ? dec.load().not() : Bool.of(true));
                if (btb != null) train(c, dec, ex);
                c.put(HALT, Bool.of(false));
                // a replayed item writes no register: a trap already clears writesRd, a same-word replay must too
                // (else a load like lw x2, 0(x2) would write the stale word and come back with a wrong address)
                c.put(WRITES_OK, lane1Memory == Lane1Memory.REPLAY ? ex.writesRd().and(replay.not()) : ex.writesRd());
                c.put(ALU, ex.result());
                c.put(COMMITS, replay.not());
                c.put(TRAP, Bool.of(false));
                c.put(NEXT_PC, mux(replay, c.get(PC), ex.nextPc()));
                if (zicsr) {
                    // a replay always fetches the instruction again (it comes back in lane 0)
                    c.put(NEXT_PLAIN, ex.nextPc());
                    c.put(EVENT, replay);
                    lane1Fire.set(c.fire());
                    lane1Commit.set(c.fire().and(replay.not()));
                }
                return;
            }
            c.put(ALU_READY, dec.load().not().and(dec.mulDiv().not()));
            // the multiply/divide unit's first half runs here, its second half in "memory"
            c.put(MD_HALF, Rv32i.mulDivStart(d, dec, c.get(RS1_VAL), c.get(RS2_VAL), seqDiv ? 0 : 16));
            if (btb != null) train(c, dec, ex);
            if (sym) {
                symRead(c, 0, dec, ex);
                pendingMem = new Object[]{dec.store(), wordIndex(ex.memAddr())};
            } else if (bram) c.put(SYNC_WORD, c.registered(dmemRead(wordIndex(ex.memAddr()), c.advancing(), true)));
            if (!zicsr) {
                c.put(HALT, ex.trap());
                c.put(WRITES_OK, ex.writesRd());
                c.put(REDIRECT, ex.redirect().and(ex.trap().not()));
                c.put(TARGET, ex.target());
                c.put(ALU, ex.result());
                c.put(COMMITS, ex.trap().not());
                c.put(TRAP, ex.trap());
                c.put(NEXT_PC, ex.nextPc());
                return;
            }
            var eff = dual ? Zicsr.step(d, dec, ex, c.get(INST), c.get(PC), c.get(RS1_VAL), csrs, irq, c.fire(), lane1Fire, lane1Commit)
                    : Zicsr.step(d, dec, ex, c.get(INST), c.get(PC), c.get(RS1_VAL), csrs, irq, c.fire());
            updateCsrs(csrs, eff.next(), c.fire());
            when(c.fire().and(eff.interrupt()), () -> handlerNext.set(true));
            c.put(EFFECT, eff);
            c.put(HALT, eff.halt());
            c.put(WRITES_OK, eff.writesRd());
            Bool branch = ex.redirect().and(eff.commits());
            c.put(REDIRECT, eff.redirect().or(branch));
            c.put(TARGET, mux(eff.redirect(), eff.target(), ex.target()));
            c.put(ALU, mux(eff.isCsr(), eff.csrValue(), ex.result()));
            c.put(COMMITS, eff.commits());
            c.put(TRAP, eff.exception());
            c.put(NEXT_PC, mux(eff.redirect(), eff.target(), ex.nextPc()));
            if (predictor.on() || dual) c.put(EVENT, eff.redirect());
            if (dual) c.put(NEXT_PLAIN, ex.nextPc());
        };
        m = zicsr ? m.effectStep("execute", execute) : m.step("execute", execute);
        // block RAM reads in "execute" miss a store written in "memory" in the same cycle: forward it
        UInt lastIdx = bram && !sym ? reg("last_store_idx", UInt.w(addrBits), 0) : null;
        UInt lastWord = bram && !sym ? reg("last_store_word", UInt.w(32), 0) : null;
        Bool lastValid = bram && !sym ? reg("last_store_valid", Bool.TYPE, 0) : null;
        m = m
                .effectStep("memory", c -> {
                    var dec = c.get(DEC);
                    if (c.lane() == 1 && sym) {
                        // lane 1 loads and stores on port 1; no M unit: its value is final here
                        UInt addr = c.get(EX).memAddr();
                        UInt word = symMemory(c, 1, dec, addr);
                        c.put(MEM_WORD, word);
                        UInt loadOrAlu = mux(dec.load(), Rv32i.loadValue(d, dec.funct3(), addr, word), c.get(ALU));
                        c.put(MEM_FWD, loadOrAlu);
                        c.put(MEM_READY, Bool.of(true));
                        if (mulStep) {
                            c.put(MD_FWD, loadOrAlu);
                            c.put(MD_READY, Bool.of(true));
                        } else {
                            c.put(RD_VAL, loadOrAlu);
                        }
                        return;
                    }
                    if (c.lane() == 1) {
                        // lane 1 has no memory port and no M unit: its value is the ALU's, final here
                        c.put(MEM_WORD, UInt.lit(0, 32));
                        c.put(MEM_FWD, c.get(ALU));
                        c.put(MEM_READY, Bool.of(true));
                        if (mulStep) {
                            c.put(MD_FWD, c.get(ALU));
                            c.put(MD_READY, Bool.of(true));
                        } else {
                            c.put(RD_VAL, c.get(ALU));
                        }
                        return;
                    }
                    UInt addr = c.get(EX).memAddr();
                    UInt idx = wordIndex(addr);
                    UInt word = sym ? symMemory(c, 0, dec, addr)
                            : bram ? mux(lastValid.and(lastIdx.eq(idx)), lastWord, c.get(SYNC_WORD)) : dmemRead(idx, Bool.of(true), false);
                    c.put(MEM_WORD, word);
                    if (mulStep) {
                        Bool isDiv = dec.mulDiv().and(dec.funct3().bit(2));
                        UInt div = seqDiv ? sequentialDivide(c, dec, true) : Rv32i.divFinish(d, c.get(MD_HALF), 16);
                        UInt loadOrAlu = mux(dec.load(), Rv32i.loadValue(d, dec.funct3(), addr, word), c.get(ALU));
                        c.put(DIV_VAL, div);
                        c.put(MD_FWD, mux(isDiv, div, loadOrAlu));
                        c.put(MD_READY, dec.mulDiv().and(isDiv.not()).not());
                        c.put(MEM_FWD, loadOrAlu);
                        c.put(MEM_READY, dec.mulDiv().not());
                    } else {
                        UInt md = seqDiv ? sequentialDivide(c, dec) : Rv32i.mulDivFinish(d, c.get(MD_HALF));
                        UInt loadOrAlu = mux(dec.load(), Rv32i.loadValue(d, dec.funct3(), addr, word), c.get(ALU));
                        c.put(RD_VAL, mux(dec.mulDiv(), md, loadOrAlu));
                        // the M result's adders stay off the forwarding path: it is forwarded a stage later
                        c.put(MEM_FWD, loadOrAlu);
                        c.put(MEM_READY, dec.mulDiv().not());
                    }
                    if (sym) return; // the store: symMemory
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
                });
        if (sumStep) m = m.step(MUL_SUM, c -> {
            if (c.lane() == 0) c.put(MSUM, Rv32i.mulSum(d, c.get(MD_HALF)));
        });
        if (mulStep)
            m = m.step(MUL_FINISH, c -> {
                if (c.lane() == 1) {
                    c.put(RD_VAL, c.get(MD_FWD));
                    return;
                }
                var dec = c.get(DEC);
                Bool isMul = dec.mulDiv().and(dec.funct3().bit(2).not());
                UInt mul = sumStep ? Rv32i.mulFix(d, c.get(MSUM)) : Rv32i.mulFinish(d, c.get(MD_HALF));
                c.put(RD_VAL, mux(isMul, mul, c.get(MD_FWD)));
            });
        m = m
                .writeRegs(RD, WRITES_OK, RD_VAL)
                .step("retire", c -> {
                    if (c.lane() == 1) {
                        // channel 1: the younger instruction of the pair; a replayed one does not retire
                        Bool retires = c.fire().and(c.halted().not()).and(c.get(COMMITS));
                        retire1(new RetireInfo(retires, c.get(INST), c.get(PC), c.get(DEC), c.get(EX), c.get(RS1_VAL), c.get(RS2_VAL),
                                c.get(RD_VAL), c.get(MEM_WORD), Bool.of(false), Bool.of(false), Bool.of(false), c.get(NEXT_PC), c.get(WRITES_OK)));
                        if (zicsr) retireCsrs1(retires);
                        return;
                    }
                    Bool interrupted = zicsr ? c.get(EFFECT).interrupt() : Bool.of(false);
                    // fire, not valid: a stage that also holds "memory" can be stalled by the divider
                    Bool retires = c.fire().and(c.halted().not()).and(interrupted.not());
                    retire(new RetireInfo(retires, c.get(INST), c.get(PC),
                            c.get(DEC), c.get(EX), c.get(RS1_VAL), c.get(RS2_VAL), c.get(RD_VAL), c.get(MEM_WORD),
                            c.get(TRAP), c.get(HALT), zicsr ? c.get(INTR) : Bool.of(false), c.get(NEXT_PC), c.get(WRITES_OK)));
                    if (zicsr) retireCsrs(retires, c.get(INST), c.get(EFFECT));
                })
                .resultAfter("execute", ALU, ALU_READY)
                .resultAfter("memory", MEM_FWD, MEM_READY);
        if (mulStep)
            // the M results' logic stays off the forwarding paths: divides from the register after "memory",
            // multiplies from the register after their own step (not at all when that is write-back)
            m = m.resultAfter("memory", MD_FWD, MD_READY, Forward.NEXT_STAGE)
                    .resultAfter(MUL_FINISH, RD_VAL, null, Forward.NEXT_STAGE);
        else
            m = m.resultAfter("retire", RD_VAL, null);
        if (dual) m = m.lanes(LANE1_OK, SERIAL, WRITES);
        // one data memory, two ports: a store in lane 0 and any access in lane 1 do not issue together
        if (lane1Memory == Lane1Memory.SPLIT) m = m.splitPairWhen(MEM_STORE, MEM_ACCESS);
        if (predictor.on()) {
            if (btb != null) m = m.predict(PRED_NEXT);
            if (predictor.rich()) speculative(m);
            // a trap, interrupt or MRET always fetches again: the next instruction's interrupt mark is captured at fetch
            if (predictor.inDecode()) m = m.resolve("decode", DEC_NEXT, DEC_KNOWS);
            if (bug == TestBug.RAS_TRUSTED) {
                // the broken core: a return predicted by the RAS is taken as resolved (CHECKED is put by "execute")
                return m.resolve("execute", NEXT_PC, CHECKED, zicsr ? EVENT : null, dual && zicsr ? NEXT_PLAIN : null).halt("execute", HALT);
            }
            return m.resolve("execute", NEXT_PC, null, zicsr ? EVENT : null, dual && zicsr ? NEXT_PLAIN : null).halt("execute", HALT);
        }
        // dual issue checks fetch's pc+4 guess per lane (resolve); single issue keeps the redirect
        // with Zicsr every trap, return and replay forces a refetch (EVENT), so the guess is compared with the plain next pc
        if (dual) return m.resolve("execute", NEXT_PC, null, zicsr ? EVENT : null, zicsr ? NEXT_PLAIN : null).halt("execute", HALT);
        return m
                .redirect("execute", REDIRECT, TARGET)
                .halt("execute", HALT);
    }

    /** The live-value table as the generator's register file. */
    private static org.eninehq.tessera.pipeline.RegisterFile lvtFile(org.eninehq.tessera.stdlib.LiveValueTable t) {
        return new org.eninehq.tessera.pipeline.RegisterFile() {
            @Override public UInt read(UInt idx) { return t.read(idx); }
            @Override public void write(UInt idx, UInt value) { t.write(idx, value); }
            @Override public int width() { return t.width(); }
            @Override public int addrWidth() { return t.addrWidth(); }
        };
    }

    /** The branch target buffer: valid, tag, target[31:2] and a 2-bit counter per entry (null without one). */
    private org.eninehq.tessera.core.Mem<UInt> btb;

    private UInt btbIndex(UInt pc) {
        return pc.bits(Integer.numberOfTrailingZeros(predictor.btbEntries()) + 1, 2);
    }

    private UInt btbTagOf(UInt pc) {
        int lo = Integer.numberOfTrailingZeros(predictor.btbEntries()) + 2;
        return pc.bits(lo + BTB_TAG_BITS - 1, lo);
    }

    private Bool btbValid(UInt e) { return e.bit(BTB_TAG_BITS + 32 + kindBits()); }

    private UInt btbTag(UInt e) { return e.bits(BTB_TAG_BITS + 31 + kindBits(), 32 + kindBits()); }

    private UInt btbTarget(UInt e) { return e.bits(31 + kindBits(), 2 + kindBits()).cat(UInt.lit(0, 2)); }

    /** The BTB entry's kind field (gshare and the RAS only): {@link #KIND_BRANCH} and the others, above the counter. */
    private int kindBits() { return predictor.rich() ? 2 : 0; }

    /**
     * Train the BTB with an item that executes: a jump or branch writes its entry (target, counter moved towards
     * what happened; a new entry starts weakly), anything else that hit as taken clears the entry it aliased.
     */
    private void train(org.eninehq.tessera.pipeline.StepContext c, Rv32i.Decoded<UInt, Bool> dec, Rv32i.Exec<UInt, Bool> ex) {
        UInt pc = c.get(PC), idx = btbIndex(pc), old = btb.read(idx);
        Bool cf = dec.jal().or(dec.jalr()).or(dec.branch()), taken = ex.redirect();
        Bool same = btbValid(old).and(btbTag(old).eq(btbTagOf(pc)));
        UInt ctr = old.bits(1, 0);
        UInt up = mux(ctr.eq(3), ctr, ctr.addWrap(1)), down = mux(ctr.eq(0), ctr, ctr.subWrap(1));
        UInt next = mux(same, mux(taken, up, down), mux(taken, UInt.lit(2, 2), UInt.lit(1, 2)));
        UInt entry = predictor.rich() ? UInt.lit(1, 1).cat(btbTagOf(pc)).cat(ex.target().bits(31, 2)).cat(kindOf(dec)).cat(next)
                : UInt.lit(1, 1).cat(btbTagOf(pc)).cat(ex.target().bits(31, 2)).cat(next);
        if (pht != null) trainPht(c, dec, ex);
        if (!dual) {
            when(c.fire().and(cf), () -> btb.write(idx, entry));
            when(c.fire().and(cf.not()).and(c.get(PRED_TAKEN)), () -> btb.write(idx, UInt.lit(0, entry.width())));
            return;
        }
        // two lanes, one write port: lane 0's update wins when both lanes train in a cycle
        Bool en = c.fire().and(cf.or(c.get(PRED_TAKEN)));
        UInt data = mux(cf, entry, UInt.lit(0, entry.width()));
        if (c.lane() == 0) {
            pendingTrain = new Object[]{en, idx, data};
            return;
        }
        Bool en0 = (Bool) pendingTrain[0];
        UInt idx0 = (UInt) pendingTrain[1], data0 = (UInt) pendingTrain[2];
        btb.write(mux(en0, idx0, idx), mux(en0, data0, data), en0.or(en));
    }

    /** Lane 0's BTB update (enable, index, entry), written together with lane 1's (not Data fields: those name signals). */
    private Object[] pendingTrain;

    // ---- gshare and the return-address stack (model code over the generator's speculative-state hook)

    /** The BTB entry kinds of a predictor with gshare or a RAS: what fetch does when the entry hits. */
    static final int KIND_BRANCH = 0, KIND_JUMP = 1, KIND_CALL = 2, KIND_RETURN = 3;
    /** Carried with each item: the global history it was predicted with, whether fetch took it for a branch, the RAS state before it. */
    static final Key<UInt> GHR_SNAP = Key.of("ghrSnapshot"), RAS_SNAP = Key.of("rasSnapshot");
    static final Key<Bool> FETCH_BR = Key.of("fetchBranch");
    /** {@link TestBug#RAS_TRUSTED} only: fetch took the target from the RAS, and whether execute checks the item. */
    static final Key<Bool> FETCH_RET = Key.of("fetchReturn"), CHECKED = Key.of("checked");
    /** The decoded RAS action, from the link-register hints: push (a call), pop (a return), or both (pop, then push). */
    static final Key<Bool> IS_CALL = Key.of("isCall"), IS_RET = Key.of("isReturn");

    private UInt ghr, rasPtr;
    private org.eninehq.tessera.core.Mem<UInt> pht;
    private java.util.List<UInt> ras;
    /** The registered repair of the RAS (see the model) and the stack as fetch sees it this cycle. */
    private Bool fixPend, fixCall, fixRet;
    private UInt fixPtr, fixTop, fixPush;
    private Ras rasView;
    /** Dual issue: slot 0's fetch-time results that slot 1 builds on (its branch mark, history and RAS after slot 0). */
    private Bool fetchBr0;
    private UInt ghrAfter0;
    private Ras rasAfter0, rasSpec0;
    private Object[] pendingPht;

    private int rasPtrBits() { return Integer.numberOfTrailingZeros(predictor.rasEntries()); }

    /** A RAS state as values: the top pointer and every entry (return address bits 31:2). */
    private record Ras(UInt ptr, java.util.List<UInt> entries) {
        UInt top() { return org.eninehq.tessera.core.Hw.index(ptr, entries); }

        Ras push(Bool en, UInt value) {
            UInt np = ptr.addWrap(1);
            var e = new java.util.ArrayList<UInt>();
            for (int i = 0; i < entries.size(); i++) e.add(mux(en.and(np.eq(i)), value, entries.get(i)));
            return new Ras(mux(en, np, ptr), e);
        }

        Ras pop(Bool en) { return new Ras(mux(en, ptr.subWrap(1), ptr), entries); }

        /** The pointer back at {@code p} and entry {@code p} back to {@code value}: the repair's restore. */
        Ras restore(UInt p, UInt value) {
            var e = new java.util.ArrayList<UInt>();
            for (int i = 0; i < entries.size(); i++) e.add(mux(p.eq(i), value, entries.get(i)));
            return new Ras(p, e);
        }

        void assignTo(UInt ptrReg, java.util.List<UInt> regs) {
            ptrReg.set(ptr);
            for (int i = 0; i < regs.size(); i++) regs.get(i).set(entries.get(i));
        }
    }

    /** {@code x} carried with the item: a register is captured; a value computed in fetch already belongs to it. */
    private static UInt carried(org.eninehq.tessera.pipeline.StepContext c, UInt x) {
        return x.stage() < 0 ? c.capture(x) : x;
    }

    private static UInt shiftIn(UInt history, Bool bit) {
        return history.bits(history.width() - 2, 0).cat(bit);
    }

    private UInt phtIndex(UInt pc, UInt history) {
        return pc.bits(predictor.ghrBits() + 1, 2).xor(history);
    }

    /** {push, pop} for a jump, from the RISC-V hint table (rd/rs1 in {x1, x5}; rd == rs1 both links: push only). */
    private static Bool[] linkKinds(Rv32i.Decoded<UInt, Bool> dec) {
        UInt rd = dec.rd().bits(4, 0), rs1 = dec.rs1().bits(4, 0);
        Bool rdLink = rd.eq(1).or(rd.eq(5)), rs1Link = rs1.eq(1).or(rs1.eq(5));
        Bool call = dec.jal().or(dec.jalr()).and(rdLink);
        Bool ret = dec.jalr().and(rs1Link).and(rdLink.and(rd.eq(rs1)).not());
        return new Bool[]{call, ret};
    }

    /** The BTB kind of an executed instruction; a pop-then-push jump is a plain jump to fetch (its target from the BTB). */
    private static UInt kindOf(Rv32i.Decoded<UInt, Bool> dec) {
        Bool[] k = linkKinds(dec);
        return mux(dec.branch(), UInt.lit(KIND_BRANCH, 2), mux(k[0].and(k[1].not()), UInt.lit(KIND_CALL, 2),
                mux(k[1].and(k[0].not()), UInt.lit(KIND_RETURN, 2), UInt.lit(KIND_JUMP, 2))));
    }

    /**
     * Fetch with gshare and/or the RAS. The BTB says whether the pc is a branch, jump, call or return; a branch's
     * direction comes from the gshare table (or the BTB counter), a return's target from the RAS (or the BTB). The
     * history and the RAS are updated here, speculatively, when the item is really fetched ({@code c.fire()}); with two
     * lanes, slot 1 sees slot 0's update (slot 1 exists only when slot 0 is guessed not to jump).
     */
    private void richFetch(org.eninehq.tessera.pipeline.StepContext c) {
        UInt pc = c.get(PC), e = btb.read(btbIndex(pc));
        Bool hit = btbValid(e).and(btbTag(e).eq(btbTagOf(pc)));
        UInt kind = e.bits(3, 2);
        Bool br = hit.and(kind.eq(KIND_BRANCH));
        boolean last = !dual || c.lane() == 1;
        Bool dir = e.bit(1);
        if (ghr != null) {
            // lane 1 is kept only behind a slot 0 guessed not to jump: a branch there shifted in a 0
            UInt hist = c.lane() == 1 ? mux(fetchBr0, shiftIn(ghr, Bool.of(false)), ghr) : ghr;
            hist = carried(c, hist);
            dir = pht.read(phtIndex(pc, hist)).bit(1);
            c.put(GHR_SNAP, hist);
            c.put(FETCH_BR, br);
            UInt base = c.lane() == 1 ? ghrAfter0 : hist;
            UInt after = mux(c.fire().and(br), shiftIn(hist, dir), base);
            if (last) ghr.set(after);
            else {
                fetchBr0 = br;
                ghrAfter0 = after;
            }
        }
        Bool taken = hit.and(kind.ne(KIND_BRANCH).or(dir));
        UInt target = btbTarget(e);
        if (ras != null) {
            // lane 1 predicts from the stack after slot 0's action, not gated by whether slot 0 is fetched (slot 1 is
            // only kept when it is): so lane 1's guess does not wait for the stall logic; the update below is gated
            Ras cur = c.lane() == 1 ? rasSpec0 : rasView;
            UInt top = cur.top();
            Bool call = hit.and(kind.eq(KIND_CALL)), ret = hit.and(kind.eq(KIND_RETURN));
            target = mux(ret, top.cat(UInt.lit(0, 2)), target);
            if (bug == TestBug.RAS_TRUSTED) c.put(FETCH_RET, ret.and(hit.and(kind.ne(KIND_BRANCH).or(dir))));
            c.put(RAS_SNAP, carried(c, cur.ptr().cat(top)));
            Ras base = c.lane() == 1 ? rasAfter0 : rasView;
            Ras after = base.pop(c.fire().and(ret)).push(c.fire().and(call), pc.addWrap(4).bits(31, 2));
            if (last) after.assignTo(rasPtr, ras);
            else {
                rasAfter0 = after;
                rasSpec0 = cur.pop(ret).push(call, pc.addWrap(4).bits(31, 2));
            }
        }
        c.put(PRED_TAKEN, taken);
        c.put(PRED_NEXT, mux(taken, target, pc.addWrap(4)));
    }

    /** The gshare counters, trained in execute with the history the branch was predicted with; lane 0 wins a conflict. */
    private void trainPht(org.eninehq.tessera.pipeline.StepContext c, Rv32i.Decoded<UInt, Bool> dec, Rv32i.Exec<UInt, Bool> ex) {
        UInt idx = phtIndex(c.get(PC), c.get(GHR_SNAP)), ctr = pht.read(idx);
        UInt up = mux(ctr.eq(3), ctr, ctr.addWrap(1)), down = mux(ctr.eq(0), ctr, ctr.subWrap(1));
        UInt next = mux(ex.redirect(), up, down);
        Bool en = c.fire().and(dec.branch());
        if (!dual) {
            pht.write(idx, next, en);
            return;
        }
        if (c.lane() == 0) {
            pendingPht = new Object[]{en, idx, next};
            return;
        }
        Bool en0 = (Bool) pendingPht[0];
        UInt idx0 = (UInt) pendingPht[1], next0 = (UInt) pendingPht[2];
        pht.write(mux(en0, idx0, idx), mux(en0, next0, next), en0.or(en));
    }

    /**
     * The predictor's speculative state, declared to the generator, and its repair: on a redirect the history and the
     * RAS go back to what the resolving item saw at fetch, plus that item's own outcome (the history shifts in its
     * direction if fetch took it for a branch; the RAS restores its top pointer and top entry, then applies the
     * item's decoded push or pop).
     */
    private void speculative(InOrderModel m) {
        if (ghr != null) m.speculative(GHR_SNAP, predictor.ghrBits()).speculative(FETCH_BR, 1);
        if (ras != null) m.speculative(RAS_SNAP, rasPtrBits() + 30).speculative(IS_CALL, 1).speculative(IS_RET, 1);
        m.onRedirect(r -> {
            if (bug == TestBug.NO_REPAIR) {
                if (ras != null) fixPend.set(false);
                return;
            }
            if (ghr != null) {
                UInt snap = r.get(GHR_SNAP);
                Bool taken = r.sequential().not();
                UInt repaired = mux(r.get(FETCH_BR), shiftIn(snap, taken), snap);
                when(r.taken(), () -> ghr.set(repaired));
            }
            if (ras != null) {
                // the resolving item's RAS state at fetch and its own action, applied next cycle (rasView)
                UInt snap = r.get(RAS_SNAP);
                fixPend.set(r.taken());
                fixPtr.set(snap.bits(snap.width() - 1, 30));
                fixTop.set(snap.bits(29, 0));
                fixPush.set(r.sequentialPc().bits(31, 2));
                fixCall.set(r.get(IS_CALL));
                fixRet.set(r.get(IS_RET));
            }
        });
    }

    /** Symmetric lanes: lane 0's store flag and word index in "execute", for lane 1's same-word replay. */
    private Object[] pendingMem;

    /**
     * Symmetric lanes, per lane: "memory" holds a store, the store happens, its word index and merged word; the bypass
     * entries (all explicitly named, so field naming leaves them alone).
     */
    private final Bool[] symHoldsStore = new Bool[2], symStoring = new Bool[2], symLastValid = new Bool[2];
    private final UInt[] symStoreIdx = new UInt[2], symStoreWord = new UInt[2], symLastIdx = new UInt[2], symLastWord = new UInt[2];

    /**
     * Symmetric lanes, in "execute": lane {@code l}'s port of the data memory. It serves the store in this lane's
     * "memory" stage when there is one, else this item's read (any item; loads and SB/SH use it). An item that needs
     * the read waits while the port is taken (SW merges nothing, so it never waits).
     */
    private void symRead(org.eninehq.tessera.pipeline.StepContext c, int l, Rv32i.Decoded<UInt, Bool> dec, Rv32i.Exec<UInt, Bool> ex) {
        UInt exIdx = wordIndex(ex.memAddr());
        Bool sel = symHoldsStore[l];
        UInt q = dmemPort(l, mux(sel, symStoreIdx[l], exIdx), mux(sel, symStoring[l], c.advancing()), symStoring[l], symStoreWord[l]);
        c.put(SYNC_WORD, c.registered(q));
        Bool reads = dec.load().or(dec.store().and(dec.funct3().bits(1, 0).ne(2)));
        c.stall(sel.and(reads).and(c.validAtEntry()).named("dmem" + l + "_port_busy"));
    }

    /**
     * Symmetric lanes, in "memory": the word this item read, corrected by the bypass (a store of either lane in the
     * cycle it read: the port read the word before it), and this item's store, handed to the port.
     */
    private UInt symMemory(org.eninehq.tessera.pipeline.StepContext c, int l, Rv32i.Decoded<UInt, Bool> dec, UInt addr) {
        var d = HwDom.current();
        UInt idx = wordIndex(addr);
        UInt word = c.get(SYNC_WORD);
        // lane 0's store first, then lane 1's (younger); the pair rule keeps them on different words anyway
        for (int k = 0; k < 2; k++) word = mux(symLastValid[k].and(symLastIdx[k].eq(idx)), symLastWord[k], word);
        UInt merged = Rv32i.storeMerge(d, dec.funct3(), addr, word, c.get(RS2_VAL));
        Bool storing = c.fire().and(dec.store()).and(c.get(COMMITS)).named("dmem" + l + "_store");
        symHoldsStore[l].set(c.valid().and(dec.store()));
        symStoring[l].set(storing);
        symStoreIdx[l].set(idx);
        symStoreWord[l].set(merged);
        // the entries describe the cycle in which the item now in "execute" reads: they change only when it moves on
        Bool lv = symLastValid[l];
        UInt li = symLastIdx[l], lw = symLastWord[l];
        when(c.advancing(), () -> {
            lv.set(storing);
            when(storing, () -> {
                li.set(idx);
                lw.set(merged);
            });
        });
        return word;
    }

    /** The M result with division by the sequential unit, which holds "memory" while it runs. */
    private UInt sequentialDivide(org.eninehq.tessera.pipeline.StepContext c, Rv32i.Decoded<UInt, Bool> dec) {
        return sequentialDivide(c, dec, false);
    }

    private UInt sequentialDivide(org.eninehq.tessera.pipeline.StepContext c, Rv32i.Decoded<UInt, Bool> dec, boolean divOnly) {
        Bool isDiv = dec.mulDiv().and(dec.funct3().bit(2)).named("is_div");
        var div = sequentialDivide(c.valid().and(isDiv), c.advancing(), c.get(MD_HALF), divOnly);
        c.stall(div.stall());
        return div.value();
    }
}
