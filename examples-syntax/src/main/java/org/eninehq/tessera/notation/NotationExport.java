package org.eninehq.tessera.notation;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Supplier;
import org.eninehq.tessera.core.Module;
import org.eninehq.tessera.core.Tessera;
import org.eninehq.tessera.emit.SvEmitter;

/** Writes the Tessera-javac notation designs: {@code NotationExport <dir>} gives dir/<design>/syntax.sv. */
public final class NotationExport {
    private NotationExport() {}

    public static void main(String[] args) throws Exception {
        Map<String, Supplier<? extends Module>> designs = Map.of("alu", Alu::new, "immdecode", ImmDecode::new, "gcd", Gcd::new,
                "uarttx", UartTx::new, "muladd", MulAdd::new, "fifo", Fifo::new);
        for (var e : designs.entrySet()) {
            Path out = Path.of(args[0]).resolve(e.getKey()).resolve("syntax.sv");
            Files.createDirectories(out.getParent());
            SvEmitter.write(Tessera.elaborate(e.getValue()).circuit(), out);
            System.out.println(e.getKey() + " -> " + out);
        }
    }
}
