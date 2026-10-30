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

    private void checkNotPorted(String what) {
        if (!def.ports().isEmpty())
            throw TesseraException.here("memory '" + def.name() + "' is a true dual-port RAM (port(...)); " + what
                    + " would leave the block-RAM template: use its ports only");
    }

    /** Combinational (asynchronous) read. */
    public T read(UInt addr) {
        checkModule();
        checkNotPorted("an asynchronous read");
        return type.wrap(Elab.init(new Expr.MemRead(def, addr(addr))));
    }

    /** Synchronous read: the data appears one cycle after the address. */
    public T readSync(UInt addr) {
        return readSync(addr, Bool.of(true));
    }

    /** Synchronous read that only updates its output register when {@code en}. */
    public T readSync(UInt addr, Bool en) {
        checkModule();
        checkNotPorted("another synchronous read");
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
        checkNotPorted("another write");
        var b = Elab.cur();
        var dom = b.currentDomain();
        var loc = SourceLoc.here();
        Expr d = Elab.fit(Elab.read(data), def.width(), def.signed(), "memory '" + def.name() + "'", loc);
        Expr e = Ops.and(b.fullCondition(), Elab.read(en));
        def.writes().add(new MemDef.Write(addr(addr), d, e, loc, dom));
        b.effects++;
    }

    /**
     * One port of a true dual-port RAM (at most two per memory): on the clock edge, when {@code en}, the word at
     * {@code addr} is read into the returned value (read-first: the word before this edge's writes) and, when
     * {@code we} too, {@code wdata} is written there. A memory with ports is accessed only through them.
     * <p>Two ports writing one address in the same cycle is undefined in the RAM (FPGA block RAMs corrupt the word),
     * so the second port adds an assertion that it never happens: simulators stop with an error on it and formal
     * back ends check it. A read on one port of the word the other port writes returns the old word.
     */
    public T port(UInt addr, Bool en, Bool we, T wdata) {
        checkModule();
        if (!def.writes().isEmpty() && def.ports().isEmpty())
            throw TesseraException.here("memory '" + def.name() + "' already has a plain write; a true dual-port RAM is accessed only through port(...)");
        if (def.ports().size() == 2) throw TesseraException.here("memory '" + def.name() + "' already has two ports");
        if (def.registers()) throw TesseraException.here("memory '" + def.name() + "' is built from registers; ports are for block RAM");
        var b = Elab.cur();
        var dom = b.currentDomain();
        if (!def.ports().isEmpty() && def.ports().getFirst().write().domain() != dom)
            throw TesseraException.here("memory '" + def.name() + "': both ports must be in one clock domain");
        var loc = SourceLoc.here();
        Expr a = addr(addr);
        Expr e = Ops.and(b.fullCondition(), Elab.read(en));
        Expr w = Elab.read(we);
        Expr d = Elab.fit(Elab.read(wdata), def.width(), def.signed(), "memory '" + def.name() + "'", loc);
        var s = b.newRegSignal(def.name() + "_q" + (char) ('a' + def.ports().size()), true, def.width(), def.signed(), loc);
        b.def.signals().add(s);
        // read-first: the register samples the word before this edge's writes, as readSync does
        b.addAssign(s, Ops.mux(e, new Expr.MemRead(def, a), new Expr.Ref(s)), loc);
        var write = new MemDef.Write(a, d, Ops.and(e, w), loc, dom);
        def.writes().add(write);
        def.ports().add(new MemDef.Port(a, e, w, d, s, write));
        b.effects++;
        if (def.ports().size() == 2) {
            var p0 = def.ports().getFirst();
            Expr collide = Ops.and(Ops.and(p0.write().en(), write.en()),
                    Ops.op(org.eninehq.tessera.ir.OpKind.EQ, 1, false, p0.addr(), a));
            Expr rst = new Expr.Ref(dom.reset());
            Expr notReset = dom.resetKind().activeHigh() ? Ops.not(rst) : rst;
            b.def.assertions().add(new org.eninehq.tessera.ir.Assertion(org.eninehq.tessera.ir.Assertion.Kind.ASSERT,
                    def.name() + "_ports_write_one_word", Ops.not(collide), notReset, dom, loc));
        }
        return type.wrap(Elab.init(new Expr.Ref(s)));
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
