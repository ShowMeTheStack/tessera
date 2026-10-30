package org.eninehq.tessera.riscv;

import static org.eninehq.tessera.core.Hw.mux;
import static org.eninehq.tessera.core.Hw.when;

import org.eninehq.tessera.core.Bool;
import org.eninehq.tessera.core.TesseraException;
import org.eninehq.tessera.core.UInt;

/**
 * C11: the out-of-order core of {@link OooCore} (Tomasulo with a reorder buffer, the same fetch, static prediction,
 * issue, multiply/divide unit and commit) with <b>execute-time branch recovery</b> .
 *
 * <ul>
 *   <li>Every conditional branch and JALR takes a <b>checkpoint</b> of the register alias table when it dispatches
 *       (after its own rename of rd); dispatch stalls while none of the {@code checkpoints} is free. JAL needs none:
 *       its target is known at dispatch, so it is never mispredicted.</li>
 *   <li>Every ROB entry, reservation station and the multiply/divide unit carry a <b>branch mask</b>: the checkpoints
 *       of the unresolved branches older than the instruction.</li>
 *   <li>A branch resolves on the ALU. If it was predicted right (or it traps, which commit handles), its checkpoint is
 *       freed and its bit cleared from every mask. If it was mispredicted, in the same cycle: the RAT is restored from
 *       its checkpoint, the younger ROB entries (tail moved to the branch + 1), reservation stations and checkpoints
 *       (by mask) and a younger operation in the multiply/divide unit are squashed, and fetch is redirected.</li>
 *   <li>The restored RAT keeps a rename only while the instruction it names is still in flight (older than the branch
 *       and not yet committed, also this cycle), so commits between the checkpoint and the restore need no
 *       per-checkpoint bookkeeping.</li>
 *   <li>Memory still happens at the head (as in C7), and traps, interrupts and MRET are taken at commit, flushing
 *       everything, so they stay precise. No mispredict flushes at commit any more.</li>
 * </ul>
 * RVFI reports instructions as they commit, as in C7.
 */
public class OooSpecCore extends RvCore {
    public static final int ROB_DEFAULT = 16, RS_DEFAULT = 8, CP_DEFAULT = 4, LQ_DEFAULT = 4, SQ_DEFAULT = 4;
    private final int ROB, RS, TAG, NCP, CPB, LQ, SQ;
    /** With a load/store queue (LQ and SQ entries); without, memory instructions run at the head as in C7. */
    public final boolean lsq;
    /** With the LSQ: the data memory as a true dual-port RAM (port A speculative loads, port B the commit). */
    public final boolean tdp;
    public final boolean bram;

    /** The default window: ROB 16, RS 8, 4 checkpoints, LQ 4, SQ 4. */
    public OooSpecCore(int memWords, int[] image, Config config) {
        this(memWords, image, config, ROB_DEFAULT, RS_DEFAULT, CP_DEFAULT, LQ_DEFAULT, SQ_DEFAULT);
    }

    /** Without a load/store queue (execute-time branch recovery only). */
    public OooSpecCore(int memWords, int[] image, Config config, int robEntries, int rsEntries, int checkpoints) {
        this(memWords, image, config, robEntries, rsEntries, checkpoints, 0, 0);
    }

    /**
     * {@code robEntries} (a power of two), {@code rsEntries}, {@code checkpoints}, {@code lqEntries} and {@code sqEntries}
     * size the window; no load/store queue when both queue sizes are 0.
     */
    public OooSpecCore(int memWords, int[] image, Config config, int robEntries, int rsEntries, int checkpoints, int lqEntries,
                       int sqEntries) {
        this(memWords, image, config, robEntries, rsEntries, checkpoints, lqEntries, sqEntries, false);
    }

    /**
     * As above; with {@code tdp} (needs the LSQ and block RAM) the data memory is a true dual-port RAM ({@link TdpPort}):
     * speculative loads read on port A, the commit reads (to merge a byte or halfword store) and writes on port B, so a
     * load never waits for the port.
     */
    public OooSpecCore(int memWords, int[] image, Config config, int robEntries, int rsEntries, int checkpoints, int lqEntries,
                       int sqEntries, boolean tdp) {
        super(memWords, image, config);
        if (tdp && (lqEntries == 0 || !config.bram()))
            throw TesseraException.here("a true dual-port data memory needs the load/store queue and block RAM");
        this.tdp = tdp;
        if (Integer.bitCount(robEntries) != 1 || robEntries < 2) throw TesseraException.here("the ROB size must be a power of two");
        if (checkpoints < 1) throw TesseraException.here("at least one checkpoint is needed");
        if ((lqEntries == 0) != (sqEntries == 0) || lqEntries < 0 || sqEntries < 0)
            throw TesseraException.here("the load and store queues are both present (sizes >= 1) or both absent (0)");
        LQ = lqEntries;
        SQ = sqEntries;
        lsq = LQ > 0;
        ROB = robEntries;
        RS = rsEntries;
        TAG = Integer.numberOfTrailingZeros(robEntries);
        NCP = checkpoints;
        CPB = Math.max(1, 32 - Integer.numberOfLeadingZeros(checkpoints - 1));
        this.bram = config.bram();
        var d = HwDom.current();

        // ---------------------------------------------------------------- state
        UInt pc = reg("pc", UInt.w(32), 0);
        Bool fv = reg("fetch_valid", Bool.TYPE, 0);
        UInt fpc = reg("fetch_pc", UInt.w(32), 0);
        Bool halt = reg("halt", Bool.TYPE, 0);

        Bool[] rValid = new Bool[ROB], rDone = new Bool[ROB], rTrap = new Bool[ROB];
        // loads and CSR instructions: their value is known only at commit
        Bool[] rIsLoad = new Bool[ROB];
        Bool[][] rMask = new Bool[ROB][NCP];
        UInt[] rPc = new UInt[ROB], rInst = new UInt[ROB], rA = new UInt[ROB], rB = new UInt[ROB], rVal = new UInt[ROB],
                rNext = new UInt[ROB];
        for (int i = 0; i < ROB; i++) {
            rValid[i] = reg("rob" + i + "_valid", Bool.TYPE, 0);
            rDone[i] = reg("rob" + i + "_done", Bool.TYPE, 0);
            rTrap[i] = reg("rob" + i + "_trap", Bool.TYPE, 0);
            rIsLoad[i] = reg("rob" + i + "_load", Bool.TYPE, 0);
            for (int k = 0; k < NCP; k++) rMask[i][k] = reg("rob" + i + "_bm" + k, Bool.TYPE, 0);
            rPc[i] = reg("rob" + i + "_pc", UInt.w(32), 0);
            rInst[i] = reg("rob" + i + "_inst", UInt.w(32), 0);
            rA[i] = reg("rob" + i + "_rs1v", UInt.w(32), 0);
            rB[i] = reg("rob" + i + "_rs2v", UInt.w(32), 0);
            rVal[i] = reg("rob" + i + "_value", UInt.w(32), 0);
            rNext[i] = reg("rob" + i + "_next_pc", UInt.w(32), 0);
        }
        UInt head = reg("rob_head", UInt.w(TAG), 0), tail = reg("rob_tail", UInt.w(TAG), 0);
        UInt count = reg("rob_count", UInt.w(TAG + 1), 0);

        Bool[] sValid = new Bool[RS], sIsMd = new Bool[RS], s1Rdy = new Bool[RS], s2Rdy = new Bool[RS], sHasCp = new Bool[RS];
        Bool[][] sMask = new Bool[RS][NCP];
        UInt[] sTag = new UInt[RS], sPc = new UInt[RS], sInst = new UInt[RS], sPred = new UInt[RS], sCp = new UInt[RS],
                s1Val = new UInt[RS], s2Val = new UInt[RS], s1Tag = new UInt[RS], s2Tag = new UInt[RS];
        for (int j = 0; j < RS; j++) {
            sValid[j] = reg("rs" + j + "_valid", Bool.TYPE, 0);
            sIsMd[j] = reg("rs" + j + "_muldiv", Bool.TYPE, 0);
            s1Rdy[j] = reg("rs" + j + "_a_ready", Bool.TYPE, 0);
            s2Rdy[j] = reg("rs" + j + "_b_ready", Bool.TYPE, 0);
            sHasCp[j] = reg("rs" + j + "_has_cp", Bool.TYPE, 0);
            for (int k = 0; k < NCP; k++) sMask[j][k] = reg("rs" + j + "_bm" + k, Bool.TYPE, 0);
            sTag[j] = reg("rs" + j + "_tag", UInt.w(TAG), 0);
            sPc[j] = reg("rs" + j + "_pc", UInt.w(32), 0);
            sInst[j] = reg("rs" + j + "_inst", UInt.w(32), 0);
            sPred[j] = reg("rs" + j + "_pred", UInt.w(32), 0);
            sCp[j] = reg("rs" + j + "_cp", UInt.w(CPB), 0);
            s1Val[j] = reg("rs" + j + "_a", UInt.w(32), 0);
            s2Val[j] = reg("rs" + j + "_b", UInt.w(32), 0);
            s1Tag[j] = reg("rs" + j + "_a_tag", UInt.w(TAG), 0);
            s2Tag[j] = reg("rs" + j + "_b_tag", UInt.w(TAG), 0);
        }

        Bool[] ratBusy = new Bool[32];
        UInt[] ratTag = new UInt[32];
        ratBusy[0] = Bool.of(false);                // x0 is never renamed
        ratTag[0] = UInt.lit(0, TAG);
        for (int r = 1; r < 32; r++) {
            ratBusy[r] = reg("rat" + r + "_busy", Bool.TYPE, 0);
            ratTag[r] = reg("rat" + r + "_tag", UInt.w(TAG), 0);
        }

        // checkpoints: in use, the mask of the branches older than the owner, and the RAT after the owner's dispatch
        Bool[] cpValid = new Bool[NCP];
        Bool[][] cpMask = new Bool[NCP][NCP], cpBusy = new Bool[NCP][32];
        UInt[][] cpTag = new UInt[NCP][32];
        for (int c = 0; c < NCP; c++) {
            cpValid[c] = reg("cp" + c + "_valid", Bool.TYPE, 0);
            for (int k = 0; k < NCP; k++) cpMask[c][k] = reg("cp" + c + "_bm" + k, Bool.TYPE, 0);
            cpBusy[c][0] = Bool.of(false);
            cpTag[c][0] = UInt.lit(0, TAG);
            for (int r = 1; r < 32; r++) {
                cpBusy[c][r] = reg("cp" + c + "_rat" + r + "_busy", Bool.TYPE, 0);
                cpTag[c][r] = reg("cp" + c + "_rat" + r + "_tag", UInt.w(TAG), 0);
            }
        }

        // load and store queues: in program order by ROB tag; addresses (and store data) from the ALU
        Bool[] lValid = new Bool[LQ], lAddrOk = new Bool[LQ], lIssued = new Bool[LQ];
        Bool[][] lMask = new Bool[LQ][NCP];
        UInt[] lTag = new UInt[LQ], lAddr = new UInt[LQ], lF3 = new UInt[LQ];
        for (int l = 0; l < LQ; l++) {
            lValid[l] = reg("lq" + l + "_valid", Bool.TYPE, 0);
            lAddrOk[l] = reg("lq" + l + "_addr_ok", Bool.TYPE, 0);
            lIssued[l] = reg("lq" + l + "_issued", Bool.TYPE, 0);
            for (int k = 0; k < NCP; k++) lMask[l][k] = reg("lq" + l + "_bm" + k, Bool.TYPE, 0);
            lTag[l] = reg("lq" + l + "_tag", UInt.w(TAG), 0);
            lAddr[l] = reg("lq" + l + "_addr", UInt.w(32), 0);
            lF3[l] = reg("lq" + l + "_f3", UInt.w(3), 0);
        }
        Bool[] qValid = new Bool[SQ], qAddrOk = new Bool[SQ];
        Bool[][] qMask = new Bool[SQ][NCP];
        UInt[] qTag = new UInt[SQ], qAddr = new UInt[SQ], qF3 = new UInt[SQ], qData = new UInt[SQ];
        for (int q = 0; q < SQ; q++) {
            qValid[q] = reg("sq" + q + "_valid", Bool.TYPE, 0);
            qAddrOk[q] = reg("sq" + q + "_addr_ok", Bool.TYPE, 0);
            for (int k = 0; k < NCP; k++) qMask[q][k] = reg("sq" + q + "_bm" + k, Bool.TYPE, 0);
            qTag[q] = reg("sq" + q + "_tag", UInt.w(TAG), 0);
            qAddr[q] = reg("sq" + q + "_addr", UInt.w(32), 0);
            qF3[q] = reg("sq" + q + "_f3", UInt.w(3), 0);
            qData[q] = reg("sq" + q + "_data", UInt.w(32), 0);
        }

        // ---------------------------------------------------------------- commit (head of the ROB)
        Bool hValid = pickB(head, rValid), hDone = pickB(head, rDone), hTrap = pickB(head, rTrap);
        UInt hInst = pick(head, rInst).named("commit_inst"), hPc = pick(head, rPc).named("commit_pc");
        UInt hA = pick(head, rA), hB = pick(head, rB), hNext = pick(head, rNext);
        var hDec = Rv32i.decode(d, hInst, zicsr);
        var hEx = Rv32i.execute(d, hDec, hPc, hA, hB);
        // with the LSQ only byte and halfword stores use memory at the head (to read the word they merge into); loads have
        // their value already, and a word store writes the whole word at once
        Bool hIsMem = (lsq ? hDec.store().and(hDec.funct3().bits(1, 0).ne(2)) : hDec.load().or(hDec.store())).and(hTrap.not());
        Bool ready = hValid.and(hDone).and(halt.not()).named("commit_ready");
        // memory instructions take two cycles at the head: read the word, then commit
        Bool memPhase = reg("mem_phase", Bool.TYPE, 0);
        Bool memStart = ready.and(hIsMem).and(memPhase.not());
        UInt memIdx = wordIndex(hEx.memAddr());
        UInt word;
        // a speculative load's read request (from the load queue, below) and the words for the commit and the load
        UInt ldReqIdx = lsq ? wire("ld_req_idx", UInt.w(memIdx.width())) : null;
        Bool ldReq = lsq ? wire("ld_req", Bool.TYPE) : null;
        DataPort.Words words = null;
        TdpPort tdpPort = tdp ? new TdpPort() : null;
        if (tdp) {
            words = tdpPort.read(this, memIdx, memStart, ldReqIdx, ldReq);
            word = words.commit();
        } else if (lsq) {
            words = new SinglePort().read(this, memIdx, memStart, ldReqIdx, ldReq);
            word = words.commit();
        } else if (bram) {
            word = dmemRead(memIdx, memStart, true);
        } else {
            UInt w = reg("mem_word", UInt.w(32), 0);
            UInt live = dmemRead(memIdx, memStart, false);
            when(memStart, () -> w.set(live));
            word = w;
        }
        // the head's turn: it commits, traps, or (with Zicsr) gives way to an interrupt
        Bool turn = ready.and(hIsMem.not().or(memPhase)).named("commit_turn");
        when(memStart, () -> memPhase.set(true));
        when(turn, () -> memPhase.set(false));
        UInt loadOrValue = lsq ? pick(head, rVal) : mux(hDec.load(), Rv32i.loadValue(d, hDec.funct3(), hEx.memAddr(), word), pick(head, rVal));
        // RVFI's memory word: for a load with the LSQ, the word it read (or was forwarded), kept in its rs2 slot (loads
        // read no rs2, and RVFI reports rs2 and the write data of a load as zero)
        UInt rvfiWord = lsq ? mux(hDec.load(), hB, word) : word;
        UInt cRd = hDec.rd().bits(4, 0);
        Bool commit, cWrites, flush, storeOk;
        UInt cVal, flushTarget;
        if (zicsr) {
            var csrs = csrRegisters();
            var eff = Zicsr.step(d, hDec, hEx, hInst, hPc, hA, csrs, irq, turn);
            updateCsrs(csrs, eff.next(), turn);
            // rvfi_intr marks the first instruction of a handler entered by an interrupt
            Bool intrNext = reg("intr_next", Bool.TYPE, 0);
            commit = turn.and(eff.interrupt().not()).named("commit");
            when(turn.and(eff.interrupt()), () -> intrNext.set(true));
            when(commit, () -> intrNext.set(false));
            cVal = mux(eff.isCsr(), eff.csrValue(), loadOrValue).named("commit_value");
            cWrites = eff.writesRd();
            storeOk = eff.commits();
            when(eff.halt(), () -> halt.set(true));
            // only traps, interrupts and MRET flush at commit: mispredictions were recovered at execute
            flush = turn.and(eff.redirect()).named("flush");
            flushTarget = mux(eff.redirect(), eff.target(), hNext);
            retire(new RetireInfo(commit, hInst, hPc, hDec, hEx, hA, hB, cVal, rvfiWord, eff.exception(), eff.halt(), intrNext,
                    flushTarget, cWrites));
            retireCsrs(commit, hInst, eff);
        } else {
            commit = turn.named("commit");
            cVal = loadOrValue.named("commit_value");
            cWrites = hDec.writesRd().and(hTrap.not());
            storeOk = hTrap.not();
            when(commit.and(hTrap), () -> halt.set(true));
            flush = Bool.of(false);
            flushTarget = hNext;
            retire(new RetireInfo(commit, hInst, hPc, hDec, hEx, hA, hB, cVal, rvfiWord, hTrap, hTrap, Bool.of(false), hNext, cWrites));
        }
        when(commit.and(cWrites), () -> regs.write(cRd, cVal));
        if (tdp) tdpPort.write(Rv32i.storeMerge(d, hDec.funct3(), hEx.memAddr(), word, hB), commit.and(hDec.store()).and(storeOk));
        else dmemWrite(memIdx, Rv32i.storeMerge(d, hDec.funct3(), hEx.memAddr(), word, hB), commit.and(hDec.store()).and(storeOk));
        halted.set(halt);

        // ---------------------------------------------------------------- result buses
        // ALU: oldest ready non-multiply/divide entry
        Bool[] aluCand = new Bool[RS], mdCand = new Bool[RS];
        for (int j = 0; j < RS; j++) {
            Bool rdy = sValid[j].and(s1Rdy[j]).and(s2Rdy[j]);
            aluCand[j] = rdy.and(sIsMd[j].not());
            mdCand[j] = rdy.and(sIsMd[j]);
        }
        Bool mdBusy = reg("md_busy", Bool.TYPE, 0);
        Bool[] aluPick = oldest(aluCand, sTag, head);
        Bool[] mdPick = oldest(and(mdCand, mdBusy.not()), sTag, head);
        Bool aluIssue = any(aluPick).named("alu_issue"), mdIssue = any(mdPick).named("md_issue");

        UInt aInst = pickOneHot(aluPick, sInst), aPc = pickOneHot(aluPick, sPc), aA = pickOneHot(aluPick, s1Val),
                aB = pickOneHot(aluPick, s2Val), aPred = pickOneHot(aluPick, sPred), aTag = pickOneHot(aluPick, sTag);
        var aDec = Rv32i.decode(d, aInst, zicsr);
        var aEx = Rv32i.execute(d, aDec, aPc, aA, aB);
        Bool aluBus = aluIssue.and(late(aDec).not()).named("alu_bus");   // with the LSQ the ALU computes a load's address only
        UInt aluVal = aEx.result();

        // branch resolution: a checkpointed instruction on the ALU; a mispredict that does not trap recovers now
        Bool aHasCp = pickOneHotB(aluPick, sHasCp);
        UInt aCp = pickOneHot(aluPick, sCp);
        Bool[] resBit = new Bool[NCP];
        for (int k = 0; k < NCP; k++) resBit[k] = aCp.eq(k);
        Bool resolve = aluIssue.and(aHasCp).named("br_resolve");
        Bool recover = resolve.and(aEx.nextPc().ne(aPred)).and(aEx.trap().not()).named("br_recover");
        Bool resolveOk = resolve.and(recover.not()).named("br_resolve_ok");
        UInt recoverTarget = aEx.nextPc().named("br_target");

        // multiply/divide unit, cancelled by a recovery if its operation is younger than the branch
        UInt mInst = pickOneHot(mdPick, sInst), mA = pickOneHot(mdPick, s1Val), mB = pickOneHot(mdPick, s2Val);
        var md = mulDivUnit(mdBusy, mdIssue, pickOneHot(mdPick, sTag), mInst, mA, mB, zicsr);
        Bool[] mdIssueMask = new Bool[NCP];
        for (int k = 0; k < NCP; k++) mdIssueMask[k] = pickOneHotB(mdPick, column(sMask, k));
        mulDivSquash(mdBusy, mdIssue, mdIssueMask, recover, resolveOk, resBit);
        UInt mdTag = md.tag(), mdVal = md.value();
        UInt[] mdRegs = {md.a(), md.b()};
        Bool mdBus = md.bus();

        // ---------------------------------------------------------------- the load/store queues
        Bool ldDone = null;
        UInt ldTag = null, ldVal = null, ldWord = null;
        Bool[] ldFree = null, ldPick = null;
        if (lsq) {
            // the address (and a store's data) from the ALU; a misaligned load leaves the queue (commit traps)
            for (int l = 0; l < LQ; l++) {
                final int ll = l;
                when(aluIssue.and(lValid[l]).and(lTag[l].eq(aTag)), () -> {
                    lAddrOk[ll].set(true);
                    lAddr[ll].set(aEx.memAddr());
                    when(aEx.trap(), () -> lValid[ll].set(false));
                });
            }
            for (int q = 0; q < SQ; q++) {
                final int qq = q;
                when(aluIssue.and(qValid[q]).and(qTag[q].eq(aTag)), () -> {
                    qAddrOk[qq].set(true);
                    qAddr[qq].set(aEx.memAddr());
                    qData[qq].set(aB);
                });
                // a store leaves the queue when it commits (it has written memory)
                when(commit.and(qValid[q]).and(qTag[q].eq(head)), () -> qValid[qq].set(false));
            }
            // a load may go when every older store's address is known; the youngest older store to the same word
            // forwards if it writes every byte the load reads, otherwise the load waits for it to commit
            Bool lrPend = reg("ld_read_pending", Bool.TYPE, 0);
            UInt lrIdx = reg("ld_read_lq", UInt.w(Math.max(1, 32 - Integer.numberOfLeadingZeros(LQ - 1))), 0);
            Bool[] canGo = new Bool[LQ], viaFwd = new Bool[LQ];
            Bool[][] fwdFrom = new Bool[LQ][SQ];
            for (int l = 0; l < LQ; l++) {
                UInt lAge = lTag[l].subWrap(head), lIdx = wordIndex(lAddr[l]);
                UInt lBytes = bytes(lF3[l], lAddr[l]);
                Bool unknown = Bool.of(false), anyMatch = Bool.of(false), covered = Bool.of(false);
                Bool[] match = new Bool[SQ];
                UInt[] qAge = new UInt[SQ];
                for (int q = 0; q < SQ; q++) {
                    qAge[q] = qTag[q].subWrap(head);
                    Bool older = qValid[q].and(qAge[q].lt(lAge));
                    unknown = unknown.or(older.and(qAddrOk[q].not()));
                    match[q] = older.and(qAddrOk[q]).and(wordIndex(qAddr[q]).eq(lIdx));
                    anyMatch = anyMatch.or(match[q]);
                }
                for (int q = 0; q < SQ; q++) {
                    Bool youngest = match[q];
                    for (int o = 0; o < SQ; o++) if (o != q) youngest = youngest.and(match[o].and(qAge[o].gt(qAge[q])).not());
                    fwdFrom[l][q] = youngest;
                    Bool covers = lBytes.and(bytes(qF3[q], qAddr[q]).not()).eq(0);
                    covered = covered.or(youngest.and(covers));
                }
                Bool lReady = lValid[l].and(lAddrOk[l]).and(lIssued[l].not()).and(unknown.not());
                // a forwarded load uses the load bus now (not while a read returns); a read needs the port
                viaFwd[l] = anyMatch;
                canGo[l] = lReady.and(mux(anyMatch, covered.and(lrPend.not()), words.loadGranted()));
            }
            ldPick = oldest(canGo, lTag, head);
            Bool picked = any(ldPick);
            Bool fwdNow = picked.and(pickOneHotB(ldPick, viaFwd)).named("ld_forward");
            Bool memReq = picked.and(fwdNow.not()).named("ld_read");
            ldReq.set(memReq);
            ldReqIdx.set(wordIndex(pickOneHot(ldPick, lAddr)));
            Bool[] fsel = new Bool[SQ];
            for (int q = 0; q < SQ; q++) {
                Bool f = Bool.of(false);
                for (int l = 0; l < LQ; l++) f = f.or(ldPick[l].and(fwdFrom[l][q]));
                fsel[q] = f;
            }
            UInt fwdWord = Rv32i.storeMerge(d, pickOneHot(fsel, qF3), pickOneHot(fsel, qAddr), UInt.lit(0, 32), pickOneHot(fsel, qData));
            // the read returns the next cycle (the load may have been squashed meanwhile)
            Bool[] retSel = new Bool[LQ];
            for (int l = 0; l < LQ; l++) retSel[l] = lrPend.and(lrIdx.eq(l));
            Bool retNow = lrPend.and(pickB(lrIdx, lValid)).named("ld_return");
            UInt lqPicked = UInt.lit(0, lrIdx.width());
            for (int l = 1; l < LQ; l++) lqPicked = mux(ldPick[l], UInt.lit(l, lrIdx.width()), lqPicked);
            final UInt lqPickedF = lqPicked;
            lrPend.set(memReq);
            when(memReq, () -> lrIdx.set(lqPickedF));
            ldFree = new Bool[LQ];
            for (int l = 0; l < LQ; l++) ldFree[l] = mux(retNow, retSel[l], ldPick[l].and(fwdNow));
            ldDone = retNow.or(fwdNow).named("ld_done");
            ldWord = mux(retNow, words.load(), fwdWord).named("ld_word");
            ldTag = mux(retNow, pick(lrIdx, lTag), pickOneHot(ldPick, lTag));
            UInt ldF3 = mux(retNow, pick(lrIdx, lF3), pickOneHot(ldPick, lF3));
            UInt ldAddr = mux(retNow, pick(lrIdx, lAddr), pickOneHot(ldPick, lAddr));
            ldVal = Rv32i.loadValue(d, ldF3, ldAddr, ldWord).named("ld_value");
            for (int l = 0; l < LQ; l++) {
                final int ll = l;
                when(memReq.and(ldPick[l]), () -> lIssued[ll].set(true));
                when(ldDone.and(ldFree[l]), () -> lValid[ll].set(false));
            }
        }

        // the buses: ALU, multiply/divide, commit (for CSR instructions, and loads without the LSQ, whose value
        // appears only there), and with the LSQ the loads
        UInt[] busTag = lsq ? new UInt[]{aTag, mdTag, head, ldTag} : new UInt[]{aTag, mdTag, head};
        UInt[] busVal = lsq ? new UInt[]{aluVal, mdVal, cVal, ldVal} : new UInt[]{aluVal, mdVal, cVal};
        Bool[] busOn = lsq ? new Bool[]{aluBus, mdBus, commit.and(cWrites), ldDone} : new Bool[]{aluBus, mdBus, commit.and(cWrites)};

        // results into the ROB
        for (int i = 0; i < ROB; i++) {
            final int e = i;
            when(aluIssue.and(aTag.eq(e)), () -> {
                if (lsq) rDone[e].set(aDec.load().not().or(aEx.trap()));
                else rDone[e].set(true);
                rA[e].set(aA);
                rB[e].set(aB);
                rVal[e].set(aluVal);
                rNext[e].set(aEx.nextPc());
                rTrap[e].set(aEx.trap());
            });
            when(mdBus.and(mdTag.eq(e)), () -> {
                rDone[e].set(true);
                rA[e].set(mdRegs[0]);
                rB[e].set(mdRegs[1]);
                rVal[e].set(mdVal);
            });
            if (lsq) {
                final Bool ld = ldDone;
                final UInt lt = ldTag, lv = ldVal, lw = ldWord;
                when(ld.and(lt.eq(e)), () -> {
                    rDone[e].set(true);
                    rVal[e].set(lv);
                    rB[e].set(lw);
                });
            }
        }

        // ---------------------------------------------------------------- fetch and dispatch
        UInt fetched;
        UInt fetchAddr = wire("fetch_addr", UInt.w(32));
        Bool fetchEnable = wire("fetch_en", Bool.TYPE);
        // the fetched instruction arrives the cycle after its address (block RAM, or a register)
        if (bram) {
            fetched = imem.readSync(wordIndex(fetchAddr), fetchEnable);
        } else {
            UInt ir = reg("fetch_inst", UInt.w(32), 0);
            UInt live = imem.read(wordIndex(fetchAddr));
            when(fetchEnable, () -> ir.set(live));
            fetched = ir;
        }
        UInt finst = fetched.named("dispatch_inst");
        var fDec = Rv32i.decode(d, finst, zicsr);
        UInt immTarget = fpc.addWrap(fDec.imm());
        Bool predTaken = fDec.jal().or(fDec.branch().and(fDec.imm().bit(31)));
        UInt pred = mux(predTaken, immTarget, fpc.addWrap(4)).named("predicted_next");
        // conditional branches and JALR take a checkpoint (the first free one)
        Bool needsCp = fDec.branch().or(fDec.jalr());
        Bool[] cpFree = new Bool[NCP];
        for (int c = 0; c < NCP; c++) cpFree[c] = cpValid[c].not();
        Bool[] cpSlot = first(cpFree);
        UInt cpFirst = UInt.lit(0, CPB);
        for (int c = 1; c < NCP; c++) cpFirst = mux(cpSlot[c], UInt.lit(c, CPB), cpFirst);
        UInt cpIdx = cpFirst;
        // the new instruction's mask: the branches in flight, less one resolved right this cycle
        Bool[] newMask = new Bool[NCP];
        for (int k = 0; k < NCP; k++) newMask[k] = cpValid[k].and(resolveOk.and(resBit[k]).not());

        Bool robFree = count.ne(ROB);
        Bool[] sFree = new Bool[RS];
        for (int j = 0; j < RS; j++) sFree[j] = sValid[j].not();
        Bool[] slot = first(sFree);
        Bool[] lqSlot = null, sqSlot = null;
        Bool queueOk = Bool.of(true);
        if (lsq) {
            Bool[] lFreeE = new Bool[LQ], qFreeE = new Bool[SQ];
            for (int l = 0; l < LQ; l++) lFreeE[l] = lValid[l].not();
            for (int q = 0; q < SQ; q++) qFreeE[q] = qValid[q].not();
            lqSlot = first(lFreeE);
            sqSlot = first(qFreeE);
            queueOk = fDec.load().not().or(any(lqSlot)).and(fDec.store().not().or(any(sqSlot)));
        }
        Bool dispatch = (lsq ? fv.and(queueOk) : fv).and(halt.not()).and(flush.not()).and(recover.not()).and(robFree).and(any(slot))
                .and(needsCp.not().or(any(cpSlot))).named("dispatch");
        Bool takesCp = dispatch.and(needsCp).named("cp_alloc");

        fetchEnable.set(fv.not().or(dispatch).or(flush).or(recover));
        UInt seqAddr = mux(dispatch.and(predTaken), immTarget, pc);
        fetchAddr.set(mux(flush, flushTarget, mux(recover, recoverTarget, seqAddr)));
        when(fetchEnable, () -> {
            fv.set(halt.not());
            fpc.set(fetchAddr);
            pc.set(fetchAddr.addWrap(4));
        });

        // operands: register file, a finished result in the ROB, or a bus this cycle
        UInt rs1 = fDec.rs1().bits(4, 0), rs2 = fDec.rs2().bits(4, 0);
        Operand op1 = operand(rs1, fDec.usesRs1(), ratBusy, ratTag, rDone, rIsLoad, rVal, busOn, busTag, busVal);
        Operand op2 = operand(rs2, fDec.usesRs2(), ratBusy, ratTag, rDone, rIsLoad, rVal, busOn, busTag, busVal);

        for (int i = 0; i < ROB; i++) {
            final int e = i;
            when(dispatch.and(tail.eq(e)), () -> {
                rValid[e].set(true);
                rDone[e].set(false);
                rTrap[e].set(false);
                rIsLoad[e].set(lateValue(fDec));
                for (int k = 0; k < NCP; k++) rMask[e][k].set(newMask[k]);
                rPc[e].set(fpc);
                rInst[e].set(finst);
                rNext[e].set(fpc.addWrap(4));
            });
            when(commit.and(head.eq(e)), () -> rValid[e].set(false));
        }
        for (int j = 0; j < RS; j++) {
            final int k = j;
            // wake-up from the buses
            for (int b = 0; b < busOn.length; b++) {
                final int bb = b;
                when(sValid[k].and(s1Rdy[k].not()).and(busOn[bb]).and(s1Tag[k].eq(busTag[bb])), () -> {
                    s1Rdy[k].set(true);
                    s1Val[k].set(busVal[bb]);
                });
                when(sValid[k].and(s2Rdy[k].not()).and(busOn[bb]).and(s2Tag[k].eq(busTag[bb])), () -> {
                    s2Rdy[k].set(true);
                    s2Val[k].set(busVal[bb]);
                });
            }
            when(aluPick[k].or(mdPick[k]), () -> sValid[k].set(false));
            when(dispatch.and(slot[k]), () -> {
                sValid[k].set(true);
                sIsMd[k].set(fDec.mulDiv());
                sHasCp[k].set(needsCp);
                sCp[k].set(cpIdx);
                for (int b = 0; b < NCP; b++) sMask[k][b].set(newMask[b]);
                sTag[k].set(tail);
                sPc[k].set(fpc);
                sInst[k].set(finst);
                sPred[k].set(pred);
                s1Rdy[k].set(op1.ready());
                s1Val[k].set(op1.value());
                s1Tag[k].set(op1.tag());
                s2Rdy[k].set(op2.ready());
                s2Val[k].set(op2.value());
                s2Tag[k].set(op2.tag());
            });
        }
        if (lsq) {
            for (int l = 0; l < LQ; l++) {
                final int ll = l;
                final Bool[] ls = lqSlot;
                when(dispatch.and(fDec.load()).and(ls[l]), () -> {
                    lValid[ll].set(true);
                    lAddrOk[ll].set(false);
                    lIssued[ll].set(false);
                    lTag[ll].set(tail);
                    lF3[ll].set(fDec.funct3().bits(2, 0));
                    for (int k = 0; k < NCP; k++) lMask[ll][k].set(newMask[k]);
                });
            }
            for (int q = 0; q < SQ; q++) {
                final int qq = q;
                final Bool[] qs = sqSlot;
                when(dispatch.and(fDec.store()).and(qs[q]), () -> {
                    qValid[qq].set(true);
                    qAddrOk[qq].set(false);
                    qTag[qq].set(tail);
                    qF3[qq].set(fDec.funct3().bits(2, 0));
                    for (int k = 0; k < NCP; k++) qMask[qq][k].set(newMask[k]);
                });
            }
        }
        UInt rd = fDec.rd().bits(4, 0);
        Bool fWrites = zicsr ? fDec.writesRd().or(csr(fDec)) : fDec.writesRd();
        Bool renames = dispatch.and(fWrites).and(rd.ne(0));
        // the checkpoint: the RAT with the branch's own rename (a JALR's link register)
        for (int c = 0; c < NCP; c++) {
            final int cc = c;
            when(takesCp.and(cpSlot[c]), () -> {
                cpValid[cc].set(true);
                for (int k = 0; k < NCP; k++) cpMask[cc][k].set(newMask[k]);
                for (int r = 1; r < 32; r++) {
                    Bool own = renames.and(rd.eq(r));
                    cpBusy[cc][r].set(ratBusy[r].or(own));
                    cpTag[cc][r].set(mux(own, tail, ratTag[r]));
                }
            });
        }
        for (int r = 1; r < 32; r++) {
            final int x = r;
            when(commit.and(cWrites).and(cRd.eq(x)).and(ratTag[x].eq(head)), () -> ratBusy[x].set(false));
            when(renames.and(rd.eq(x)), () -> {
                ratBusy[x].set(true);
                ratTag[x].set(tail);
            });
        }
        when(dispatch, () -> tail.set(tail.addWrap(1)));
        when(commit, () -> head.set(head.addWrap(1)));
        UInt inc = mux(dispatch, UInt.lit(1, TAG + 1), UInt.lit(0, TAG + 1));
        UInt dec = mux(commit, UInt.lit(1, TAG + 1), UInt.lit(0, TAG + 1));
        count.set(count.addWrap(inc).subWrap(dec));

        // ---------------------------------------------------------------- a branch resolved right: free its checkpoint
        for (int k = 0; k < NCP; k++) {
            final int b = k;
            Bool clr = resolveOk.and(resBit[b]);
            when(clr, () -> {
                cpValid[b].set(false);
                for (int i = 0; i < ROB; i++) rMask[i][b].set(false);
                for (int j = 0; j < RS; j++) sMask[j][b].set(false);
                for (int c = 0; c < NCP; c++) cpMask[c][b].set(false);
                for (int l = 0; l < LQ; l++) lMask[l][b].set(false);
                for (int q = 0; q < SQ; q++) qMask[q][b].set(false);
            });
        }

        // ---------------------------------------------------------------- a mispredicted branch: recover in this cycle
        // squash what is younger (its mask bit is set): ROB entries, reservation stations, checkpoints
        for (int i = 0; i < ROB; i++) {
            final int e = i;
            when(recover.and(younger(rMask[e], resBit)), () -> rValid[e].set(false));
        }
        for (int j = 0; j < RS; j++) {
            final int k = j;
            when(recover.and(younger(sMask[k], resBit)), () -> sValid[k].set(false));
        }
        for (int c = 0; c < NCP; c++) {
            final int cc = c;
            when(recover.and(resBit[c].or(younger(cpMask[c], resBit))), () -> cpValid[cc].set(false));
        }
        for (int l = 0; l < LQ; l++) {
            final int ll = l;
            when(recover.and(younger(lMask[l], resBit)), () -> lValid[ll].set(false));
        }
        for (int q = 0; q < SQ; q++) {
            final int qq = q;
            when(recover.and(younger(qMask[q], resBit)), () -> qValid[qq].set(false));
        }
        // the RAT from the branch's checkpoint, keeping only renames by instructions still in flight after this cycle
        UInt headNext = mux(commit, head.addWrap(1), head);
        UInt branchAge = aTag.subWrap(headNext);
        for (int r = 1; r < 32; r++) {
            final int x = r;
            Bool[] busyCol = new Bool[NCP];
            UInt[] tagCol = new UInt[NCP];
            for (int c = 0; c < NCP; c++) {
                busyCol[c] = cpBusy[c][r];
                tagCol[c] = cpTag[c][r];
            }
            Bool cBusy = pickB(aCp, busyCol);
            UInt cTag = pick(aCp, tagCol);
            Bool inFlight = cTag.subWrap(headNext).le(branchAge);
            when(recover, () -> {
                ratBusy[x].set(cBusy.and(inFlight));
                ratTag[x].set(cTag);
            });
        }
        when(recover, () -> {
            tail.set(aTag.addWrap(1));
            count.set(aTag.subWrap(head).zext(TAG + 1).addWrap(1).subWrap(dec));
        });

        // ---------------------------------------------------------------- flush at commit (traps, interrupts, MRET)
        when(flush, () -> {
            for (var v : rValid) v.set(false);
            for (var v : sValid) v.set(false);
            for (var v : cpValid) v.set(false);
            for (var v : lValid) v.set(false);
            for (var v : qValid) v.set(false);
            for (int r = 1; r < 32; r++) ratBusy[r].set(false);
            mdBusy.set(false);
            head.set(0);
            tail.set(0);
            count.set(0);
        });
    }

    private record Operand(Bool ready, UInt value, UInt tag) {}

    /** Whether an entry with this branch mask is younger than the branch {@code branch} (one-hot). */
    private static Bool younger(Bool[] mask, Bool[] branch) {
        Bool out = Bool.of(false);
        for (int k = 0; k < mask.length; k++) out = out.or(mask[k].and(branch[k]));
        return out;
    }

    private static Bool[] column(Bool[][] m, int k) {
        Bool[] out = new Bool[m.length];
        for (int j = 0; j < m.length; j++) out[j] = m[j][k];
        return out;
    }

    /** A CSR instruction (with Zicsr): it reads its CSR, and writes rd, when it commits. */
    private static Bool csr(Rv32i.Decoded<UInt, Bool> dec) {
        return dec.system().and(dec.funct3().ne(0)).and(dec.funct3().ne(4));
    }

    /** The instruction's rd value appears only at commit: CSR instructions, and loads without the LSQ. */
    private Bool lateValue(Rv32i.Decoded<UInt, Bool> dec) {
        if (!lsq) return late(dec);
        return zicsr ? csr(dec) : Bool.of(false);
    }

    /** The bytes of its word an access of this size (funct3) at this address touches (4 bits). */
    private static UInt bytes(UInt f3, UInt addr) {
        UInt size = f3.bits(1, 0);
        UInt b = mux(size.eq(0), UInt.lit(1, 4), mux(size.eq(1), UInt.lit(3, 4), UInt.lit(15, 4)));
        return b.dshl(addr.bits(1, 0));
    }

    /**
     * How the load/store queue reaches the data memory: a read for the commit (a store merging into its word) and a
     * read for a speculative load, each giving its word the cycle after the request. Writes stay at commit
     * ({@link RvCore#dmemWrite}). {@link SinglePort} shares the one read port, the commit first; a true-dual-port memory
     * would give the loads port A and the commit port B, and grant every load.
     */
    interface DataPort {
        record Words(UInt commit, UInt load, Bool loadGranted) {}

        Words read(OooSpecCore core, UInt commitIdx, Bool commitEn, UInt loadIdx, Bool loadEn);
    }

    /** One read port: a load is granted only in cycles the commit does not read. */
    static final class SinglePort implements DataPort {
        @Override
        public Words read(OooSpecCore core, UInt commitIdx, Bool commitEn, UInt loadIdx, Bool loadEn) {
            UInt idx = mux(commitEn, commitIdx, loadIdx);
            Bool en = commitEn.or(loadEn);
            UInt w;
            if (core.bram) {
                w = core.dmemRead(idx, en, true);
            } else {
                UInt r = core.reg("mem_word", UInt.w(32), 0);
                UInt live = core.dmemRead(idx, en, false);
                when(en, () -> r.set(live));
                w = r;
            }
            return new Words(w, w, commitEn.not());
        }
    }

    /**
     * The true dual-port data memory ({@link RvCore#dmemPort}): port A (shared with the debug host, which uses it only
     * while the core is held) reads for speculative loads and never writes; port B reads the word a byte or halfword
     * store merges into, and writes the committed store. A load is always granted. Port B's write enable and data come
     * from the commit, later in the constructor ({@link #write}). Read-first: a load reading the word a store writes
     * in the same cycle would see the old word, but that store is then still in the store queue, so such a load
     * forwards from it or waits instead of reading.
     */
    static final class TdpPort implements DataPort {
        private Bool we;
        private UInt wdata;

        @Override
        public Words read(OooSpecCore core, UInt commitIdx, Bool commitEn, UInt loadIdx, Bool loadEn) {
            UInt a = core.dmemPort(0, loadIdx, loadEn, Bool.of(false), UInt.lit(0, 32));
            we = core.wire("dmem_b_we", Bool.TYPE);
            wdata = core.wire("dmem_b_wdata", UInt.w(32));
            UInt b = core.dmemPort(1, commitIdx, commitEn.or(we), we, wdata);
            return new Words(b, a, Bool.of(true));
        }

        /** The committed store on port B (at the commit's address). */
        void write(UInt data, Bool en) {
            we.set(en);
            wdata.set(data);
        }
    }

    /** The instruction's rd value appears only at commit: loads, and CSR instructions. */
    private Bool late(Rv32i.Decoded<UInt, Bool> dec) {
        return zicsr ? dec.load().or(csr(dec)) : dec.load();
    }

    private Operand operand(UInt idx, Bool uses, Bool[] ratBusy, UInt[] ratTag, Bool[] rDone, Bool[] rIsLoad,
                            UInt[] rVal, Bool[] busOn, UInt[] busTag, UInt[] busVal) {
        Bool busy = pickB(idx, ratBusy).and(uses).and(idx.ne(0));
        UInt tag = pick(idx, ratTag);
        Bool inRob = pickB(tag, rDone).and(pickB(tag, rIsLoad).not());
        UInt value = mux(inRob, pick(tag, rVal), UInt.lit(0, 32));
        Bool onBus = Bool.of(false);
        for (int b = busOn.length - 1; b >= 0; b--) {
            Bool hit = busOn[b].and(busTag[b].eq(tag));
            value = mux(hit, busVal[b], value);
            onBus = onBus.or(hit);
        }
        UInt fileValue = readReg(idx);
        return new Operand(busy.not().or(inRob).or(onBus), mux(busy, value, fileValue), tag);
    }

    /** One-hot choice of the oldest candidate (age = distance of its ROB tag from the head). */
    private static Bool[] oldest(Bool[] cand, UInt[] tag, UInt head) {
        int n = cand.length;
        UInt[] age = new UInt[n];
        for (int j = 0; j < n; j++) age[j] = tag[j].subWrap(head);
        Bool[] out = new Bool[n];
        for (int j = 0; j < n; j++) {
            Bool best = cand[j];
            for (int k = 0; k < n; k++) {
                if (k == j) continue;
                Bool older = k < j ? age[k].le(age[j]) : age[k].lt(age[j]);
                best = best.and(cand[k].and(older).not());
            }
            out[j] = best;
        }
        return out;
    }

    private static Bool[] first(Bool[] cand) {
        Bool[] out = new Bool[cand.length];
        Bool taken = Bool.of(false);
        for (int j = 0; j < cand.length; j++) {
            out[j] = cand[j].and(taken.not());
            taken = taken.or(cand[j]);
        }
        return out;
    }

    private static Bool[] and(Bool[] a, Bool b) {
        Bool[] out = new Bool[a.length];
        for (int j = 0; j < a.length; j++) out[j] = a[j].and(b);
        return out;
    }

    private static Bool any(Bool[] a) {
        Bool out = Bool.of(false);
        for (var x : a) out = out.or(x);
        return out;
    }

    private static UInt pickOneHot(Bool[] sel, UInt[] v) {
        UInt out = v[0];
        for (int j = 1; j < v.length; j++) out = mux(sel[j], v[j], out);
        return out;
    }

    private static Bool pickOneHotB(Bool[] sel, Bool[] v) {
        Bool out = v[0];
        for (int j = 1; j < v.length; j++) out = mux(sel[j], v[j], out);
        return out;
    }

    private static UInt pick(UInt idx, UInt[] v) {
        UInt out = v[0];
        for (int j = 1; j < v.length; j++) out = mux(idx.eq(j), v[j], out);
        return out;
    }

    private static Bool pickB(UInt idx, Bool[] v) {
        Bool out = v[0];
        for (int j = 1; j < v.length; j++) out = mux(idx.eq(j), v[j], out);
        return out;
    }
}
