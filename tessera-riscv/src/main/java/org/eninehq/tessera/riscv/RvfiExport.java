package org.eninehq.tessera.riscv;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.eninehq.tessera.core.Tessera;
import org.eninehq.tessera.emit.SvEmitter;

/**
 * Writes the Tessera cores in the layout riscv-formal expects ({@code cores/<name>/}: the
 * SystemVerilog, an {@code rvfi_wrapper} and {@code checks.cfg}). Memories have no initial
 * contents, so formal tools treat the program and data as arbitrary.
 *
 * <p>Usage: {@code RvfiExport <riscv-formal>/cores}
 */
public final class RvfiExport {
    private RvfiExport() {}

    /** A core to export and the BMC depths for its checks. */
    public record Target(String name, Supplier<RvCore> core, int insnDepth, int regDepth) {}

    public static Map<String, Target> targets() {
        var t = new LinkedHashMap<String, Target>();
        t.put("tessera_single", new Target("tessera_single", () -> new SingleCycleCore(16, null), 4, 8));
        t.put("tessera_five", new Target("tessera_five", () -> new FiveStageCore(16, null), 12, 16));
        t.put("tessera_sched3", new Target("tessera_sched3", () -> new ScheduledCore(16, null, ScheduledCore.THREE_STAGE), 8, 12));
        t.put("tessera_sched5", new Target("tessera_sched5", () -> new ScheduledCore(16, null, ScheduledCore.FIVE_STAGE), 12, 16));
        t.put("tessera_sched6", new Target("tessera_sched6", () -> new ScheduledCore(16, null, ScheduledCore.SIX_STAGE), 14, 18));
        t.put("tessera_bram5", new Target("tessera_bram5", () -> new ScheduledCore(16, null, ScheduledCore.FIVE_STAGE, false, true), 12, 16));
        // the out-of-order core with a 4-entry window (the full-size one is the same generator)
        t.put("tessera_ooo", new Target("tessera_ooo", () -> new OooCore(16, null, RvCore.Config.DEFAULT, 4, 3), 14, 18));
        // the ZedBoard configuration: Zicsr (with CSR checks and a free irq), block RAM, sequential divider, idle debug port
        t.put("tessera_fpga5", new Target("tessera_fpga5", () -> new ScheduledCore(16, null, ScheduledCore.FIVE_STAGE, RvCore.Config.fpga()), 12, 16));
        t.put("tessera_single_zicsr", new Target("tessera_single_zicsr", () -> new SingleCycleCore(16, null, true), 4, 8));
        // deliberately broken cores: riscv-formal must find these bugs
        t.put("tessera_five_bug_wbfwd", new Target("tessera_five_bug_wbfwd",
                () -> new FiveStageCore(16, null, FiveStageCore.TestBug.NO_WB_FORWARD), 12, 16));
        t.put("tessera_five_bug_loaduse", new Target("tessera_five_bug_loaduse",
                () -> new FiveStageCore(16, null, FiveStageCore.TestBug.NO_LOAD_USE_STALL), 12, 16));
        // the validation cores (Cores), each in the common configuration: Zicsr with CSR checks and a free irq,
        // the sequential divider, an idle debug port, block RAM except C1a
        int[][] depths = {{4, 8}, {8, 12}, {12, 16}, {8, 12}, {10, 14}, {12, 16}, {14, 18}};
        for (int k = 0; k < 7; k++) {
            var spec = Cores.ALL.get(k);
            String n = "tessera_" + spec.id().toLowerCase();
            t.put(n, new Target(n, () -> spec.build(16, null), depths[k][0], depths[k][1]));
        }
        // C8 (prediction, C5's depths) and C5/C6 before the multiply was split
        t.put("tessera_c8", new Target("tessera_c8", () -> Cores.get("C8").build(16, null), 12, 16));
        t.put("tessera_c5_v1", new Target("tessera_c5_v1", () -> Cores.get("C5-v1").build(16, null), 12, 16));
        t.put("tessera_c6_v1", new Target("tessera_c6_v1", () -> Cores.get("C6-v1").build(16, null), 14, 18));
        // where the multiply finishes (Cores.MUL_VARIANTS); a stage of its own adds two cycles of depth
        // instruction-check depth: C5's 12 and C6's 14, plus 2 for a schedule with the MF stage
        var mulDepths = Map.ofEntries(Map.entry("C5-mmem", 12), Map.entry("C5-mwb", 12), Map.entry("C5-mmf", 14),
                Map.entry("C6-mwb", 14), Map.entry("C6-mmf", 16), Map.entry("C5-m2wb", 12), Map.entry("C5-m2mf", 14),
                Map.entry("C5-mfwb", 14), Map.entry("C6-m2wb", 14), Map.entry("C6-m2mf", 16), Map.entry("C6-mfwb", 16));
        for (var spec : Cores.MUL_VARIANTS) {
            String n = "tessera_" + spec.id().toLowerCase().replace('-', '_');
            int insn = mulDepths.get(spec.id());
            t.put(n, new Target(n, () -> spec.build(16, null), insn, insn + 4));
        }
        // dual issue (C9, C10): two RVFI channels, checked per channel; depths as C5's
        for (var spec : List.of(Cores.get("C9"), Cores.get("C10"))) {
            String n = "tessera_" + spec.id().toLowerCase();
            t.put(n, new Target(n, () -> spec.build(16, null), 12, 16));
        }
        // branch prediction on C5's pipeline (Cores.PREDICT_VARIANTS): the BTB starts in any state
        for (var spec : Cores.PREDICT_VARIANTS) {
            String n = "tessera_" + spec.id().toLowerCase().replace('-', '_');
            t.put(n, new Target(n, () -> spec.build(16, null), 12, 16));
        }
        // C7 with the window riscv-formal can cover (4 ROB entries, 3 reservation stations), and the
        // board's 16/8 window to a bound
        t.put("tessera_c7", new Target("tessera_c7", () -> new OooCore(16, null, Cores.common(true), 4, 3), 14, 14));
        t.put("tessera_c7_16x8", new Target("tessera_c7_16x8", () -> new OooCore(16, null, Cores.common(true)), 14, 14));
        return t;
    }

    static final String[] RVFI = {"valid", "order", "insn", "trap", "halt", "intr", "mode", "ixl", "rs1_addr", "rs2_addr",
            "rs1_rdata", "rs2_rdata", "rd_addr", "rd_wdata", "pc_rdata", "pc_wdata", "mem_addr", "mem_rmask", "mem_wmask",
            "mem_rdata", "mem_wdata"};
    /** Each RVFI signal's width per channel (the order of {@link #RVFI}). */
    static final int[] RVFI_WIDTHS = {1, 64, 32, 1, 1, 1, 2, 2, 5, 5, 32, 32, 5, 32, 32, 32, 32, 4, 4, 32, 32};

    public static void write(Path coresDir, Target t) throws IOException {
        Path dir = coresDir.resolve(t.name());
        Files.createDirectories(dir);
        // M instructions use riscv-formal's alternative operations (RISCV_FORMAL_ALTOPS)
        var e = HwDom.withAltops(() -> Tessera.elaborate(t.core()));
        RvCore core = e.top();
        Path sv = dir.resolve(t.name() + ".sv");
        String top = SvEmitter.write(e.circuit(), sv);
        var w = new StringBuilder("// Generated by Tessera RvfiExport\nmodule rvfi_wrapper (\n  input clock,\n  input reset,\n  `RVFI_OUTPUTS\n);\n");
        w.append("  (* keep *) wire halted;\n");
        if (core.irq != null) w.append("  (* keep *) `rvformal_rand_reg irq;   // interrupts at any time\n");
        w.append("  ").append(top).append(" uut (\n    .clock(clock),\n    .reset(reset),\n    .halted(halted)");
        // two channels (a dual-issue core): rvfi_* is channel 0 and rvfi1_* channel 1 of riscv-formal's NRET=2 buses
        boolean two = core.rvfi1 != null;
        for (int i = 0; i < RVFI.length; i++) {
            String f = RVFI[i];
            if (!two) {
                w.append(",\n    .rvfi_").append(f).append("(rvfi_").append(f).append(')');
                continue;
            }
            int wd = RVFI_WIDTHS[i];
            w.append(",\n    .rvfi_").append(f).append("(rvfi_").append(f).append("[0 +: ").append(wd).append("])");
            w.append(",\n    .rvfi1_").append(f).append("(rvfi_").append(f).append('[').append(wd).append(" +: ").append(wd).append("])");
        }
        if (core.irq != null) w.append(",\n    .irq(irq)");
        // the debug port (the FPGA host's) is idle
        if (core.dbgEn != null)
            w.append(",\n    .dbg_en(1'b0), .dbg_we(1'b0), .dbg_sel_dmem(1'b0), .dbg_addr('0), .dbg_wdata(32'h0), .dbg_rdata(), .dbg_reg_rdata()");
        if (core.hasRvfiCsrs)
            for (var c : RvCore.RVFI_CSRS)
                for (var f : new String[]{"rmask", "wmask", "rdata", "wdata"}) {
                    String n = "csr_" + c.name() + "_" + f;
                    int wd = c.wide() ? 64 : 32;
                    if (!two) {
                        w.append(",\n    .rvfi_").append(n).append("(rvfi_").append(n).append(')');
                        continue;
                    }
                    w.append(",\n    .rvfi_").append(n).append("(rvfi_").append(n).append("[0 +: ").append(wd).append("])");
                    w.append(",\n    .rvfi1_").append(n).append("(rvfi_").append(n).append('[').append(wd).append(" +: ").append(wd).append("])");
                }
        w.append("\n  );\nendmodule\n");
        Files.writeString(dir.resolve("wrapper.sv"), w);
        int d = t.insnDepth(), r = t.regDepth(), h = Math.max(1, r / 2);
        var cfg = new StringBuilder();
        cfg.append("[options]\nisa rv32im\nsolver yices\n").append(two ? "nret 2\n" : "").append("\n[depth]\n");
        cfg.append("insn            ").append(d).append('\n');
        for (var c : new String[]{"reg", "pc_fwd", "pc_bwd", "causal"})
            cfg.append(String.format("%-10s %d    %d%n", c, h, r));
        cfg.append(String.format("unique     1  %d %d%n", h, r));
        cfg.append("ill             ").append(d).append('\n');
        cfg.append("cover      1    ").append(d).append('\n');
        if (core.hasRvfiCsrs) {
            // CSR instruction semantics, illegal CSRs trap, the counters count up
            cfg.append("csrw            ").append(d).append('\n');
            cfg.append("csr_ill         ").append(d).append('\n');
            cfg.append("csrc_upcnt 1    ").append(d).append('\n');
            cfg.append("\n[csrs]\n");
            for (var c : RvCore.RVFI_CSRS) cfg.append(c.name()).append(c.wide() ? "    upcnt" : "").append('\n');
            cfg.append("\n[illegal_csrs]\nfff     msu     rw\n7c0     m       rw\nf11     m       w\n");
        }
        cfg.append("""

                [defines]
                `define RISCV_FORMAL_ALIGNED_MEM
                `define RISCV_FORMAL_ALTOPS

                [verilog-files]
                @basedir@/cores/@core@/wrapper.sv
                @basedir@/cores/@core@/@core@.sv

                [cover]
                always @* if (!reset) cover (channel[0].cnt_insns == 2);
                """);
        Files.writeString(dir.resolve("checks.cfg"), cfg);
    }

    /** {@code RvfiExport <cores dir> [target ...]}: every target, or only the named ones. */
    public static void main(String[] args) throws IOException {
        Path cores = Path.of(args.length > 0 ? args[0] : "build/riscv-formal/cores");
        var only = java.util.Arrays.asList(args).subList(Math.min(1, args.length), args.length);
        for (var t : targets().values()) {
            if (!only.isEmpty() && !only.contains(t.name())) continue;
            write(cores, t);
            System.out.println("wrote " + cores.resolve(t.name()));
        }
    }
}
