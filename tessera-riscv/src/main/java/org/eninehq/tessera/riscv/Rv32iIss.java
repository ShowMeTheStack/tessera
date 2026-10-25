package org.eninehq.tessera.riscv;

/**
 * The golden RV32IM instruction-set simulator: the {@link Rv32i} (and, optionally,
 * {@link Zicsr}) semantics run with {@link SwDom}. Memory is word-addressed, little-endian,
 * starting at address 0; like the hardware cores, addresses wrap around the (power-of-two)
 * memory size.
 */
public final class Rv32iIss {
    private static final SwDom D = SwDom.INSTANCE;

    public final int[] x = new int[32];
    public final int[] mem;
    public final boolean zicsr;
    public Zicsr.Csrs<Integer> csrs = Zicsr.reset(D);
    public int pc;
    public boolean halted;
    public long retired;
    /** The external interrupt line (MEIP). */
    public boolean irq;

    public Rv32iIss(int memWords, int[] image) {
        this(memWords, image, false);
    }

    public Rv32iIss(int memWords, int[] image, boolean zicsr) {
        if (Integer.bitCount(memWords) != 1) throw new IllegalArgumentException("memWords must be a power of two");
        this.mem = new int[memWords];
        this.zicsr = zicsr;
        System.arraycopy(image, 0, mem, 0, Math.min(image.length, memWords));
    }

    private int index(int addr) {
        return (addr >>> 2) & (mem.length - 1);
    }

    /** Execute one instruction; returns what it retired, or null if halted. */
    public Retire step() {
        return step(false);
    }

    /**
     * Take the pending interrupt first (as the hardware did), then execute the handler's first
     * instruction. Used by co-simulation when the core reports {@code rvfi_intr}.
     */
    public Retire stepAfterInterrupt() {
        return step(true);
    }

    private Retire step(boolean takeInterrupt) {
        if (halted) return null;
        if (zicsr && takeInterrupt) {
            int inst = mem[index(pc)];
            var dec = Rv32i.decode(D, inst, true);
            var ex = Rv32i.execute(D, dec, pc, x[dec.rs1()], x[dec.rs2()]);
            var eff = Zicsr.step(D, dec, ex, inst, pc, x[dec.rs1()], csrs, irq, true);
            if (!eff.interrupt()) throw new IllegalStateException("the core took an interrupt the ISS cannot take at pc " + pc);
            csrs = eff.next();
            pc = eff.target();
        }
        int inst = mem[index(pc)];
        var dec = Rv32i.decode(D, inst, zicsr);
        var ex = Rv32i.execute(D, dec, pc, x[dec.rs1()], x[dec.rs2()]);
        int i = index(ex.memAddr());
        int rdValue = dec.mulDiv() ? Rv32i.mulDivFinish(D, Rv32i.mulDivStart(D, dec, x[dec.rs1()], x[dec.rs2()])) : ex.result();
        if (dec.load()) rdValue = Rv32i.loadValue(D, dec.funct3(), ex.memAddr(), mem[i]);
        boolean writes, store, halt;
        int next;
        boolean trap;
        if (zicsr) {
            // interrupts are only taken where the hardware reports them (stepAfterInterrupt)
            var eff = Zicsr.step(D, dec, ex, inst, pc, x[dec.rs1()], csrs, false, true);
            if (eff.isCsr()) rdValue = eff.csrValue();
            writes = eff.writesRd();
            store = dec.store() && eff.commits();
            halt = eff.halt();
            next = eff.redirect() ? eff.target() : ex.nextPc();
            trap = eff.exception();
            csrs = eff.next();
        } else {
            writes = ex.writesRd();
            store = dec.store() && !ex.trap();
            halt = ex.trap();
            next = ex.nextPc();
            trap = ex.trap();
        }
        if (store) mem[i] = Rv32i.storeMerge(D, dec.funct3(), ex.memAddr(), mem[i], x[dec.rs2()]);
        var r = new Retire(pc, inst, writes ? dec.rd() : 0, writes ? rdValue : 0);
        if (writes) x[dec.rd()] = rdValue;
        if (halt) halted = true;
        else pc = next;
        retired++;
        if (trap && !zicsr) halted = true;
        return r;
    }

    /** Run until halted or {@code maxSteps}; returns true if halted. */
    public boolean run(long maxSteps) {
        for (long i = 0; i < maxSteps && !halted; i++) step();
        return halted;
    }
}
