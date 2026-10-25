package org.eninehq.tessera.riscv;

import static org.eninehq.tessera.core.Hw.mux;
import static org.eninehq.tessera.core.Hw.when;

import org.eninehq.tessera.core.Bool;
import org.eninehq.tessera.core.TesseraException;
import org.eninehq.tessera.core.UInt;

/**
 * An out-of-order RV32IM core (Tomasulo with a reorder buffer), built from the same
 * {@link Rv32i} semantics as the in-order cores.
 *
 * <ul>
 *   <li><b>Fetch</b> one instruction per cycle; static prediction at dispatch (JAL and backward
 *       branches taken, everything else falls through), so the fetch redirect costs no bubble.</li>
 *   <li><b>Dispatch</b> in order into a reorder buffer (16 entries by default) and a
 *       reservation station (8), renaming through a register alias table; ready
 *       operands come from the register file, finished-but-uncommitted results, or this cycle's
 *       result buses.</li>
 *   <li><b>Issue</b> out of order, oldest ready first: one ALU operation per cycle (results on
 *       the bus the same cycle), and one multiply/divide into a sequential unit (multiply 2
 *       cycles, divide 18, 2 bits per cycle) while ALU work continues around it.</li>
 *   <li><b>Commit</b> in order, one per cycle. Loads and stores access memory when they reach
 *       the head (no speculation on memory); a mispredicted control transfer flushes everything
 *       younger at commit and refetches. Without Zicsr, traps halt the core, as in the other cores.</li>
 *   <li><b>Zicsr</b> (optional) happens entirely at the head: CSR instructions read and write
 *       their CSR when they commit (their rd value, like a load's, appears only on the commit
 *       bus), and exceptions, MRET and interrupts are taken there, flushing everything younger,
 *       so traps are precise by construction.</li>
 * </ul>
 * RVFI reports instructions as they commit, so riscv-formal and the ISS comparison apply unchanged.
 */
public class OooCore extends RvCore {
    public static final int ROB_DEFAULT = 16, RS_DEFAULT = 8;
    private final int ROB, RS, TAG;
    public final boolean bram;

    public OooCore(int memWords, int[] image) {
        this(memWords, image, Config.DEFAULT);
    }

    public OooCore(int memWords, int[] image, Config config) {
        this(memWords, image, config, ROB_DEFAULT, RS_DEFAULT);
    }

    /** {@code robEntries} (a power of two) and {@code rsEntries} size the window. */
    public OooCore(int memWords, int[] image, Config config, int robEntries, int rsEntries) {
        super(memWords, image, config);
        if (Integer.bitCount(robEntries) != 1 || robEntries < 2) throw TesseraException.here("the ROB size must be a power of two");
        ROB = robEntries;
        RS = rsEntries;
        TAG = Integer.numberOfTrailingZeros(robEntries);
        this.bram = config.bram();
        var d = HwDom.current();
        UInt zero32 = UInt.lit(0, 32);

        // ---------------------------------------------------------------- state
        UInt pc = reg("pc", UInt.w(32), 0);
        Bool fv = reg("fetch_valid", Bool.TYPE, 0);
        UInt fpc = reg("fetch_pc", UInt.w(32), 0);
        Bool halt = reg("halt", Bool.TYPE, 0);

        Bool[] rValid = new Bool[ROB], rDone = new Bool[ROB], rMis = new Bool[ROB], rTrap = new Bool[ROB];
        // loads and CSR instructions: their value is known only at commit
        Bool[] rIsLoad = new Bool[ROB];
        UInt[] rPc = new UInt[ROB], rInst = new UInt[ROB], rA = new UInt[ROB], rB = new UInt[ROB], rVal = new UInt[ROB],
                rNext = new UInt[ROB];
        for (int i = 0; i < ROB; i++) {
            rValid[i] = reg("rob" + i + "_valid", Bool.TYPE, 0);
            rDone[i] = reg("rob" + i + "_done", Bool.TYPE, 0);
            rMis[i] = reg("rob" + i + "_mispredict", Bool.TYPE, 0);
            rTrap[i] = reg("rob" + i + "_trap", Bool.TYPE, 0);
            rIsLoad[i] = reg("rob" + i + "_load", Bool.TYPE, 0);
            rPc[i] = reg("rob" + i + "_pc", UInt.w(32), 0);
            rInst[i] = reg("rob" + i + "_inst", UInt.w(32), 0);
            rA[i] = reg("rob" + i + "_rs1v", UInt.w(32), 0);
            rB[i] = reg("rob" + i + "_rs2v", UInt.w(32), 0);
            rVal[i] = reg("rob" + i + "_value", UInt.w(32), 0);
            rNext[i] = reg("rob" + i + "_next_pc", UInt.w(32), 0);
        }
        UInt head = reg("rob_head", UInt.w(TAG), 0), tail = reg("rob_tail", UInt.w(TAG), 0);
        UInt count = reg("rob_count", UInt.w(TAG + 1), 0);

        Bool[] sValid = new Bool[RS], sIsMd = new Bool[RS], s1Rdy = new Bool[RS], s2Rdy = new Bool[RS];
        UInt[] sTag = new UInt[RS], sPc = new UInt[RS], sInst = new UInt[RS], sPred = new UInt[RS],
                s1Val = new UInt[RS], s2Val = new UInt[RS], s1Tag = new UInt[RS], s2Tag = new UInt[RS];
        for (int j = 0; j < RS; j++) {
            sValid[j] = reg("rs" + j + "_valid", Bool.TYPE, 0);
            sIsMd[j] = reg("rs" + j + "_muldiv", Bool.TYPE, 0);
            s1Rdy[j] = reg("rs" + j + "_a_ready", Bool.TYPE, 0);
            s2Rdy[j] = reg("rs" + j + "_b_ready", Bool.TYPE, 0);
            sTag[j] = reg("rs" + j + "_tag", UInt.w(TAG), 0);
            sPc[j] = reg("rs" + j + "_pc", UInt.w(32), 0);
            sInst[j] = reg("rs" + j + "_inst", UInt.w(32), 0);
            sPred[j] = reg("rs" + j + "_pred", UInt.w(32), 0);
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

        // ---------------------------------------------------------------- commit (head of the ROB)
        Bool hValid = pickB(head, rValid), hDone = pickB(head, rDone), hTrap = pickB(head, rTrap);
        UInt hInst = pick(head, rInst).named("commit_inst"), hPc = pick(head, rPc).named("commit_pc");
        UInt hA = pick(head, rA), hB = pick(head, rB), hNext = pick(head, rNext);
        var hDec = Rv32i.decode(d, hInst, zicsr);
        var hEx = Rv32i.execute(d, hDec, hPc, hA, hB);
        Bool hIsMem = hDec.load().or(hDec.store()).and(hTrap.not());
        Bool ready = hValid.and(hDone).and(halt.not()).named("commit_ready");
        // memory instructions take two cycles at the head: read the word, then commit
        Bool memPhase = reg("mem_phase", Bool.TYPE, 0);
        Bool memStart = ready.and(hIsMem).and(memPhase.not());
        UInt memIdx = wordIndex(hEx.memAddr());
        UInt word;
        if (bram) {
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
        UInt loadOrValue = mux(hDec.load(), Rv32i.loadValue(d, hDec.funct3(), hEx.memAddr(), word), pick(head, rVal));
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
            Bool mispredict = pickB(head, rMis).and(eff.commits());
            flush = turn.and(eff.redirect().or(mispredict)).named("flush");
            flushTarget = mux(eff.redirect(), eff.target(), hNext);
            retire(new RetireInfo(commit, hInst, hPc, hDec, hEx, hA, hB, cVal, word, eff.exception(), eff.halt(), intrNext,
                    flushTarget, cWrites));
            retireCsrs(commit, hInst, eff);
        } else {
            commit = turn.named("commit");
            cVal = loadOrValue.named("commit_value");
            cWrites = hDec.writesRd().and(hTrap.not());
            storeOk = hTrap.not();
            when(commit.and(hTrap), () -> halt.set(true));
            flush = commit.and(pickB(head, rMis)).and(hTrap.not()).named("flush");
            flushTarget = hNext;
            retire(new RetireInfo(commit, hInst, hPc, hDec, hEx, hA, hB, cVal, word, hTrap, hTrap, Bool.of(false), hNext, cWrites));
        }
        when(commit.and(cWrites), () -> regs.write(cRd, cVal));
        dmemWrite(memIdx, Rv32i.storeMerge(d, hDec.funct3(), hEx.memAddr(), word, hB), commit.and(hDec.store()).and(storeOk));
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
        Bool aluBus = aluIssue.and(late(aDec).not()).named("alu_bus");
        UInt aluVal = aEx.result();

        // multiply/divide unit
        UInt mInst = pickOneHot(mdPick, sInst), mA = pickOneHot(mdPick, s1Val), mB = pickOneHot(mdPick, s2Val);
        var mHalf = Rv32i.mulDivStart(d, Rv32i.decode(d, mInst, zicsr), mA, mB, 0);
        UInt mdTag = reg("md_tag", UInt.w(TAG), 0);
        UInt mdCount = reg("md_count", UInt.w(6), 0);
        Bool mdIsDiv = reg("md_div", Bool.TYPE, 0);
        UInt[] mdRegs = new UInt[11];
        String[] mdNames = {"a", "b", "funct3", "p00", "p01", "p10", "p11", "rem", "quo", "dividend", "divisor"};
        UInt[] mdNow = {mHalf.a(), mHalf.b(), mHalf.funct3(), mHalf.p00(), mHalf.p01(), mHalf.p10(), mHalf.p11(),
                mHalf.rem(), mHalf.quo(), mHalf.dividend(), mHalf.divisor()};
        for (int k = 0; k < mdRegs.length; k++) mdRegs[k] = reg("md_" + mdNames[k], UInt.w(mdNow[k].width()), 0);
        Bool mdNegQ = reg("md_neg_q", Bool.TYPE, 0), mdNegR = reg("md_neg_r", Bool.TYPE, 0), mdByZero = reg("md_by_zero", Bool.TYPE, 0);
        when(mdIssue, () -> {
            mdBusy.set(true);
            mdTag.set(pickOneHot(mdPick, sTag));
            mdCount.set(0);
            mdIsDiv.set(mInst.bit(14));
            for (int k = 0; k < mdRegs.length; k++) mdRegs[k].set(mdNow[k]);
            mdNegQ.set(mHalf.negQ());
            mdNegR.set(mHalf.negR());
            mdByZero.set(mHalf.byZero());
        });
        when(mdBusy.and(mdIsDiv).and(mdCount.ne(32)), () -> {
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
        when(mdBus, () -> mdBusy.set(false));

        // the three buses: ALU, multiply/divide, commit (for loads, whose value appears only there)
        UInt[] busTag = {aTag, mdTag, head};
        UInt[] busVal = {aluVal, mdVal, cVal};
        Bool[] busOn = {aluBus, mdBus, commit.and(cWrites)};

        // results into the ROB
        for (int i = 0; i < ROB; i++) {
            final int e = i;
            when(aluIssue.and(aTag.eq(e)), () -> {
                rDone[e].set(true);
                rA[e].set(aA);
                rB[e].set(aB);
                rVal[e].set(aluVal);
                rNext[e].set(aEx.nextPc());
                rTrap[e].set(aEx.trap());
                rMis[e].set(aEx.nextPc().ne(aPred));
            });
            when(mdBus.and(mdTag.eq(e)), () -> {
                rDone[e].set(true);
                rA[e].set(mdRegs[0]);
                rB[e].set(mdRegs[1]);
                rVal[e].set(mdVal);
            });
        }

        // ---------------------------------------------------------------- fetch and dispatch
        Bool robFree = count.ne(ROB);
        Bool[] sFree = new Bool[RS];
        for (int j = 0; j < RS; j++) sFree[j] = sValid[j].not();
        Bool[] slot = first(sFree);
        Bool dispatch = fv.and(halt.not()).and(flush.not()).and(robFree).and(any(slot)).named("dispatch");
        // the fetched instruction arrives the cycle after its address (block RAM, or a register)
        UInt fetchAddr = wire("fetch_addr", UInt.w(32));
        Bool fetchEnable = wire("fetch_en", Bool.TYPE);
        UInt fetched;
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

        fetchEnable.set(fv.not().or(dispatch).or(flush));
        UInt seqAddr = mux(dispatch.and(predTaken), immTarget, pc);
        fetchAddr.set(mux(flush, flushTarget, seqAddr));
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
                rMis[e].set(false);
                rTrap[e].set(false);
                rIsLoad[e].set(late(fDec));
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
        UInt rd = fDec.rd().bits(4, 0);
        Bool fWrites = zicsr ? fDec.writesRd().or(csr(fDec)) : fDec.writesRd();
        Bool renames = dispatch.and(fWrites).and(rd.ne(0));
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

        // ---------------------------------------------------------------- flush on a mispredicted commit
        when(flush, () -> {
            for (var v : rValid) v.set(false);
            for (var v : sValid) v.set(false);
            for (int r = 1; r < 32; r++) ratBusy[r].set(false);
            mdBusy.set(false);
            head.set(0);
            tail.set(0);
            count.set(0);
        });
    }

    private record Operand(Bool ready, UInt value, UInt tag) {}

    /** A CSR instruction (with Zicsr): it reads its CSR, and writes rd, when it commits. */
    private static Bool csr(Rv32i.Decoded<UInt, Bool> dec) {
        return dec.system().and(dec.funct3().ne(0)).and(dec.funct3().ne(4));
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
                // ties cannot happen (distinct tags); break them by index anyway
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
