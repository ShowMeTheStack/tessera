package org.eninehq.tessera.riscv;

import static org.eninehq.tessera.core.Hw.mux;
import static org.eninehq.tessera.core.Hw.when;

import org.eninehq.tessera.core.Bool;
import org.eninehq.tessera.core.UInt;

/**
 * R3: a single-cycle RV32IM core built directly from the shared {@link Rv32i} semantics.
 *
 * <p>With asynchronous (LUT) memories every instruction takes one cycle. With block RAM
 * ({@link Config#bram}), whose reads take a clock edge, it becomes a multi-cycle core: fetch,
 * then execute, plus a memory cycle for loads and stores (CPI 2, 3 for memory instructions).
 * With {@link Config#seqDiv} a divide waits 16 cycles for the sequential divider.
 */
public class SingleCycleCore extends RvCore {
    public final boolean bram;

    public SingleCycleCore(int memWords, int[] image) {
        this(memWords, image, false);
    }

    /** With {@code zicsr}: machine-mode CSRs, traps to mtvec, MRET and the {@code irq} interrupt input. */
    public SingleCycleCore(int memWords, int[] image, boolean zicsr) {
        this(memWords, image, new Config(zicsr, false, false));
    }

    public SingleCycleCore(int memWords, int[] image, Config config) {
        super(memWords, image, config);
        this.bram = config.bram();
        var d = HwDom.current();
        UInt pc = reg("pc", UInt.w(32), 0);
        Bool halt = reg("halt", Bool.TYPE, 0);
        // the datapath is combinational: without this, the instruction at the reset pc would write
        // registers and memory for as long as reset is held (a host loading programs holds it long)
        Bool run = halt.not().and(reset().not());

        // with block RAM: 0 fetch, 1 execute, 2 memory
        UInt phase = bram ? reg("phase", UInt.w(2), 0) : null;
        Bool fetching = bram ? phase.eq(0) : Bool.of(true);
        Bool executing = bram ? phase.eq(1) : Bool.of(true);
        Bool inMemory = bram ? phase.eq(2) : Bool.of(false);

        UInt inst = (bram ? imem.readSync(wordIndex(pc), fetching) : imem.read(wordIndex(pc))).named("inst");
        var dec = Rv32i.decode(d, inst, zicsr);
        UInt rs1v = readReg(dec.rs1().bits(4, 0)).named("rs1v");
        UInt rs2v = readReg(dec.rs2().bits(4, 0)).named("rs2v");
        var ex = Rv32i.execute(d, dec, pc, rs1v, rs2v);
        Bool memOp = dec.load().or(dec.store());
        UInt idx = wordIndex(ex.memAddr());
        UInt word = dmemRead(idx, executing.and(memOp), bram);

        UInt md;
        Bool divWait = Bool.of(false);
        if (config.seqDiv()) {
            Bool isDiv = dec.mulDiv().and(dec.funct3().bit(2)).named("is_div");
            Bool want = run.and(executing).and(isDiv);
            // the instruction finishes (and the divider is released) once the result is there
            Bool advancing = wire("div_advance", Bool.TYPE);
            var div = sequentialDivide(want, advancing, Rv32i.mulDivStart(d, dec, rs1v, rs2v, 0));
            md = div.value();
            divWait = div.stall();
            advancing.set(want.and(div.stall().not()));
        } else {
            md = Rv32i.mulDivFinish(d, Rv32i.mulDivStart(d, dec, rs1v, rs2v));
        }
        // the cycle the instruction completes: writes, CSR changes, the next pc
        Bool complete = (bram ? executing.and(memOp.not()).and(divWait.not()).or(inMemory) : divWait.not())
                .and(run).named("complete");
        if (bram) {
            when(run, () -> when(fetching, () -> phase.set(1))
                    .elseWhen(executing.and(memOp), () -> phase.set(2))
                    .elseWhen(complete, () -> phase.set(0)));
        }
        UInt loaded = Rv32i.loadValue(d, dec.funct3(), ex.memAddr(), word);
        UInt merged = Rv32i.storeMerge(d, dec.funct3(), ex.memAddr(), word, rs2v);

        if (!zicsr) {
            UInt rdValue = mux(dec.load(), loaded, mux(dec.mulDiv(), md, ex.result())).named("rd_value");
            when(complete, () -> {
                when(ex.writesRd(), () -> regs.write(dec.rd().bits(4, 0), rdValue));
                when(ex.trap(), () -> halt.set(true))
                        .otherwise(() -> pc.set(ex.nextPc()));
            });
            dmemWrite(idx, merged, complete.and(dec.store()).and(ex.trap().not()));
            halted.set(halt);
            retire(new RetireInfo(complete, inst, pc, dec, ex, rs1v, rs2v, rdValue, word));
            return;
        }
        var csrs = csrRegisters();
        var eff = Zicsr.step(d, dec, ex, inst, pc, rs1v, csrs, irq, complete);
        UInt rdValue = mux(eff.isCsr(), eff.csrValue(), mux(dec.load(), loaded, mux(dec.mulDiv(), md, ex.result())))
                .named("rd_value");
        // rvfi_intr marks the first instruction of a handler entered by an interrupt
        Bool intrNext = reg("intr_next", Bool.TYPE, 0);
        UInt nextPc = mux(eff.redirect(), eff.target(), ex.nextPc());
        when(complete, () -> {
            when(eff.writesRd(), () -> regs.write(dec.rd().bits(4, 0), rdValue));
            when(eff.halt(), () -> halt.set(true))
                    .otherwise(() -> pc.set(nextPc));
            intrNext.set(eff.interrupt());
        });
        dmemWrite(idx, merged, complete.and(dec.store()).and(eff.commits()));
        updateCsrs(csrs, eff.next(), complete);
        halted.set(halt);
        retire(new RetireInfo(complete.and(eff.interrupt().not()), inst, pc, dec, ex, rs1v, rs2v, rdValue, word,
                eff.exception(), eff.halt(), intrNext, nextPc, eff.writesRd()));
        retireCsrs(complete.and(eff.interrupt().not()), inst, eff);
    }
}
