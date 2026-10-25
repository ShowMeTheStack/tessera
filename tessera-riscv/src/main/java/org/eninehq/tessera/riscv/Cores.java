package org.eninehq.tessera.riscv;

import java.util.List;

/**
 * The validation cores, each in the common configuration: RV32IM, Zicsr
 * (CSRs, traps, interrupts), a debug port and the sequential divider, with block RAM except
 * C1a. Every experiment (tests, riscv-formal, QoR, the board) takes its cores from here.
 */
public final class Cores {
    private Cores() {}

    /** A core configuration: an id (C1a..C7), a description, and how to build it. */
    public record Spec(String id, String name, Factory factory) {
        public RvCore build(int memWords, int[] image) {
            return factory.make(memWords, image);
        }
    }

    @FunctionalInterface
    public interface Factory {
        RvCore make(int memWords, int[] image);
    }

    /** The common configuration; {@code bram} false only for the single-cycle LUT-RAM core. */
    public static RvCore.Config common(boolean bram) {
        return new RvCore.Config(true, bram, true, true);
    }

    public static final List<Spec> ALL = List.of(
            new Spec("C1a", "single-cycle, LUT RAM", (m, i) -> new SingleCycleCore(m, i, common(false))),
            new Spec("C1b", "multi-cycle (single-cycle datapath, block RAM)", (m, i) -> new SingleCycleCore(m, i, common(true))),
            new Spec("C2", "five-stage, hand-written (Level 1)", (m, i) -> new FiveStageCore(m, i, common(true))),
            new Spec("C3", "three-stage, scheduled (Level 2)", (m, i) -> new ScheduledCore(m, i, ScheduledCore.THREE_STAGE_BRAM, common(true))),
            new Spec("C4", "four-stage, scheduled (Level 2)", (m, i) -> new ScheduledCore(m, i, ScheduledCore.FOUR_STAGE_BRAM, common(true))),
            // C5 and C6 finish multiplies in two steps, summed in MEM and corrected in WB; C5-v1 and C6-v1 (BEFORE)
            // finish them in MEM
            new Spec("C5", "five-stage, scheduled (Level 2)", (m, i) -> new ScheduledCore(m, i, ScheduledCore.FIVE_STAGE_MUL_SUM_WB, common(true))),
            new Spec("C6", "six-stage, scheduled (Level 2)", (m, i) -> new ScheduledCore(m, i, ScheduledCore.SIX_STAGE_MUL_SUM_WB, common(true))),
            new Spec("C7", "out-of-order, 16-entry ROB / 8 RS", (m, i) -> new OooCore(m, i, common(true))),
            new Spec("C8", "five-stage with branch prediction (64-entry BTB), scheduled (Level 2)",
                    (m, i) -> new ScheduledCore(m, i, ScheduledCore.FIVE_STAGE_MUL_SUM_WB, common(true), ScheduledCore.Predictor.btb(64))),
            // two lanes, in order, from the same model
            new Spec("C9", "dual-issue five-stage, scheduled (Level 2)", (m, i) -> new ScheduledCore(m, i, ScheduledCore.FIVE_STAGE_MUL_SUM_WB.withLanes(2), common(true))),
            // jumps the BTB misses are found in execute: decode's JAL check behind the lane shift cost 13% of Fmax/CPI
            new Spec("C10", "dual-issue five-stage with branch prediction (64-entry BTB), scheduled (Level 2)",
                    (m, i) -> new ScheduledCore(m, i, ScheduledCore.FIVE_STAGE_MUL_SUM_WB.withLanes(2), common(true), ScheduledCore.Predictor.btb(64).withoutDecode())));

    /** C5 and C6 with the multiply finished in the memory step. Not in {@link #ALL}. */
    public static final List<Spec> BEFORE = List.of(
            new Spec("C5-v1", "five-stage, scheduled (Level 2), multiply finished in MEM", (m, i) -> new ScheduledCore(m, i, ScheduledCore.FIVE_STAGE, common(true))),
            new Spec("C6-v1", "six-stage, scheduled (Level 2), multiply finished in MEM", (m, i) -> new ScheduledCore(m, i, ScheduledCore.SIX_STAGE, common(true))));

    /**
     * Where the multiply finishes, for C5 and C6: "mmem" in "memory" as a separate step (the same pipeline as
     * C5/C6), "mwb" in write-back with the result not forwarded, "mmf" in a stage of its own; "m2wb", "m2mf" and
     * "mfwb" split the finish into a sum and a correction (MEM+WB, MEM+MF, MF+WB). Not in {@link #ALL}.
     */
    public static final List<Spec> MUL_VARIANTS = List.of(
            new Spec("C5-mmem", "five-stage, multiply finished in MEM as its own step", (m, i) -> new ScheduledCore(m, i, ScheduledCore.FIVE_STAGE_MUL_MEM, common(true))),
            new Spec("C5-mwb", "five-stage, multiply finished in WB", (m, i) -> new ScheduledCore(m, i, ScheduledCore.FIVE_STAGE_MUL_WB, common(true))),
            new Spec("C5-mmf", "five-stage plus a multiply-finish stage", (m, i) -> new ScheduledCore(m, i, ScheduledCore.FIVE_STAGE_MUL_MF, common(true))),
            new Spec("C6-mwb", "six-stage, multiply finished in WB", (m, i) -> new ScheduledCore(m, i, ScheduledCore.SIX_STAGE_MUL_WB, common(true))),
            new Spec("C6-mmf", "six-stage plus a multiply-finish stage", (m, i) -> new ScheduledCore(m, i, ScheduledCore.SIX_STAGE_MUL_MF, common(true))),
            // the finish itself split: summed ("mulSum") in one stage, corrected ("mulFinish") in a later one
            new Spec("C5-m2wb", "five-stage, multiply summed in MEM, corrected in WB", (m, i) -> new ScheduledCore(m, i, ScheduledCore.FIVE_STAGE_MUL_SUM_WB, common(true))),
            new Spec("C5-m2mf", "five-stage plus MF, multiply summed in MEM, corrected in MF", (m, i) -> new ScheduledCore(m, i, ScheduledCore.FIVE_STAGE_MUL_SUM_MF, common(true))),
            new Spec("C5-mfwb", "five-stage plus MF, multiply summed in MF, corrected in WB", (m, i) -> new ScheduledCore(m, i, ScheduledCore.FIVE_STAGE_MUL_MF_WB, common(true))),
            new Spec("C6-m2wb", "six-stage, multiply summed in MEM, corrected in WB", (m, i) -> new ScheduledCore(m, i, ScheduledCore.SIX_STAGE_MUL_SUM_WB, common(true))),
            new Spec("C6-m2mf", "six-stage plus MF, multiply summed in MEM, corrected in MF", (m, i) -> new ScheduledCore(m, i, ScheduledCore.SIX_STAGE_MUL_SUM_MF, common(true))),
            new Spec("C6-mfwb", "six-stage plus MF, multiply summed in MF, corrected in WB", (m, i) -> new ScheduledCore(m, i, ScheduledCore.SIX_STAGE_MUL_MF_WB, common(true))));

    /**
     * Branch prediction on C5's pipeline: "static" guesses JAL and backward branches taken in decode; "btbN" has an
     * N-entry branch target buffer with 2-bit counters in fetch (and resolves JAL in decode). Not in {@link #ALL}.
     */
    public static final List<Spec> PREDICT_VARIANTS = List.of(
            new Spec("C5-static", "five-stage, static prediction in decode", (m, i) -> new ScheduledCore(m, i, ScheduledCore.FIVE_STAGE, common(true), ScheduledCore.Predictor.STATIC)),
            new Spec("C5-btb8", "five-stage, 8-entry BTB", (m, i) -> new ScheduledCore(m, i, ScheduledCore.FIVE_STAGE, common(true), ScheduledCore.Predictor.btb(8))),
            new Spec("C5-btb16", "five-stage, 16-entry BTB", (m, i) -> new ScheduledCore(m, i, ScheduledCore.FIVE_STAGE, common(true), ScheduledCore.Predictor.btb(16))),
            new Spec("C5-btb32", "five-stage, 32-entry BTB", (m, i) -> new ScheduledCore(m, i, ScheduledCore.FIVE_STAGE, common(true), ScheduledCore.Predictor.btb(32))),
            new Spec("C5-btb64", "five-stage, 64-entry BTB", (m, i) -> new ScheduledCore(m, i, ScheduledCore.FIVE_STAGE, common(true), ScheduledCore.Predictor.btb(64))),
            // the same predictors on the five-stage schedule with the multiply split (C5-m2wb), the candidates for C8
            new Spec("C8-static", "five-stage (multiply summed in MEM, corrected in WB), static prediction", (m, i) -> new ScheduledCore(m, i, ScheduledCore.FIVE_STAGE_MUL_SUM_WB, common(true), ScheduledCore.Predictor.STATIC)),
            new Spec("C8-btb8", "five-stage (multiply split), 8-entry BTB", (m, i) -> new ScheduledCore(m, i, ScheduledCore.FIVE_STAGE_MUL_SUM_WB, common(true), ScheduledCore.Predictor.btb(8))),
            new Spec("C8-btb16", "five-stage (multiply split), 16-entry BTB", (m, i) -> new ScheduledCore(m, i, ScheduledCore.FIVE_STAGE_MUL_SUM_WB, common(true), ScheduledCore.Predictor.btb(16))),
            new Spec("C8-btb32", "five-stage (multiply split), 32-entry BTB", (m, i) -> new ScheduledCore(m, i, ScheduledCore.FIVE_STAGE_MUL_SUM_WB, common(true), ScheduledCore.Predictor.btb(32))),
            new Spec("C8-btb64", "five-stage (multiply split), 64-entry BTB", (m, i) -> new ScheduledCore(m, i, ScheduledCore.FIVE_STAGE_MUL_SUM_WB, common(true), ScheduledCore.Predictor.btb(64))),
            // without decode's JAL check (the BTB and execute only)
            new Spec("C8-nodec", "five-stage (multiply split), 64-entry BTB, no JAL check in decode", (m, i) -> new ScheduledCore(m, i, ScheduledCore.FIVE_STAGE_MUL_SUM_WB, common(true), ScheduledCore.Predictor.btb(64).withoutDecode())),
            // C10 with decode's JAL check
            new Spec("C10-dec", "dual-issue five-stage, 64-entry BTB, JAL checked in decode", (m, i) -> new ScheduledCore(m, i, ScheduledCore.FIVE_STAGE_MUL_SUM_WB.withLanes(2), common(true), ScheduledCore.Predictor.btb(64))));


    /** Every core: {@link #ALL} and the variants. */
    public static List<Spec> all() {
        var out = new java.util.ArrayList<>(ALL);
        out.addAll(BEFORE);
        out.addAll(MUL_VARIANTS);
        out.addAll(PREDICT_VARIANTS);
        return out;
    }

    /** A core of {@link #ALL} or a variant, by id. */
    public static Spec get(String id) {
        return all().stream().filter(s -> s.id().equalsIgnoreCase(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("no core " + id + "; one of " + all().stream().map(Spec::id).toList()));
    }
}
