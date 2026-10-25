package org.eninehq.tessera.fpga;

/** What the ARM does with an {@link RvSoc}: load a program, run it to the halt, read the results. */
public final class SocHost {
    public static final long CTRL = 0x00, STATUS = 0x04, CYCLES = 0x08, RETIRED = 0x0C, ID = 0x10, LAST_PC = 0x14,
            REGS = 0x80, IMEM = 0x10000, DMEM = 0x20000;

    public record Run(boolean halted, long cycles, long retired, long lastPc) {}

    private final SocBus bus;

    public SocHost(SocBus bus) {
        this.bus = bus;
    }

    public SocBus bus() {
        return bus;
    }

    public long id() {
        return bus.read(ID);
    }

    /** Stop the core and copy {@code image} into both memories (they are separate, Harvard). */
    public void load(int[] image) {
        bus.write(CTRL, 0);
        bus.writeBlock(IMEM, image);
        bus.writeBlock(DMEM, image);
    }

    /** Zero x1..x31 (the register file keeps its contents across runs) by running a small program. */
    public void clearRegisters() {
        int[] p = new int[32];
        for (int r = 1; r < 32; r++) p[r - 1] = r << 7 | 0x13;   // addi xr, x0, 0
        p[31] = 0x00100073;                                       // ebreak
        bus.write(CTRL, 0);
        bus.writeBlock(IMEM, p);
        if (!run(1000).halted()) throw new IllegalStateException("register-clearing program did not halt");
    }

    /** Clear {@code words} words of data memory starting at {@code fromWord}. */
    public void clearData(int fromWord, int words) {
        bus.writeBlock(DMEM + 4L * fromWord, new int[words]);
    }

    /** Start the core and wait for it to halt (EBREAK, or a trap without Zicsr); leaves it stopped. */
    public Run run(int maxPolls) {
        bus.write(CTRL, 0);
        bus.write(CTRL, 1);
        boolean halted = bus.waitFor(STATUS, 1, maxPolls);
        var r = new Run(halted, bus.read(CYCLES), bus.read(RETIRED), bus.read(LAST_PC));
        bus.write(CTRL, 0);
        return r;
    }

    /** x0..x31 (read while stopped). */
    public int[] regs() {
        long[] v = bus.readBlock(REGS, 32);
        int[] out = new int[32];
        for (int i = 1; i < 32; i++) out[i] = (int) v[i];
        return out;
    }

    public int[] data(int fromWord, int words) {
        long[] v = bus.readBlock(DMEM + 4L * fromWord, words);
        int[] out = new int[words];
        for (int i = 0; i < words; i++) out[i] = (int) v[i];
        return out;
    }
}
