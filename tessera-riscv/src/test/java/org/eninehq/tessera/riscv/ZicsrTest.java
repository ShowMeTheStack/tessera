package org.eninehq.tessera.riscv;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.Arrays;
import java.util.function.Supplier;
import org.eninehq.tessera.core.Elaborated;
import org.eninehq.tessera.core.Tessera;
import org.eninehq.tessera.sim.Sim;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Machine-mode CSRs, traps and interrupts: the ISS and the cores, instruction by instruction. */
class ZicsrTest {
    static final int MEM = 4096;

    static int[] image(String name) {
        return RiscvTests.image("../zicsr/" + name);
    }

    /** Run on the core, check every retirement against the ISS; raise irq from {@code irqAt} (cycle) until the end. */
    static Rv32iIss cosim(Supplier<? extends RvCore> core, String program, long irqAt) {
        return cosim(Tessera.elaborate(core), program, irqAt);
    }

    /** The same on an elaborated core, so that one elaboration serves many runs. */
    static Rv32iIss cosim(Elaborated<? extends RvCore> e, String program, long irqAt) {
        int[] img = image(program);
        var iss = new Rv32iIss(MEM, img, true);
        try (var sim = Sim.create(e, Sim.Backend.COMPILED)) {
            RvCore c = e.top();
            long[] imem = sim.memory(c.imem), dmem = sim.memory(c.dmem);
            Arrays.fill(imem, 0);
            Arrays.fill(dmem, 0);
            for (int i = 0; i < img.length; i++) {
                imem[i] = Integer.toUnsignedLong(img[i]);
                dmem[i] = Integer.toUnsignedLong(img[i]);
            }
            var effects = new SideEffects(sim, c, iss);
            sim.reset(1);
            long start = sim.cycle(), retired = 0;
            var ports = c.retirePorts();
            while (sim.peek(c.halted) == 0) {
                if (sim.cycle() - start > 100_000) fail("no halt; ISS pc " + iss.pc);
                boolean irq = sim.cycle() - start >= irqAt;
                sim.poke(c.irq, irq ? 1 : 0);
                for (var port : ports) {
                    if (sim.peek(port.valid()) != 1) continue;
                    var got = new Retire((int) sim.peek(port.pc()), (int) sim.peek(port.insn()), (int) sim.peek(port.rd()),
                            (int) sim.peek(port.rdValue()));
                    iss.irq = irq;
                    var want = sim.peek(port.intr()) == 1 ? iss.stepAfterInterrupt() : iss.step();
                    if (!got.equals(want)) fail("retire #" + retired + " differs:\n  core " + got + "\n  iss  " + want);
                    retired++;
                }
                effects.sample();
                sim.step();
            }
            assertTrue(iss.halted, "ISS did not halt");
            effects.check(dmem);
            long[] regs = CoreRunner.regs(sim, c);
            for (int r = 1; r < 32; r++) assertEquals(iss.x[r], (int) regs[r], "x" + r);
        }
        return iss;
    }

    static Supplier<? extends RvCore> core(String kind) {
        return switch (kind) {
            case "single" -> () -> new SingleCycleCore(MEM, null, true);
            case "sched5" -> () -> new ScheduledCore(MEM, null, ScheduledCore.FIVE_STAGE, true);
            case "sched3" -> () -> new ScheduledCore(MEM, null, ScheduledCore.THREE_STAGE, true);
            case "fpga" -> () -> new ScheduledCore(MEM, null, ScheduledCore.FIVE_STAGE, RvCore.Config.fpga());
            case "ooo" -> () -> new OooCore(MEM, null, new RvCore.Config(true, false, false));
            case "ooo-small" -> () -> new OooCore(MEM, null, new RvCore.Config(true, false, false), 4, 3);
            case "ooo-fpga" -> () -> new OooCore(MEM, null, RvCore.Config.fpga());
            default -> throw new IllegalArgumentException(kind);
        };
    }

    // registers: s0=x8 s1=x9 s2=x18 ... s11=x27, a0=x10
    static int s(Rv32iIss iss, int n) {
        return iss.x[n < 2 ? 8 + n : 16 + n];
    }

    @ParameterizedTest
    @ValueSource(strings = {"single", "sched5", "sched3", "fpga", "ooo", "ooo-small", "ooo-fpga"})
    void csrInstructions(String kind) {
        checkCsr(cosim(core(kind), "csr", Long.MAX_VALUE));
    }

    @ParameterizedTest
    @ValueSource(strings = {"single", "sched5", "sched3", "fpga", "ooo", "ooo-small", "ooo-fpga"})
    void exceptionsTrapToTheHandler(String kind) {
        checkTraps(cosim(core(kind), "traps", Long.MAX_VALUE));
    }

    @ParameterizedTest
    @ValueSource(strings = {"single", "sched5", "sched3", "fpga", "ooo", "ooo-small", "ooo-fpga"})
    void externalInterrupt(String kind) {
        checkIrq(core(kind));
    }

    /**
     * Side effects that retirements do not show: trapping stores must not write memory, and a CSR instruction
     * held behind a divide must not write its CSR before it retires. The second runs with the interrupt raised
     * at every 5th cycle of the loop, so that one of them arrives while the instruction is held.
     */
    static void checkSideEffects(Supplier<? extends RvCore> core) {
        var e = Tessera.elaborate(core);
        var iss = cosim(e, "sidefx-store", Long.MAX_VALUE);
        assertEquals(0x11223344, s(iss, 1), "aligned sw");
        assertEquals(0x00008800, s(iss, 2), "sb at offset 9; the misaligned sh at 5 did not write");
        assertEquals(0x55667788, s(iss, 3), "the store behind the trapping load ran once, after the handler");
        assertEquals(0x11223344, s(iss, 4), "the store behind the ecall ran once, after the handler");
        assertEquals(0x55667788 / 3, s(iss, 5), "the quotient; the misaligned store behind the divide did not write");
        assertEquals(5, s(iss, 6), "five traps");
        for (long at = 0; at <= 400; at += 5) {
            var r = cosim(e, "sidefx-irq", at);
            assertEquals(1, s(r, 2), "handler ran, irq from cycle " + at);
            assertEquals(23, s(r, 3), "mscratch after the loop, irq from cycle " + at);
        }
    }

    static void checkCsr(Rv32iIss iss) {
        assertEquals(0x12345678, s(iss, 0));
        assertEquals(0x12345678, s(iss, 1));
        assertEquals(0x123456F8, s(iss, 2));
        assertEquals(0x123456F8, s(iss, 3));
        assertEquals(0x123456F0, s(iss, 4));
        assertEquals(0x123456F0, s(iss, 5));
        assertEquals(28, s(iss, 6));
        assertEquals(0x40001100, s(iss, 7));
        assertEquals(0, s(iss, 8));
        assertEquals(3, s(iss, 9));
        assertEquals(0xFFFFFFFC, s(iss, 10));
        assertEquals(0x1888, s(iss, 11));
    }

    static void checkTraps(Rv32iIss iss) {
        assertEquals(11, s(iss, 1), "ecall");
        assertEquals(0, s(iss, 2));
        assertEquals(2, s(iss, 3), "illegal");
        assertEquals(4, s(iss, 4), "misaligned load");
        assertEquals(0x1003, s(iss, 5));
        assertEquals(2, s(iss, 6), "write to read-only CSR");
        assertEquals(0xF1431073, s(iss, 7), "mtval = the instruction (csrw mhartid, t1)");
        assertEquals(0, s(iss, 8), "misaligned jump");
        assertEquals(5, s(iss, 9), "five traps");
    }

    static void checkIrq(Supplier<? extends RvCore> core) {
        for (long at : new long[]{0, 3, 10}) assertEquals(1, s(cosim(core, "irq", at), 2), "handler ran, irq from cycle " + at);
        var iss = cosim(core, "irq", 60);
        assertEquals(1, s(iss, 2), "handler ran");
        assertTrue(s(iss, 1) > 5, "loop ran before the interrupt: " + s(iss, 1));
        assertEquals(0x8000000B, s(iss, 3), "mcause: machine external interrupt");
        assertEquals(0x1888, s(iss, 4), "MIE restored by mret");
    }
}
