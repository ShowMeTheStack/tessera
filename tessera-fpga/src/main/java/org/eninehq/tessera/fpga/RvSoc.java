package org.eninehq.tessera.fpga;

import static org.eninehq.tessera.core.Hw.mux;
import static org.eninehq.tessera.core.Hw.when;

import java.util.function.Supplier;
import org.eninehq.tessera.core.Bool;
import org.eninehq.tessera.core.ClockDomain;
import org.eninehq.tessera.core.Hw;
import org.eninehq.tessera.core.Module;
import org.eninehq.tessera.core.UInt;
import org.eninehq.tessera.ir.ClockDomainDef.ResetKind;
import org.eninehq.tessera.riscv.RvCore;
import org.eninehq.tessera.stdlib.AxiLite;

/**
 * A RISC-V core behind an AXI-Lite slave, for a Zynq: the ARM loads programs into the core's
 * memories, starts it, and reads back registers, memory and counters.
 *
 * <pre>
 * 0x00000 CTRL     bit0 run (the core is held in reset while 0), bit1 irq
 * 0x00004 STATUS   bit0 halted
 * 0x00008 CYCLES   clock cycles since run, until halted
 * 0x0000C RETIRED  retired instructions
 * 0x00010 ID       core identification
 * 0x00014 LAST_PC  pc of the last retired instruction
 * 0x00080 X0..X31  the register file (read while the core is stopped)
 * 0x10000 IMEM     instruction memory (write while stopped)
 * 0x20000 DMEM     data memory (read/write while stopped)
 * </pre>
 * Clock and reset are {@code aclk}/{@code aresetn} so Vivado connects them automatically.
 */
public final class RvSoc extends Module {
    public static final int ADDR_BITS = 18;

    public final AxiLite sAxi;
    public final UInt leds;

    public RvSoc(Supplier<RvCore> coreFactory, long id) {
        useClockNames("aclk", "aresetn");
        useReset(ResetKind.SYNC_LOW);
        sAxi = input("s_axi", AxiLite.slave(ADDR_BITS));
        leds = output("leds", UInt.w(8));

        Bool run = reg("run", Bool.TYPE, 0);
        Bool irq = reg("irq_ctrl", Bool.TYPE, 0);
        UInt cycles = reg("cycles", UInt.w(32), 0);
        UInt retired = reg("retired", UInt.w(32), 0);
        UInt lastPc = reg("last_pc", UInt.w(32), 0);

        // the core runs in its own reset domain: held in reset until software sets CTRL.run
        Bool aresetn = defaultDomain().reset();
        ClockDomain coreDomain = derivedDomain("core", run.not().or(aresetn.not()));
        RvCore core = withClock(coreDomain, () -> instance("core", coreFactory));
        if (core.dbgEn == null) throw new IllegalArgumentException("the core needs Config.debug = true");
        if (core.irq != null) core.irq.set(irq);

        Bool halted = core.halted;
        when(run.and(halted.not()), () -> cycles.set(cycles.addWrap(1)));
        if (core.rvfi1 == null) {
            when(run.and(core.rvfiValid), () -> {
                retired.set(retired.addWrap(1));
                lastPc.set(core.rvfiPcRdata);
            });
        } else {
            // a dual-issue core retires up to two instructions a cycle; channel 1's is the younger
            var ch1 = core.rvfi1;
            when(run.and(core.rvfiValid.or(ch1.valid())), () -> {
                retired.set(retired.addWrap(mux(core.rvfiValid.and(ch1.valid()), UInt.lit(2, 32), UInt.lit(1, 32))));
                lastPc.set(mux(ch1.valid(), ch1.pcRdata(), core.rvfiPcRdata));
            });
        }

        int aw = core.dbgAddr.width();
        var req = AxiLite.serve(sAxi, r -> readData(r, core, run, irq, halted, cycles, retired, lastPc, id), 2);
        UInt region = mux(req.write(), req.writeAddr(), req.readAddr()).bits(17, 16).named("region");
        UInt word = mux(req.write(), req.writeAddr(), req.readAddr()).bits(aw + 1, 2).named("word");
        core.dbgEn.set(run.not());
        core.dbgWe.set(req.write().and(region.ne(0)));
        core.dbgSelDmem.set(region.eq(2));
        core.dbgAddr.set(word);
        core.dbgWdata.set(req.writeData());

        Bool ctrlWrite = req.write().and(region.eq(0)).and(req.writeAddr().bits(15, 2).eq(0));
        when(ctrlWrite, () -> {
            Bool starting = req.writeData().bit(0).and(run.not());
            run.set(req.writeData().bit(0));
            irq.set(req.writeData().bit(1));
            when(starting, () -> {
                cycles.set(0);
                retired.set(0);
            });
        });

        // LEDs: run, halted, irq, and activity
        leds.set(retired.bits(22, 18).cat(irq, halted, run));
    }

    private static UInt readData(AxiLite.Requests r, RvCore core, Bool run, Bool irq, Bool halted, UInt cycles,
                                 UInt retired, UInt lastPc, long id) {
        UInt a = r.readAddr();
        UInt reg = a.bits(15, 2);
        UInt control = Hw.select(reg.bits(2, 0), i -> switch (i) {
            case 0 -> irq.cat(run).zext(32);
            case 1 -> halted.zext(32);
            case 2 -> cycles;
            case 3 -> retired;
            case 4 -> UInt.lit(id, 32);
            case 5 -> lastPc;
            default -> UInt.lit(0, 32);
        });
        UInt regRegion = mux(reg.bits(13, 5).eq(1), core.dbgRegData, control);
        return mux(a.bits(17, 16).eq(2), core.dbgRdata, regRegion);
    }
}
