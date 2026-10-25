package org.eninehq.tessera.babylon;

/**
 * Markers for plain-Java hardware. They do nothing when the method runs as Java; when it is
 * compiled with {@link JavaHw} they shape the hardware.
 */
public final class Hardware {
    private Hardware() {}

    /**
     * A pipeline stage boundary (Spade's {@code reg;}): values computed before it reach the
     * code after it through pipeline registers. Compile the method inside a {@code Pipeline}.
     */
    public static void stage() {}
}
