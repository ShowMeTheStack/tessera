package org.eninehq.tessera.riscv;

import static org.eninehq.tessera.core.Hw.mux;
import static org.eninehq.tessera.core.Hw.when;

import org.eninehq.tessera.core.Bool;
import org.eninehq.tessera.core.UInt;
import org.eninehq.tessera.pipeline.Late;
import org.eninehq.tessera.pipeline.Pipeline;

/**
 * R4: the classic five-stage RV32IM pipeline (IF ID EX MEM WB), written with Level-1
 * pipeline markers. Pipeline registers are inserted automatically; everything else is written
 * by hand with late values: forwarding (EX, MEM, WB to ID), the load-use and multiply/divide
 * stalls, the branch flush, and with block RAM the store-to-load bypass. Branches resolve in
 * EX (predict not taken). The multiply/divide unit starts in EX and finishes in MEM, so its
 * result is forwarded from WB; Zicsr instructions, traps and interrupts happen in EX.
 */
public class FiveStageCore extends RvCore {

    /** What a later stage tells ID about the register it will write, and whether the value is there yet. */
    private record Producer(Late<Bool> writes, Late<UInt> rd, Late<UInt> value, Late<Bool> notReady) {}

    /** Deliberate bugs, to check that verification catches them (like picorv32's TESTBUG defines). */
    public enum TestBug { NONE, NO_WB_FORWARD, NO_LOAD_USE_STALL }

    public final boolean bram;

    public FiveStageCore(int memWords, int[] image) {
        this(memWords, image, TestBug.NONE);
    }

    public FiveStageCore(int memWords, int[] image, TestBug bug) {
        this(memWords, image, Config.DEFAULT, bug);
    }

    public FiveStageCore(int memWords, int[] image, Config config) {
        this(memWords, image, config, TestBug.NONE);
    }

    public FiveStageCore(int memWords, int[] image, Config config, TestBug bug) {
        super(memWords, image, config);
        this.bram = config.bram();
        var d = HwDom.current();
        UInt pc = reg("pc", UInt.w(32), 0);
        Bool stopFetch = reg("stop_fetch", Bool.TYPE, 0);
        Bool halt = reg("halt", Bool.TYPE, 0);
        Bool handlerNext = zicsr ? reg("handler_next", Bool.TYPE, 0) : null;

        var p = Pipeline.begin("core", stopFetch.not());

        // ---------------------------------------------------------------- IF
        UInt fpc = p.input(pc);
        // the first instruction fetched after an interrupt starts the handler (rvfi_intr)
        Bool handlerStart = zicsr ? p.input(handlerNext) : Bool.of(false);
        if (zicsr) when(p.valid().and(p.advancing()), () -> handlerNext.set(false));
        Late<Bool> redirect = p.late(Bool.TYPE);
        Late<UInt> target = p.late(UInt.w(32));
        UInt inst = bram ? p.registered(imem.readSync(wordIndex(pc), p.advancing())) : imem.read(wordIndex(pc));
        pc.set(mux(redirect.get(), target.get(), mux(p.held(0), pc, pc.addWrap(4))));

        // ---------------------------------------------------------------- ID
        p.stage("ID");
        var dec = Rv32i.decode(d, inst, zicsr);
        UInt rs1 = dec.rs1().bits(4, 0), rs2 = dec.rs2().bits(4, 0), rd = dec.rd().bits(4, 0);
        var ex = producer(p);
        var mem = producer(p);
        var wb = producer(p);
        Producer wbFwd = bug == TestBug.NO_WB_FORWARD ? null : wb;
        UInt a = forward(rs1, readReg(rs1), ex, mem, wbFwd).named("id_rs1v");
        UInt b = forward(rs2, readReg(rs2), ex, mem, wbFwd).named("id_rs2v");
        // a load's value appears in MEM, a multiply/divide result in WB: wait for them
        Bool waitEx = hazard(ex, dec, rs1, rs2).and(ex.notReady().get());
        Bool waitMem = hazard(mem, dec, rs1, rs2).and(mem.notReady().get());
        if (bug != TestBug.NO_LOAD_USE_STALL) p.stall(waitEx.or(waitMem).and(p.valid()));

        // ---------------------------------------------------------------- EX
        p.stage("EX");
        var exr = Rv32i.execute(d, dec, fpc, a, b);
        var half = Rv32i.mulDivStart(d, dec, a, b, config.seqDiv() ? 0 : 16);
        Bool exValid = p.valid();
        Bool fire = exValid.and(p.advancing());
        Bool commits, writesOk, trap, haltHere, interrupted, taken;
        UInt redirectTo, nextPc, aluOrCsr;
        Zicsr.Effect<UInt, Bool> eff = null;
        if (zicsr) {
            var csrs = csrRegisters();
            eff = Zicsr.step(d, dec, exr, inst, fpc, a, csrs, irq, fire);
            updateCsrs(csrs, eff.next(), fire);
            when(fire.and(eff.interrupt()), () -> handlerNext.set(true));
            commits = eff.commits();
            writesOk = eff.writesRd();
            trap = eff.exception();
            haltHere = eff.halt();
            interrupted = eff.interrupt();
            taken = eff.redirect().or(exr.redirect().and(eff.commits()));
            redirectTo = mux(eff.redirect(), eff.target(), exr.target());
            nextPc = mux(eff.redirect(), eff.target(), exr.nextPc());
            aluOrCsr = mux(eff.isCsr(), eff.csrValue(), exr.result());
        } else {
            commits = exr.trap().not().and(exValid);
            writesOk = exr.writesRd().and(exValid);
            trap = exr.trap();
            haltHere = exr.trap().and(exValid);
            interrupted = Bool.of(false);
            taken = exr.redirect().and(exValid).and(exr.trap().not());
            redirectTo = exr.target();
            nextPc = exr.nextPc();
            aluOrCsr = exr.result();
        }
        redirect.set(taken);
        target.set(redirectTo);
        p.flush(taken.or(haltHere));
        when(haltHere, () -> stopFetch.set(true));
        Bool exNotReady = dec.load().or(dec.mulDiv());
        ex.writes().set(writesOk.and(exValid));
        ex.rd().set(rd);
        ex.value().set(aluOrCsr);
        ex.notReady().set(exNotReady);
        UInt idx = wordIndex(exr.memAddr());
        Bool memOp = dec.load().or(dec.store());
        UInt syncWord = bram ? p.registered(dmemRead(idx, p.advancing().and(memOp), true)) : null;

        // ---------------------------------------------------------------- MEM
        p.stage("MEM");
        // block RAM: a store written last cycle is not yet visible to the read made in EX
        UInt lastIdx = bram ? reg("last_store_idx", UInt.w(addrBits), 0) : null;
        UInt lastWord = bram ? reg("last_store_word", UInt.w(32), 0) : null;
        Bool lastValid = bram ? reg("last_store_valid", Bool.TYPE, 0) : null;
        UInt word = bram ? mux(lastValid.and(lastIdx.eq(idx)), lastWord, syncWord) : dmemRead(idx, Bool.of(true), false);
        UInt md;
        if (config.seqDiv()) {
            Bool isDiv = dec.mulDiv().and(dec.funct3().bit(2)).named("is_div");
            var div = sequentialDivide(p.valid().and(isDiv), p.advancing(), half);
            p.stall(div.stall());
            md = div.value();
        } else {
            md = Rv32i.mulDivFinish(d, half);
        }
        UInt rdValue = mux(dec.load(), Rv32i.loadValue(d, dec.funct3(), exr.memAddr(), word), mux(dec.mulDiv(), md, aluOrCsr));
        UInt merged = Rv32i.storeMerge(d, dec.funct3(), exr.memAddr(), word, b);
        Bool storing = dec.store().and(commits).and(p.valid()).and(p.advancing());
        dmemWrite(idx, merged, storing);
        if (bram) {
            lastValid.set(storing);
            when(storing, () -> {
                lastIdx.set(idx);
                lastWord.set(merged);
            });
        }
        mem.writes().set(writesOk.and(p.valid()));
        mem.rd().set(rd);
        mem.value().set(rdValue);
        mem.notReady().set(dec.mulDiv());

        // ---------------------------------------------------------------- WB
        p.stage("WB");
        Bool wbValid = p.valid();
        when(writesOk.and(wbValid), () -> regs.write(rd, rdValue));
        when(haltHere.and(wbValid), () -> halt.set(true));
        wb.writes().set(writesOk.and(wbValid));
        wb.rd().set(rd);
        wb.value().set(rdValue);
        wb.notReady().set(Bool.of(false));
        Bool retires = wbValid.and(halt.not()).and(interrupted.not());
        retire(new RetireInfo(retires, inst, fpc, dec, exr, a, b, rdValue, word, trap, haltHere, handlerStart, nextPc, writesOk));
        if (zicsr) retireCsrs(retires, inst, eff);
        p.end();

        halted.set(halt);
    }

    private static Producer producer(Pipeline p) {
        return new Producer(p.late(Bool.TYPE), p.late(UInt.w(5)), p.late(UInt.w(32)), p.late(Bool.TYPE));
    }

    /** The producer writes a register this instruction reads. */
    private static Bool hazard(Producer prod, Rv32i.Decoded<UInt, Bool> dec, UInt rs1, UInt rs2) {
        Bool w = prod.writes().get().and(prod.rd().get().ne(0));
        return w.and(dec.usesRs1().and(prod.rd().get().eq(rs1)).or(dec.usesRs2().and(prod.rd().get().eq(rs2))));
    }

    /** The newest in-flight value of register {@code r}, youngest producer first. */
    private static UInt forward(UInt r, UInt fromRegFile, Producer ex, Producer mem, Producer wb) {
        UInt v = fromRegFile;
        for (var prod : new Producer[]{wb, mem, ex}) {
            if (prod == null) continue;
            Bool hit = prod.writes().get().and(prod.rd().get().eq(r));
            v = mux(hit, prod.value().get(), v);
        }
        return v;
    }
}
