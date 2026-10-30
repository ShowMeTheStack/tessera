package org.eninehq.tessera.pipelinetests;

import static org.eninehq.tessera.pipeline.InOrderModel.READ_REGS;
import static org.eninehq.tessera.pipeline.InOrderModel.WRITE_REGS;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.eninehq.tessera.core.Bool;
import org.eninehq.tessera.core.Hw;
import org.eninehq.tessera.core.Mem;
import org.eninehq.tessera.core.Module;
import org.eninehq.tessera.core.Tessera;
import org.eninehq.tessera.core.TesseraException;
import org.eninehq.tessera.core.UInt;
import org.eninehq.tessera.pipeline.InOrderModel;
import org.eninehq.tessera.pipeline.InOrderPipeline;
import org.eninehq.tessera.pipeline.Key;
import org.eninehq.tessera.pipeline.Schedule;
import org.junit.jupiter.api.Test;

/** Schedule validation of Level-2 pipelines, on a tiny accumulator machine. */
class InOrderPipelineTest {
    static final Key<UInt> PC = Key.of("pc"), INST = Key.of("inst"), RS = Key.of("rs"), RD = Key.of("rd"),
            RS_VAL = Key.of("rsVal"), RESULT = Key.of("result"), TARGET = Key.of("target");
    static final Key<Bool> USES = Key.of("uses"), WRITES = Key.of("writes"), JUMP = Key.of("jump"), HALT = Key.of("halt");

    /** Model order: fetch, decode, readRegs, log (side effect), alu, branch, writeRegs. */
    static class Tiny extends Module {
        Tiny(Schedule schedule) {
            Mem<UInt> rom = mem("rom", UInt.w(16), 16);
            rom.init(0);
            Mem<UInt> rf = mem("rf", UInt.w(8), 4);
            Mem<UInt> log = mem("log", UInt.w(8), 16);
            var m = InOrderModel.create()
                    .pc(PC, 4, 0, 1)
                    .registerFile(rf, false)
                    .step("fetch", c -> c.put(INST, rom.read(c.get(PC))))
                    .step("decode", c -> {
                        UInt i = c.get(INST);
                        c.put(RS, i.bits(1, 0));
                        c.put(RD, i.bits(3, 2));
                        c.put(USES, Hw.TRUE);
                        c.put(WRITES, i.bit(4));
                        c.put(JUMP, i.bit(5));
                        c.put(HALT, i.bit(6));
                        c.put(TARGET, i.bits(11, 8));
                    })
                    .readPort(RS, USES, RS_VAL)
                    .readRegs()
                    .effectStep("log", c -> Hw.when(c.fire(), () -> log.write(c.get(PC), c.get(RS_VAL))))
                    .step("alu", c -> c.put(RESULT, c.get(RS_VAL).addWrap(1)))
                    .step("branch", c -> {})
                    .writeRegs(RD, WRITES, RESULT)
                    .resultAfter("alu", RESULT, null)
                    .redirect("branch", JUMP, TARGET)
                    .halt("decode", HALT);
            InOrderPipeline.build("tiny", m, schedule);
        }
    }

    @Test
    void validScheduleElaborates() {
        Tessera.elaborate(() -> new Tiny(Schedule.stages("F", "D", "X", "W")
                .place("F", "fetch").place("D", "decode", READ_REGS).place("X", "log", "alu", "branch")
                .place("W", WRITE_REGS)));
    }

    @Test
    void sideEffectsBeforeRedirectsResolveAreRejected() {
        var e = assertThrows(TesseraException.class, () -> Tessera.elaborate(() -> new Tiny(Schedule.stages("F", "D", "X", "W")
                .place("F", "fetch").place("D", "decode", READ_REGS, "log").place("X", "alu", "branch")
                .place("W", WRITE_REGS))));
        assertTrue(e.getMessage().contains("side-effect step 'log' is in D, before redirects resolve in X"), e.getMessage());
    }

    @Test
    void stepsMustFollowModelOrder() {
        var e = assertThrows(TesseraException.class, () -> Tessera.elaborate(() -> new Tiny(Schedule.stages("F", "D", "X", "W")
                .place("F", "fetch").place("D", "decode", READ_REGS, "alu").place("X", "log", "branch")
                .place("W", WRITE_REGS))));
        assertTrue(e.getMessage().contains("step 'alu' is scheduled in D, before step 'log' (X)"), e.getMessage());
    }

    /** A tiny predicting model with speculative state: a fetch counter, snapshotted per item and repaired on a redirect. */
    static class Spec extends Module {
        static final Key<UInt> SNAP = Key.of("snap"), NEXT = Key.of("next"), GUESS = Key.of("guess");

        Spec(boolean declare, boolean repair, Key<UInt> declared) {
            Mem<UInt> rom = mem("rom", UInt.w(16), 16);
            rom.init(0);
            Mem<UInt> rf = mem("rf", UInt.w(8), 4);
            UInt count = reg("count", UInt.w(8));
            var m = InOrderModel.create()
                    .pc(PC, 4, 0, 1)
                    .registerFile(rf, false)
                    .step("fetch", c -> {
                        c.put(INST, rom.read(c.get(PC)));
                        c.put(SNAP, c.capture(count));
                        c.put(GUESS, c.get(PC).addWrap(1));
                        count.set(mux(c.fire(), count.addWrap(1), count));
                    })
                    .step("decode", c -> {
                        UInt i = c.get(INST);
                        c.put(RS, i.bits(1, 0));
                        c.put(RD, i.bits(3, 2));
                        c.put(USES, Hw.TRUE);
                        c.put(WRITES, i.bit(4));
                        c.put(HALT, i.bit(6));
                        c.put(NEXT, mux(i.bit(5), i.bits(11, 8), c.get(PC).addWrap(1)));
                    })
                    .readPort(RS, USES, RS_VAL)
                    .readRegs()
                    .step("alu", c -> c.put(RESULT, c.get(RS_VAL).addWrap(1)))
                    .writeRegs(RD, WRITES, RESULT)
                    .resultAfter("alu", RESULT, null)
                    .predict(GUESS)
                    .resolve("alu", NEXT, null)
                    .halt("decode", HALT);
            if (declare) m.speculative(declared, 8);
            if (repair) m.onRedirect(r -> Hw.when(r.taken(), () -> count.set(r.get(SNAP).addWrap(1))));
            InOrderPipeline.build("spec", m, Schedule.stages("F", "D", "X", "W")
                    .place("F", "fetch").place("D", "decode", READ_REGS).place("X", "alu").place("W", WRITE_REGS));
        }

        static UInt mux(Bool c, UInt a, UInt b) { return Hw.mux(c, a, b); }
    }

    @Test
    void speculativeStateWithARepairElaborates() {
        Tessera.elaborate(() -> new Spec(true, true, Spec.SNAP));
    }

    @Test
    void speculativeStateNeedsARepair() {
        var e = assertThrows(TesseraException.class, () -> Tessera.elaborate(() -> new Spec(true, false, Spec.SNAP)));
        assertTrue(e.getMessage().contains("declares speculative state but no onRedirect"), e.getMessage());
    }

    @Test
    void theRepairReadsOnlyDeclaredKeys() {
        var e = assertThrows(TesseraException.class, () -> Tessera.elaborate(() -> new Spec(false, true, Spec.SNAP)));
        assertTrue(e.getMessage().contains("is not declared speculative"), e.getMessage());
    }

    @Test
    void declaredWidthIsChecked() {
        var e = assertThrows(TesseraException.class, () -> Tessera.elaborate(() -> new Spec(true, true, PC)));
        assertTrue(e.getMessage().contains("is declared 8 bits wide but is 4"), e.getMessage());
    }

    @Test
    void everyStepMustBePlaced() {
        var e = assertThrows(TesseraException.class, () -> Tessera.elaborate(() -> new Tiny(Schedule.stages("F", "X")
                .place("F", "fetch").place("X", "decode", READ_REGS, "log", "alu", WRITE_REGS))));
        assertTrue(e.getMessage().contains("step 'branch' is not placed"), e.getMessage());
    }
}
