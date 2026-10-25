package org.eninehq.tessera.examples;

import java.nio.file.Path;
import org.eninehq.tessera.core.Tessera;
import org.eninehq.tessera.emit.SvEmitter;

/** Writes the SystemVerilog of every example to {@code build/sv/}. */
public final class Emit {
    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args.length > 0 ? args[0] : "build/sv");
        SvEmitter.write(Tessera.elaborate(() -> new Counter(8)).circuit(), dir.resolve("Counter.sv"));
        SvEmitter.write(Tessera.elaborate(() -> new Blinky(24)).circuit(), dir.resolve("Blinky.sv"));
        SvEmitter.write(Tessera.elaborate(() -> new Alu(32)).circuit(), dir.resolve("Alu.sv"));
        SvEmitter.write(Tessera.elaborate(() -> new UartTx(868)).circuit(), dir.resolve("UartTx.sv"));
        System.out.println("wrote " + dir.toAbsolutePath());
    }
}
