package org.eninehq.tessera.examples;

import org.eninehq.tessera.core.Module;

/** Factories for the examples, for {@code tessera <cmd> --top org.eninehq.tessera.examples.Designs::blinky}. */
public final class Designs {
    private Designs() {}

    public static Module counter() { return new Counter(8); }
    public static Module blinky() { return new Blinky(24); }
    public static Module alu() { return new Alu(32); }
    public static Module uartTx() { return new UartTx(868); }
}
