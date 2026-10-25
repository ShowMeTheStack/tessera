package org.eninehq.tessera.core;

import java.util.List;
import org.eninehq.tessera.ir.Expr;
import org.eninehq.tessera.ir.MemDef;
import org.eninehq.tessera.ir.Ops;
import org.eninehq.tessera.ir.Signal;

/** A memory of {@code depth} elements of type {@code T}. Writes are synchronous. */
public final class Mem<T extends Data> {
    final MemDef def;
    private final HwType<T> type;

    Mem(MemDef def, HwType<T> type) {
        this.def = def;
        this.type = type;
    }

    public int depth() { return def.depth(); }

    /** Address width: ceil(log2(depth)), at least 1. */
    public int addrWidth() { return def.addrWidth(); }

    private Expr addr(UInt a) {
        if (a.width() > def.addrWidth())
            throw TesseraException.here("address is " + a.width() + " bits but memory '" + def.name() + "' needs "
                    + def.addrWidth() + "; slice the address explicitly with .bits(hi, lo)",
                    new TesseraException.Fix("use the low " + def.addrWidth() + " address bits",
                            "\\.(read|readSync|write)\\((\\w+)", ".$1($2.bits(" + (def.addrWidth() - 1) + ", 0)"));
        return Ops.ext(Elab.read(a), def.addrWidth());
    }

    private void checkModule() {
        if (Elab.cur().def != def.owner())
            throw TesseraException.here("memory '" + def.name() + "' belongs to module " + def.owner().name());
    }

    /** Combinational (asynchronous) read. */
    public T read(UInt addr) {
        checkModule();
        return type.wrap(Elab.init(new Expr.MemRead(def, addr(addr))));
    }

    /** Synchronous read: the data appears one cycle after the address. */
    public T readSync(UInt addr) {
        return readSync(addr, Bool.of(true));
    }

    /** Synchronous read that only updates its output register when {@code en}. */
    public T readSync(UInt addr, Bool en) {
        checkModule();
        var b = Elab.cur();
        var s = b.newRegSignal(def.name() + "_rdata", true, def.width(), def.signed(), SourceLoc.here());
        b.def.signals().add(s);
        Expr read = new Expr.MemRead(def, addr(addr));
        b.addAssign(s, Ops.mux(Elab.read(en), read, new Expr.Ref(s)), SourceLoc.here());
        return type.wrap(Elab.init(new Expr.Ref(s)));
    }

    /** Write {@code data} to {@code addr} on the next clock edge (under the enclosing when conditions). */
    public void write(UInt addr, T data) {
        write(addr, data, Bool.of(true));
    }

    public void write(UInt addr, T data, Bool en) {
        checkModule();
        var b = Elab.cur();
        var dom = b.currentDomain();
        var loc = SourceLoc.here();
        Expr d = Elab.fit(Elab.read(data), def.width(), def.signed(), "memory '" + def.name() + "'", loc);
        Expr e = Ops.and(b.fullCondition(), Elab.read(en));
        def.writes().add(new MemDef.Write(addr(addr), d, e, loc, dom));
        b.effects++;
    }

    /**
     * Build this memory from registers rather than a RAM: needed when it has several write ports (FPGA RAMs have
     * one or two), as a dual-issue register file does. The SV is one block, writes in order, marked for registers.
     */
    public Mem<T> asRegisters() {
        def.markRegisters();
        return this;
    }

    /** Initial contents (for ROMs and simulation). */
    public Mem<T> init(long... values) {
        if (values.length > def.depth())
            throw TesseraException.here(values.length + " initial values for a memory of depth " + def.depth());
        def.setInit(values.clone());
        return this;
    }

    public Mem<T> init(List<Long> values) {
        return init(values.stream().mapToLong(Long::longValue).toArray());
    }

    /**
     * Declare that this memory is written in one clock domain and read in another under a
     * protocol that guarantees a word is stable while it is read (as in an asynchronous FIFO).
     * The CDC lint then ignores the read crossing.
     */
    public Mem<T> crossesClockDomains() {
        def.markCdcSafe();
        return this;
    }

    /** Internal: the IR memory. */
    public MemDef def() { return def; }
}
