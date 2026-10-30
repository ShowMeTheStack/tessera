package org.eninehq.tessera.fpga;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import org.eninehq.tessera.core.Tessera;
import org.eninehq.tessera.emit.SvEmitter;
import org.eninehq.tessera.riscv.Cores;
import org.eninehq.tessera.riscv.RvCore;

/**
 * The out-of-context designs of the generated cores ({@link Cores#GENERATED}) and their hand-written
 * references, in {@link QorExport}'s layout ({@code <dir>/<name>/core.sv, top.v}), for vivado/qor.tcl.
 * {@code GenQorExport <dir>}.
 */
public final class GenQorExport {
    private GenQorExport() {}

    public static Map<String, Supplier<RvCore>> designs() {
        var m = new LinkedHashMap<String, Supplier<RvCore>>();
        for (var spec : Cores.GENERATED) m.put("gen_" + spec.id().toLowerCase().replace('-', '_'), () -> spec.build(1024, null));
        // the references, as QorExport builds them
        m.put("core_c7", () -> Cores.get("C7").build(1024, null));
        m.put("sweep_ooo_4x3", () -> new org.eninehq.tessera.riscv.OooCore(1024, null, Cores.common(true), 4, 3));
        m.put("core_c5", () -> Cores.get("C5").build(1024, null));
        return m;
    }

    public static void main(String[] args) throws Exception {
        Path out = Path.of(args[0]);
        var ref = QorExport.designs();
        for (var d : designs().entrySet()) {
            Path dir = out.resolve(d.getKey());
            Files.createDirectories(dir);
            var e = Tessera.elaborate(d.getValue());
            String top = SvEmitter.write(e.circuit(), dir.resolve("core.sv"));
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
            // a reference must be byte-identical to QorExport's design of the same name
            if (ref.containsKey(d.getKey()))
                System.out.println((SvEmitter.emit(Tessera.elaborate(ref.get(d.getKey()))).equals(Files.readString(dir.resolve("core.sv")))
                        ? "SAME-AS-QOREXPORT " : "DIFFERS-FROM-QOREXPORT ") + d.getKey());
            System.out.println("wrote " + dir);
        }
    }
}
