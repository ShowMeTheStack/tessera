package org.eninehq.tessera.stdlib;

import static org.eninehq.tessera.core.Hw.mux;

import java.util.ArrayList;
import java.util.List;
import org.eninehq.tessera.core.Bool;
import org.eninehq.tessera.core.Hw;
import org.eninehq.tessera.core.Internal;
import org.eninehq.tessera.core.Mem;
import org.eninehq.tessera.core.TesseraException;
import org.eninehq.tessera.core.UInt;
import org.eninehq.tessera.ir.Expr;
import org.eninehq.tessera.ir.Ops;
import org.eninehq.tessera.ir.Signal;

/**
 * A register file with several write ports built from one-write RAMs, built inside the current module: a
 * live-value table (LVT). Each write port has its own bank, and each bank has one copy per read port (LUT RAM has one
 * asynchronous read port per copy), so every copy has a single write port and a single read port. A small table, in
 * registers, records for every address which write port wrote it last; each read selects that bank's copy.
 * <p>Same-cycle writes of one address: the later write port wins, as in a register array written in order.
 * <p>Reads ({@link #read}) and writes ({@link #write}, under the enclosing when conditions) are declared in any
 * order, also inside pipeline stages; {@link #close} then builds the banks. Reads return the value combinationally.
 */
public final class LiveValueTable {
    private record Port(UInt addr, UInt data, Bool en) {}

    private final String name;
    private final int count, width, addrWidth;
    private final boolean zeroReg;
    private final List<Port> writes = new ArrayList<>(), reads = new ArrayList<>();
    private boolean closed;
    /** After {@link #close}: banks.get(w).get(r) is write port w's copy for read port r; the table of last writers. */
    private final List<List<Mem<UInt>>> banks = new ArrayList<>();
    private Mem<UInt> table;

    /** {@code count} registers of {@code width} bits; with {@code zeroReg}, register 0 reads as zero and is never written. */
    public LiveValueTable(String name, int count, int width, boolean zeroReg) {
        this.name = name;
        this.count = count;
        this.width = width;
        this.zeroReg = zeroReg;
        this.addrWidth = Math.max(1, 32 - Integer.numberOfLeadingZeros(count - 1));
    }

    public int width() { return width; }

    public int addrWidth() { return addrWidth; }

    /** A wire of the current module with an unconditional default (0), so it may then be set under conditions. */
    private UInt wire(String n, int w) {
        Signal s = Internal.newWire(n, w, false);
        Internal.assignRaw(s, Ops.lit(0, w));
        return Internal.wrap(UInt.w(w), new Expr.Ref(s), s, -1, null);
    }

    private Bool flag(String n) {
        Signal s = Internal.newWire(n, 1, false);
        Internal.assignRaw(s, Ops.FALSE);
        return Internal.wrap(Bool.TYPE, new Expr.Ref(s), s, -1, null);
    }

    private void open(String what) {
        if (closed) throw TesseraException.here("live-value table '" + name + "' is already built; " + what + " must come before close()");
    }

    /** A combinational read port. */
    public UInt read(UInt addr) {
        open("a read");
        int k = reads.size();
        UInt a = wire(name + "_r" + k + "_addr", addrWidth);
        a.set(addr);
        UInt d = wire(name + "_r" + k + "_data", width);
        reads.add(new Port(a, d, null));
        return d;
    }

    /** A write port: {@code data} to {@code addr} on the next clock edge, under the enclosing when conditions. */
    public void write(UInt addr, UInt data) {
        open("a write");
        int k = writes.size();
        UInt a = wire(name + "_w" + k + "_addr", addrWidth), d = wire(name + "_w" + k + "_data", width);
        Bool en = flag(name + "_w" + k + "_en");
        a.set(addr);
        d.set(data);
        en.set(true);
        writes.add(new Port(a, d, en));
    }

    /** Build the banks and the table (once every read and write port is declared). */
    public void close() {
        open("close()");
        closed = true;
        if (writes.isEmpty() || reads.isEmpty()) throw TesseraException.here("live-value table '" + name + "' needs a read and a write port");
        int sel = Math.max(1, 32 - Integer.numberOfLeadingZeros(writes.size() - 1));
        if (writes.size() > 1) {
            // the table: one entry per register, written by every port in order (the last one wins), in registers
            table = Hw.memory(name + "_lvt", UInt.w(sel), count).asRegisters();
            for (int w = 0; w < writes.size(); w++) {
                var p = writes.get(w);
                table.write(p.addr(), UInt.lit(w, sel), enable(p));
            }
        }
        for (int w = 0; w < writes.size(); w++) {
            var p = writes.get(w);
            var copies = new ArrayList<Mem<UInt>>();
            for (int r = 0; r < reads.size(); r++) {
                Mem<UInt> m = Hw.memory(name + "_b" + w + "_r" + r, UInt.w(width), count);
                m.write(p.addr(), p.data(), enable(p));
                copies.add(m);
            }
            banks.add(copies);
        }
        for (int r = 0; r < reads.size(); r++) {
            var p = reads.get(r);
            UInt v = banks.getFirst().get(r).read(p.addr());
            if (table != null) {
                UInt last = table.read(p.addr());
                for (int w = 1; w < writes.size(); w++) v = mux(last.eq(w), banks.get(w).get(r).read(p.addr()), v);
            }
            if (zeroReg) v = mux(p.addr().eq(0), UInt.lit(0, width), v);
            p.data().set(v);
        }
    }

    private Bool enable(Port p) {
        return zeroReg ? p.en().and(p.addr().ne(0)) : p.en();
    }

    /** After {@link #close}: write port {@code w}'s copy for read port {@code r} (for tests and proofs). */
    public Mem<UInt> copy(int w, int r) { return banks.get(w).get(r); }

    /** After {@link #close}: the table of last writers (null with one write port). */
    public Mem<UInt> table() { return table; }

    public int readPorts() { return reads.size(); }

    public int writePorts() { return writes.size(); }

    /** Every memory it is built from (the banks' copies, then the table), for tests that clear the state. */
    public List<Mem<UInt>> memories() {
        var out = new ArrayList<Mem<UInt>>();
        banks.forEach(out::addAll);
        if (table != null) out.add(table);
        return out;
    }
}
