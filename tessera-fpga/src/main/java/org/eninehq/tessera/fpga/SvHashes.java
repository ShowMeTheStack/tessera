package org.eninehq.tessera.fpga;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import org.eninehq.tessera.core.Tessera;
import org.eninehq.tessera.emit.SvEmitter;
import org.eninehq.tessera.riscv.Cores;
import org.eninehq.tessera.riscv.RvCore;

/**
 * SHA-256 of the emitted SystemVerilog of every design that experiments use: the out-of-context designs of
 * {@link QorExport}, the cores of {@link Cores#BEFORE}, and the ZedBoard system of each core in {@link Cores#ALL}.
 * {@code SvHashes <file>} writes one line per design ({@code <sha256>  <name>}); comparing two such files shows which
 * designs' RTL a change touched.
 */
public final class SvHashes {
    private SvHashes() {}

    public static void main(String[] args) throws Exception {
        Map<String, Supplier<String>> sv = new LinkedHashMap<>();
        for (var d : QorExport.designs().entrySet())
            sv.put("ooc/" + d.getKey(), () -> SvEmitter.emit(Tessera.elaborate(d.getValue())));
        for (var spec : Cores.BEFORE)
            sv.put("ooc/before_" + spec.id().toLowerCase().replace('-', '_'),
                    () -> SvEmitter.emit(Tessera.elaborate(() -> spec.build(1024, null))));
        for (var spec : Cores.ALL)
            sv.put("soc/" + spec.id(), () -> SvEmitter.emit(Tessera.elaborate(() -> new RvSoc(ZedBuild.core(spec.id()), ZedBuild.id(spec.id())))));
        // the symmetric-lane and LVT variants (their ZedBoard systems), after the other designs
        for (var spec : java.util.stream.Stream.of(Cores.SYMMETRIC_VARIANTS, Cores.LVT_VARIANTS).flatMap(java.util.List::stream).toList())
            sv.put("soc/" + spec.id(), () -> SvEmitter.emit(Tessera.elaborate(() -> new RvSoc(ZedBuild.core(spec.id()), ZedBuild.id(spec.id())))));
        var md = MessageDigest.getInstance("SHA-256");
        var out = new StringBuilder();
        for (var e : sv.entrySet()) {
            String hash = HexFormat.of().formatHex(md.digest(e.getValue().get().getBytes(StandardCharsets.UTF_8)));
            out.append(hash).append("  ").append(e.getKey()).append('\n');
            System.out.println(hash.substring(0, 12) + "  " + e.getKey());
        }
        Path file = Path.of(args[0]);
        Files.createDirectories(file.toAbsolutePath().getParent());
        Files.writeString(file, out);
    }
}
