package org.eninehq.tessera.ir;

import java.util.ArrayList;
import java.util.List;
import org.eninehq.tessera.core.SourceLoc;

/** A memory array with synchronous write ports and asynchronous read expressions. */
public final class MemDef {

    /** A synchronous write port: on the rising clock edge, if {@code en}, mem[addr] := data. */
    public static final class Write {
        private final Expr addr, data, en;
        private final SourceLoc loc;
        private final ClockDomainDef domain;

        public Write(Expr addr, Expr data, Expr en, SourceLoc loc, ClockDomainDef domain) {
            this.addr = addr;
            this.data = data;
            this.en = en;
            this.loc = loc;
            this.domain = domain;
        }

        public ClockDomainDef domain() { return domain; }

        public Expr addr() { return addr; }
        public Expr data() { return data; }
        public Expr en() { return en; }
        public SourceLoc loc() { return loc; }
    }

    private String name;
    private final int width, depth, addrWidth;
    private final boolean signed;
    private final ModuleDef owner;
    private final SourceLoc loc;
    private final List<Write> writes = new ArrayList<>();
    private long[] init;
    private boolean cdcSafe;

    public MemDef(String name, int width, boolean signed, int depth, ModuleDef owner, SourceLoc loc) {
        this.name = name;
        this.width = width;
        this.signed = signed;
        this.depth = depth;
        this.addrWidth = Math.max(1, 64 - Long.numberOfLeadingZeros(depth - 1L));
        this.owner = owner;
        this.loc = loc;
    }

    public String name() { return name; }
    public int width() { return width; }
    public boolean signed() { return signed; }
    public int depth() { return depth; }
    public int addrWidth() { return addrWidth; }
    public ModuleDef owner() { return owner; }
    public SourceLoc loc() { return loc; }
    public List<Write> writes() { return writes; }
    public long[] init() { return init; }

    public void rename(String n) { this.name = n; }
    public void setInit(long[] init) { this.init = init; }

    /** True when reads in other clock domains are made safe by a protocol (an asynchronous FIFO). */
    private boolean registers;

    /** Built from registers, not a RAM (a register file with several write ports). */
    public boolean registers() { return registers; }
    public void markRegisters() { this.registers = true; }

    public boolean cdcSafe() { return cdcSafe; }
    public void markCdcSafe() { this.cdcSafe = true; }
}
