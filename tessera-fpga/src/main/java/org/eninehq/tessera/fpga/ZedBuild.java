package org.eninehq.tessera.fpga;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Supplier;
import org.eninehq.tessera.core.Tessera;
import org.eninehq.tessera.emit.SvEmitter;
import org.eninehq.tessera.riscv.OooCore;
import org.eninehq.tessera.riscv.RvCore;
import org.eninehq.tessera.riscv.ScheduledCore;

/**
 * Writes the SystemVerilog for a ZedBoard build: {@code ZedBuild <core> <outdir>}. The top module
 * is always {@code tessera_soc} (an AXI-Lite slave, see {@link RvSoc}); {@code vivado/zed_soc.tcl}
 * puts it next to the Zynq's ARM.
 */
public final class ZedBuild {
    private ZedBuild() {}

    /**
     * Words of instruction and data memory on the board: 64 KiB of block RAM each, except the
     * single-cycle core with LUT RAM (C1a), whose asynchronous memories get 16 KiB each.
     */
    public static int memWords(String kind) {
        return kind.equalsIgnoreCase("C1a") ? 4096 : Designs.MEM_WORDS;
    }

    /** The cores that can go on the board, with the ID register value the ARM reads back. */
    public static Supplier<RvCore> core(String kind) {
        var cfg = RvCore.Config.fpga();
        // the validation cores and their variants (C5-m2wb, C5-btb16, ...)
        if (kind.matches("(?i)C\\d.*")) {
            var spec = org.eninehq.tessera.riscv.Cores.get(kind);
            int words = memWords(kind);
            return () -> spec.build(words, null);
        }
        return switch (kind) {
            case "bram5" -> () -> new ScheduledCore(Designs.MEM_WORDS, null, ScheduledCore.FIVE_STAGE, cfg);
            case "bram3" -> () -> new ScheduledCore(Designs.MEM_WORDS, null, ScheduledCore.THREE_STAGE_BRAM, cfg);
            case "ooo" -> () -> new OooCore(Designs.MEM_WORDS, null, new RvCore.Config(false, true, true));
            default -> throw new IllegalArgumentException("unknown core " + kind);
        };
    }

    /** Whether the core implements machine mode (the ISS must match). */
    public static boolean zicsr(String kind) {
        return kind.matches("(?i)C\\d[ab]?") || !kind.startsWith("ooo");
    }

    public static long id(String kind) {
        switch (kind.toUpperCase()) {
            case "C1A": return 0x7E55_C1A0L;
            case "C1B": return 0x7E55_C1B0L;
            case "C2": return 0x7E55_C200L;
            case "C3": return 0x7E55_C300L;
            case "C4": return 0x7E55_C400L;
            case "C5": return 0x7E55_C500L;
            case "C6": return 0x7E55_C600L;
            case "C7": return 0x7E55_C700L;
            case "C8": return 0x7E55_C800L;
            case "C9": return 0x7E55_C900L;
            case "C10": return 0x7E55_CA00L;
            default: break;
        }
        return switch (kind) {
            case "bram5" -> 0x7E55_0005L;
            case "bram3" -> 0x7E55_0003L;
            case "ooo" -> 0x7E55_000FL;
            default -> 0x7E55_0000L;
        };
    }

    public static void main(String[] args) throws Exception {
        String kind = args[0];
        Path out = Path.of(args[1]);
        Files.createDirectories(out);
        var e = Tessera.elaborate(() -> new RvSoc(core(kind), id(kind)));
        Files.writeString(out.resolve("tessera_soc.sv"), SvEmitter.emit(e));
        System.out.println("wrote " + out.resolve("tessera_soc.sv"));
    }
}
