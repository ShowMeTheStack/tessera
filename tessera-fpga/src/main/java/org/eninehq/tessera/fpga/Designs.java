package org.eninehq.tessera.fpga;

/** Sizes shared by the FPGA builds and the board tests. */
public final class Designs {
    private Designs() {}

    /** Words of instruction and of data memory (64 KiB each: one AXI region each). */
    public static final int MEM_WORDS = 16384;
}
