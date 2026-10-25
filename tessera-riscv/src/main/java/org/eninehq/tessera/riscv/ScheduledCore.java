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
    public record Predictor(boolean staticDecode, int btbEntries, boolean decodeJal) {
        public static final Predictor NONE = new Predictor(false, 0, false);
        public static final Predictor STATIC = new Predictor(true, 0, true);

        public static Predictor btb(int entries) {
            if (entries < 2 || Integer.bitCount(entries) != 1) throw new IllegalArgumentException("BTB entries must be a power of two >= 2");
            return new Predictor(false, entries, true);
        }

        /** The same BTB without decode's check of JAL: jumps the BTB misses are found in execute (one bubble more). */
        public Predictor withoutDecode() {
            if (staticDecode) throw new IllegalArgumentException("the static predictor works in decode");
            return new Predictor(false, btbEntries, false);
        }

        boolean on() { return staticDecode || btbEntries > 0; }

        boolean inDecode() { return staticDecode || decodeJal; }
    }

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

    public ScheduledCore(int memWords, int[] image, Schedule schedule, Config config, Predictor predictor) {
        super(memWords, image, config);
        this.predictor = predictor;
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
    }

    /**
     * RV32IM (and optionally Zicsr) as a sequence of steps, with no notion of stages. With {@code mulStep},
     * multiplies finish in a step of their own ({@link #MUL_FINISH}).
     */
    private InOrderModel model(boolean mulStep, boolean sumStep) {
        var d = HwDom.current();
        // no initial contents: riscv-formal starts it in any state, so the core is checked for every prediction
        btb = predictor.btbEntries() > 0 ? mem("btb", UInt.w(1 + BTB_TAG_BITS + 30 + 2), predictor.btbEntries()) : null;
        // with Zicsr, CSRs change in "execute", which makes it a step with side effects
        Zicsr.Csrs<UInt> csrs = zicsr ? csrRegisters() : null;
        Bool handlerNext = zicsr ? reg("handler_next", Bool.TYPE, 0) : null;
        // dual issue with Zicsr: lane 1's instruction also counts in mcycle and minstret (lane 0 updates the CSRs)
        Bool lane1Fire = dual && zicsr ? wire("lane1_fire", Bool.TYPE) : null;
        Bool lane1Commit = dual && zicsr ? wire("lane1_commit", Bool.TYPE) : null;
        var m = InOrderModel.create()
                .pc(PC, 32, 0, 4)
                .registerFile(regs, true)
                .step("fetch", c -> {
                    c.put(INST, bram ? c.registered(imem.readSync(wordIndex(c.get(PC)), c.advancing()))
                            : imem.read(wordIndex(c.get(PC))));
                    if (btb != null) {
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
                    if (dual) {
                        Bool alu = dec.opImm().or(dec.op()).or(dec.lui()).or(dec.auipc());
                        c.put(LANE1_OK, alu.or(dec.jal()).or(dec.jalr()).or(dec.branch()).and(dec.illegal().not()));
                        c.put(SERIAL, dec.system().or(dec.fence()));
                    }
                })
                .readPort(RS1, USES_RS1, RS1_VAL)
                .readPort(RS2, USES_RS2, RS2_VAL)
                .readRegs();
        java.util.function.Consumer<org.eninehq.tessera.pipeline.StepContext> execute = c -> {
            var dec = c.get(DEC);
            var ex = Rv32i.execute(d, dec, c.get(PC), c.get(RS1_VAL), c.get(RS2_VAL));
            c.put(EX, ex);
            if (c.lane() == 1) {
                // lane 1: no loads, M or CSRs. A trap here (a misaligned jump target) is replayed: the instruction does
                // not retire, fetch returns to it, and it comes back in lane 0, where the trap is taken
                Bool replay = ex.trap().named("lane1_replay");
                c.put(ALU_READY, Bool.of(true));
                if (btb != null) train(c, dec, ex);
                c.put(HALT, Bool.of(false));
                c.put(WRITES_OK, ex.writesRd());
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
            if (bram) c.put(SYNC_WORD, c.registered(dmemRead(wordIndex(ex.memAddr()), c.advancing(), true)));
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
        UInt lastIdx = bram ? reg("last_store_idx", UInt.w(addrBits), 0) : null;
        UInt lastWord = bram ? reg("last_store_word", UInt.w(32), 0) : null;
        Bool lastValid = bram ? reg("last_store_valid", Bool.TYPE, 0) : null;
        m = m
                .effectStep("memory", c -> {
                    var dec = c.get(DEC);
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
                    UInt word = bram ? mux(lastValid.and(lastIdx.eq(idx)), lastWord, c.get(SYNC_WORD)) : dmemRead(idx, Bool.of(true), false);
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
        if (predictor.on()) {
            if (btb != null) m = m.predict(PRED_NEXT);
            // a trap, interrupt or MRET always fetches again: the next instruction's interrupt mark is captured at fetch
            if (predictor.inDecode()) m = m.resolve("decode", DEC_NEXT, DEC_KNOWS);
            return m.resolve("execute", NEXT_PC, null, zicsr ? EVENT : null, dual && zicsr ? NEXT_PLAIN : null).halt("execute", HALT);
        }
        // dual issue checks fetch's pc+4 guess per lane (resolve); single issue keeps the redirect
        // with Zicsr every trap, return and replay forces a refetch (EVENT), so the guess is compared with the plain next pc
        if (dual) return m.resolve("execute", NEXT_PC, null, zicsr ? EVENT : null, zicsr ? NEXT_PLAIN : null).halt("execute", HALT);
        return m
                .redirect("execute", REDIRECT, TARGET)
                .halt("execute", HALT);
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

    private static Bool btbValid(UInt e) { return e.bit(BTB_TAG_BITS + 32); }

    private static UInt btbTag(UInt e) { return e.bits(BTB_TAG_BITS + 31, 32); }

    private static UInt btbTarget(UInt e) { return e.bits(31, 2).cat(UInt.lit(0, 2)); }

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
        UInt entry = UInt.lit(1, 1).cat(btbTagOf(pc)).cat(ex.target().bits(31, 2)).cat(next);
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
