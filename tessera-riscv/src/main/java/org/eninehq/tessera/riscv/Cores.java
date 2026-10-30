package org.eninehq.tessera.riscv;

import java.util.List;

/**
 * The validation cores, each in the common configuration: RV32IM, Zicsr
 * (CSRs, traps, interrupts), a debug port and the sequential divider, with block RAM except
 * C1a. Every experiment (tests, riscv-formal, QoR, the board) takes its cores from here.
 */
public final class Cores {
    private Cores() {}

    /** A core configuration: an id (C1a..C11), a description, and how to build it. */
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
                    (m, i) -> new ScheduledCore(m, i, ScheduledCore.FIVE_STAGE_MUL_SUM_WB.withLanes(2), common(true), ScheduledCore.Predictor.btb(64).withoutDecode())),
            // C7 with execute-time branch recovery (RAT checkpoints, branch masks): a core of its own
            new Spec("C11", "out-of-order with execute-time branch recovery and a load/store queue, 16-entry ROB / 8 RS / 4 checkpoints / 4+4 LSQ",
                    (m, i) -> new OooSpecCore(m, i, common(true))));

    /**
     * C11's stages and memory: "br" is execute-time branch recovery alone, with memory at the head as in C7; "tdp" is C11 with a true dual-port data memory (port A speculative loads, port B committed stores). Not in {@link #ALL}.
     */
    public static final List<Spec> OOO_VARIANTS = List.of(
            new Spec("C11-br", "out-of-order with execute-time branch recovery, memory at the head, 16-entry ROB / 8 RS / 4 checkpoints",
                    (m, i) -> new OooSpecCore(m, i, common(true), 16, 8, 4)),
            new Spec("C11-tdp", "C11 with a true dual-port data memory (loads on port A, committed stores on port B), 16-entry ROB / 8 RS / 4 checkpoints / 4+4 LSQ",
                    (m, i) -> new OooSpecCore(m, i, common(true), 16, 8, 4, 4, 4, true)));

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
            new Spec("C10-dec", "dual-issue five-stage, 64-entry BTB, JAL checked in decode", (m, i) -> new ScheduledCore(m, i, ScheduledCore.FIVE_STAGE_MUL_SUM_WB.withLanes(2), common(true), ScheduledCore.Predictor.btb(64))),
            // gshare (8-bit global history, 256 counters) and an 8-entry return-address stack on C8 and C10, as model
            // code over the generator's speculative-state hook (InOrderModel.speculative/onRedirect)
            new Spec("C8-gshare", "five-stage (multiply split), 64-entry BTB + gshare (8-bit history)", (m, i) -> new ScheduledCore(m, i, ScheduledCore.FIVE_STAGE_MUL_SUM_WB, common(true), ScheduledCore.Predictor.btb(64).withGshare(8))),
            new Spec("C8-ras", "five-stage (multiply split), 64-entry BTB + 8-entry return-address stack", (m, i) -> new ScheduledCore(m, i, ScheduledCore.FIVE_STAGE_MUL_SUM_WB, common(true), ScheduledCore.Predictor.btb(64).withRas(8))),
            new Spec("C8-gsras", "five-stage (multiply split), 64-entry BTB + gshare (8-bit history) + 8-entry RAS", (m, i) -> new ScheduledCore(m, i, ScheduledCore.FIVE_STAGE_MUL_SUM_WB, common(true), ScheduledCore.Predictor.btb(64).withGshare(8).withRas(8))),
            new Spec("C10-gsras", "dual-issue five-stage, 64-entry BTB + gshare (8-bit history) + 8-entry RAS", (m, i) -> new ScheduledCore(m, i, ScheduledCore.FIVE_STAGE_MUL_SUM_WB.withLanes(2), common(true), ScheduledCore.Predictor.btb(64).withoutDecode().withGshare(8).withRas(8))),
            // the best of these predictors on Embench (C8-gs12ras, geomean CPI -0.60% vs C8), and C10 with it (-0.83% vs
            // C10): gshare with 12 bits of history (4096 counters) and the RAS; the 8-bit gshare loses (+0.60%)
            new Spec("C8-gs12ras", "five-stage (multiply split), 64-entry BTB + gshare (12-bit history) + 8-entry RAS",
                    (m, i) -> new ScheduledCore(m, i, ScheduledCore.FIVE_STAGE_MUL_SUM_WB, common(true), ScheduledCore.Predictor.btb(64).withGshare(12).withRas(8))),
            new Spec("C10-gs12ras", "dual-issue five-stage, 64-entry BTB + gshare (12-bit history) + 8-entry RAS",
                    (m, i) -> new ScheduledCore(m, i, ScheduledCore.FIVE_STAGE_MUL_SUM_WB.withLanes(2), common(true), ScheduledCore.Predictor.btb(64).withoutDecode().withGshare(12).withRas(8))));


    /**
     * The gshare history length on C8 (h bits of history, 2^h counters), for the choice of h. Simulation only: in
     * {@link #all()} (so tests can name them) but not in {@link #PREDICT_VARIANTS}, QoR, the SV hashes or riscv-formal.
     */
    public static final List<Spec> GSHARE_SWEEP = java.util.stream.Stream.concat(java.util.stream.IntStream.of(4, 6, 10, 12).mapToObj(h -> new Spec("C8-gs" + h,
            "five-stage (multiply split), 64-entry BTB + gshare (" + h + "-bit history)",
            (m, i) -> new ScheduledCore(m, i, ScheduledCore.FIVE_STAGE_MUL_SUM_WB, common(true), ScheduledCore.Predictor.btb(64).withGshare(h)))),
            // C10 with the RAS alone and with the 12-bit gshare alone (the best, C10-gs12ras, is in PREDICT_VARIANTS)
            java.util.stream.Stream.of(
                    new Spec("C10-ras", "dual-issue five-stage, 64-entry BTB + 8-entry RAS",
                            (m, i) -> new ScheduledCore(m, i, ScheduledCore.FIVE_STAGE_MUL_SUM_WB.withLanes(2), common(true), ScheduledCore.Predictor.btb(64).withoutDecode().withRas(8))),
                    new Spec("C10-gs12", "dual-issue five-stage, 64-entry BTB + gshare (12-bit history)",
                            (m, i) -> new ScheduledCore(m, i, ScheduledCore.FIVE_STAGE_MUL_SUM_WB.withLanes(2), common(true), ScheduledCore.Predictor.btb(64).withoutDecode().withGshare(12)))))
            .toList();

    /**
     * Out-of-order cores generated by {@link org.eninehq.tessera.pipeline.OutOfOrderPipeline} from
     * {@link GeneratedOooCore}'s model, with C7's organisation (memory at the head, recovery at commit). Not in {@link #ALL}.
     */
    public static final List<Spec> GENERATED = List.of(
            new Spec("C7g", "out-of-order, generated from the model (C7's organisation), 16-entry ROB / 8 RS",
                    (m, i) -> new GeneratedOooCore(m, i, common(true))),
            new Spec("C7g-4x3", "out-of-order, generated from the model, 4-entry ROB / 3 RS",
                    (m, i) -> new GeneratedOooCore(m, i, common(true), 4, 3)),
            // M-e4: the same with branch recovery at execute (a generator option)
            new Spec("C7g-er", "out-of-order, generated, recovery at execute, 16-entry ROB / 8 RS",
                    (m, i) -> new GeneratedOooCore(m, i, common(true), 16, 8, true)),
            new Spec("C7g-er-4x3", "out-of-order, generated, recovery at execute, 4-entry ROB / 3 RS",
                    (m, i) -> new GeneratedOooCore(m, i, common(true), 4, 3, true)),
            // M-e4: loads out of order (load unit, ordering against older stores in the ROB), with and without early recovery
            new Spec("C7g-lsq", "out-of-order, generated, loads out of order, 16-entry ROB / 8 RS",
                    (m, i) -> new GeneratedOooCore(m, i, common(true), 16, 8, false, true)),
            new Spec("C7g-er-lsq", "out-of-order, generated, recovery at execute and loads out of order, 16-entry ROB / 8 RS",
                    (m, i) -> new GeneratedOooCore(m, i, common(true), 16, 8, true, true)),
            new Spec("C7g-er-lsq-4x3", "out-of-order, generated, recovery at execute and loads out of order, 4-entry ROB / 3 RS",
                    (m, i) -> new GeneratedOooCore(m, i, common(true), 4, 3, true, true)),
            new Spec("C5g", "five-stage, the same model through the in-order generator",
                    (m, i) -> new GeneratedOooCore(m, i, common(true), GeneratedOooCore.FIVE_STAGE)));

    /**
     * Symmetric lanes, as variants: C9 and C10 with loads and stores in lane 1 too, on the second
     * port of a true dual-port data memory. "s": a pair with a store first and a memory access second is split at
     * issue; "s-rep": such a pair issues and lane 1 is replayed only when both touch one word. M stays in lane 0.
     * Not in {@link #ALL}.
     */
    public static final List<Spec> SYMMETRIC_VARIANTS = List.of(
            new Spec("C9s", "dual-issue five-stage, loads and stores in both lanes (pair split on store + access)",
                    (m, i) -> new ScheduledCore(m, i, ScheduledCore.FIVE_STAGE_MUL_SUM_WB.withLanes(2), common(true), ScheduledCore.Predictor.NONE,
                            ScheduledCore.Lane1Memory.SPLIT)),
            new Spec("C10s", "dual-issue five-stage, 64-entry BTB, loads and stores in both lanes (pair split on store + access)",
                    (m, i) -> new ScheduledCore(m, i, ScheduledCore.FIVE_STAGE_MUL_SUM_WB.withLanes(2), common(true),
                            ScheduledCore.Predictor.btb(64).withoutDecode(), ScheduledCore.Lane1Memory.SPLIT)),
            new Spec("C9s-rep", "dual-issue five-stage, loads and stores in both lanes (lane 1 replayed on a same-word store)",
                    (m, i) -> new ScheduledCore(m, i, ScheduledCore.FIVE_STAGE_MUL_SUM_WB.withLanes(2), common(true), ScheduledCore.Predictor.NONE,
                            ScheduledCore.Lane1Memory.REPLAY)),
            new Spec("C10s-rep", "dual-issue five-stage, 64-entry BTB, loads and stores in both lanes (lane 1 replayed on a same-word store)",
                    (m, i) -> new ScheduledCore(m, i, ScheduledCore.FIVE_STAGE_MUL_SUM_WB.withLanes(2), common(true),
                            ScheduledCore.Predictor.btb(64).withoutDecode(), ScheduledCore.Lane1Memory.REPLAY)));

    /**
     * C9 and C10 with the register file as a live-value table, as variants: one-write LUT RAMs
     * per write and read port and a table of last writers, instead of 1,024 flip-flops and their read muxes.
     * Not in {@link #ALL}.
     */
    public static final List<Spec> LVT_VARIANTS = List.of(
            new Spec("C9-lvt", "dual-issue five-stage, live-value-table register file",
                    (m, i) -> new ScheduledCore(m, i, ScheduledCore.FIVE_STAGE_MUL_SUM_WB.withLanes(2), common(true), ScheduledCore.Predictor.NONE,
                            ScheduledCore.Lane1Memory.NONE, true)),
            new Spec("C10-lvt", "dual-issue five-stage, 64-entry BTB, live-value-table register file",
                    (m, i) -> new ScheduledCore(m, i, ScheduledCore.FIVE_STAGE_MUL_SUM_WB.withLanes(2), common(true),
                            ScheduledCore.Predictor.btb(64).withoutDecode(), ScheduledCore.Lane1Memory.NONE, true)));

    /** Every core: {@link #ALL} and the variants. */
    public static List<Spec> all() {
        var out = new java.util.ArrayList<>(ALL);
        out.addAll(BEFORE);
        out.addAll(MUL_VARIANTS);
        out.addAll(PREDICT_VARIANTS);
        out.addAll(OOO_VARIANTS);
        out.addAll(GSHARE_SWEEP);
        out.addAll(GENERATED);
        out.addAll(SYMMETRIC_VARIANTS);
        out.addAll(LVT_VARIANTS);
        return out;
    }

    /** A core of {@link #ALL} or a variant, by id. */
    public static Spec get(String id) {
        return all().stream().filter(s -> s.id().equalsIgnoreCase(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("no core " + id + "; one of " + all().stream().map(Spec::id).toList()));
    }
}
