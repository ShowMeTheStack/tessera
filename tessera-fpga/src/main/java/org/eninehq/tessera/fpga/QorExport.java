package org.eninehq.tessera.fpga;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.eninehq.tessera.core.Tessera;
import org.eninehq.tessera.emit.SvEmitter;
import org.eninehq.tessera.riscv.OooCore;
import org.eninehq.tessera.riscv.RvCore;
import org.eninehq.tessera.riscv.ScheduledCore;

/**
 * Writes the cores for an out-of-context quality-of-results comparison ({@code vivado/qor.tcl}):
 * {@code <dir>/<name>/core.sv} plus {@code top.v}, a wrapper whose only ports are the clock,
 * reset, {@code halted} and the debug port, so logic that only feeds RVFI is removed (as for
 * the baseline cores, which are wrapped without their RVFI ports).
 */
public final class QorExport {
    private QorExport() {}

    /** Classes every operation of the cores goes through (the hardware domain of the shared RV32IM model): not where it was written. */
    public static final java.util.List<String> LOC_SKIP = java.util.List.of("org.eninehq.tessera.riscv.HwDom", "org.eninehq.tessera.riscv.Dom");

    public static Map<String, Supplier<RvCore>> designs() {
        var m = new LinkedHashMap<String, Supplier<RvCore>>();
        // RV32IM, sequential divider, asynchronous 16-word memories (LUT RAM)
        var seq = new RvCore.Config(false, false, true, true);
        m.put("tessera_sched3", () -> new ScheduledCore(16, null, ScheduledCore.THREE_STAGE, seq));
        m.put("tessera_sched4", () -> new ScheduledCore(16, null, ScheduledCore.FOUR_STAGE, seq));
        m.put("tessera_sched5", () -> new ScheduledCore(16, null, ScheduledCore.FIVE_STAGE, seq));
        m.put("tessera_sched6", () -> new ScheduledCore(16, null, ScheduledCore.SIX_STAGE, seq));
        m.put("tessera_ooo", () -> new OooCore(16, null, new RvCore.Config(false, false, true)));
        // the ZedBoard cores: block RAM (1024 words each), Zicsr on the in-order one
        m.put("tessera_fpga5", () -> new ScheduledCore(1024, null, ScheduledCore.FIVE_STAGE, RvCore.Config.fpga()));
        m.put("tessera_ooo_bram", () -> new OooCore(1024, null, new RvCore.Config(false, true, true)));
        // the validation cores in their common configuration, with 1024-word memories (block RAM, LUT RAM for C1a)
        for (var spec : org.eninehq.tessera.riscv.Cores.ALL)
            m.put("core_" + spec.id().toLowerCase(), () -> spec.build(1024, null));
        // where the multiply finishes, on C5 and C6 (Cores.MUL_VARIANTS)
        for (var spec : java.util.stream.Stream.of(org.eninehq.tessera.riscv.Cores.MUL_VARIANTS, org.eninehq.tessera.riscv.Cores.PREDICT_VARIANTS,
                        org.eninehq.tessera.riscv.Cores.OOO_VARIANTS, org.eninehq.tessera.riscv.Cores.SYMMETRIC_VARIANTS, org.eninehq.tessera.riscv.Cores.LVT_VARIANTS)
                .flatMap(List::stream).toList())
            m.put("var_" + spec.id().toLowerCase().replace('-', '_'), () -> spec.build(1024, null));
        // parameter sweeps: every schedule with LUT RAM and block RAM; the out-of-order window
        Object[][] scheds = {{"3", ScheduledCore.THREE_STAGE, ScheduledCore.THREE_STAGE_BRAM}, {"4", ScheduledCore.FOUR_STAGE, ScheduledCore.FOUR_STAGE_BRAM},
                {"5", ScheduledCore.FIVE_STAGE, ScheduledCore.FIVE_STAGE}, {"6", ScheduledCore.SIX_STAGE, ScheduledCore.SIX_STAGE}};
        for (var sc : scheds) {
            m.put("sweep_s" + sc[0] + "_lut", () -> new ScheduledCore(1024, null, (org.eninehq.tessera.pipeline.Schedule) sc[1],
                    org.eninehq.tessera.riscv.Cores.common(false)));
            m.put("sweep_s" + sc[0] + "_bram", () -> new ScheduledCore(1024, null, (org.eninehq.tessera.pipeline.Schedule) sc[2],
                    org.eninehq.tessera.riscv.Cores.common(true)));
        }
        for (int rob : new int[]{4, 8, 16, 32})
            for (int rs : new int[]{3, 6, 8})
                if (rs <= rob) m.put("sweep_ooo_" + rob + "x" + rs, () -> new OooCore(1024, null, org.eninehq.tessera.riscv.Cores.common(true), rob, rs));
        return m;
    }

    /** {@code QorExport <dir> [--names]}: with {@code --names}, each core.sv gets its name table beside it (core.sv.names.tsv). */
    public static void main(String[] args) throws Exception {
        Path out = Path.of(args[0]);
        boolean names = java.util.Arrays.asList(args).contains("--names");
        for (var d : designs().entrySet()) {
            Path dir = out.resolve(d.getKey());
            Files.createDirectories(dir);
            // with --names, each operation's line too; the domain adapter every operation goes through is skipped
            var locs = new java.util.IdentityHashMap<org.eninehq.tessera.ir.Expr, org.eninehq.tessera.core.SourceLoc>();
            var e = names ? org.eninehq.tessera.core.OpLocations.record(locs, LOC_SKIP, () -> Tessera.elaborate(d.getValue()))
                    : Tessera.elaborate(d.getValue());
            String top = names ? SvEmitter.writeWithNames(e.circuit(), dir.resolve("core.sv"), locs) : SvEmitter.write(e.circuit(), dir.resolve("core.sv"));
            RvCore c = e.top();
            int aw = c.dbgAddr.width();
            var w = new StringBuilder("module top (input clock, input reset, output halted,\n");
            if (c.irq != null) w.append("  input irq,\n");
            w.append("  input dbg_en, input dbg_we, input dbg_sel_dmem, input [").append(aw - 1)
                    .append(":0] dbg_addr, input [31:0] dbg_wdata,\n  output [31:0] dbg_rdata, output [31:0] dbg_reg_rdata);\n");
            w.append("  ").append(top).append(" core (.clock(clock), .reset(reset), .halted(halted),");
            if (c.irq != null) w.append(" .irq(irq),");
            w.append("\n    .dbg_en(dbg_en), .dbg_we(dbg_we), .dbg_sel_dmem(dbg_sel_dmem), .dbg_addr(dbg_addr), .dbg_wdata(dbg_wdata),\n")
                    .append("    .dbg_rdata(dbg_rdata), .dbg_reg_rdata(dbg_reg_rdata));\nendmodule\n");
            Files.writeString(dir.resolve("top.v"), w);
            System.out.println("wrote " + dir);
        }
    }
}
