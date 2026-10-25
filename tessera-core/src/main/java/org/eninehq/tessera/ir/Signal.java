package org.eninehq.tessera.ir;

import java.math.BigInteger;
import org.eninehq.tessera.core.SourceLoc;

/** A named signal of a module: a port, wire, register or named node. Identity semantics. */
public final class Signal {
    public enum Kind { INPUT, OUTPUT, WIRE, REG, NODE }

    private final Kind kind;
    private final int width;
    private final boolean signed;
    private final ModuleDef owner;
    private final SourceLoc loc;
    private String name;
    private boolean autoNamed;
    private Expr driver;
    private BigInteger resetValue;
    private boolean clockOrReset;
    private ClockDomainDef domain;
    private boolean cdcSync;
    private TypeInfo typeInfo;

    /**
     * What the signal is on the Java side, for waveform viewers and tools: the Java type name,
     * enum literal names (by encoding), and the bundle field it came from.
     */
    public record TypeInfo(String javaType, java.util.List<String> enumLiterals, String bundle, String field) {
        public TypeInfo {
            enumLiterals = enumLiterals == null ? java.util.List.of() : java.util.List.copyOf(enumLiterals);
        }
    }

    public Signal(Kind kind, String name, boolean autoNamed, int width, boolean signed, ModuleDef owner, SourceLoc loc) {
        this.kind = kind;
        this.name = name;
        this.autoNamed = autoNamed;
        this.width = width;
        this.signed = signed;
        this.owner = owner;
        this.loc = loc;
    }

    public Kind kind() { return kind; }
    public String name() { return name; }
    public boolean autoNamed() { return autoNamed; }
    public int width() { return width; }
    public boolean signed() { return signed; }
    public ModuleDef owner() { return owner; }
    public SourceLoc loc() { return loc; }

    /** Combinational value for OUTPUT/WIRE/NODE; next-state value for REG; null for INPUT. */
    public Expr driver() { return driver; }

    /** Reset value for registers with a synchronous reset, or null. */
    public BigInteger resetValue() { return resetValue; }

    /** True for clock and reset ports (implicit or of a named clock domain). */
    public boolean isClockOrReset() { return clockOrReset; }

    /** The clock domain of a register, or null for other signals. */
    public ClockDomainDef domain() { return domain; }

    /** True for the first register of a declared synchronizer: it may sample another clock domain. */
    public boolean isCdcSync() { return cdcSync; }

    public void setDomain(ClockDomainDef d) { this.domain = d; }

    /** Java-side type information, or null. */
    public TypeInfo typeInfo() { return typeInfo; }
    public void setTypeInfo(TypeInfo t) { this.typeInfo = t; }
    public void markCdcSync() { this.cdcSync = true; }

    public boolean isPort() { return kind == Kind.INPUT || kind == Kind.OUTPUT; }

    public void rename(String newName, boolean auto) {
        this.name = newName;
        this.autoNamed = auto;
    }

    public void setDriver(Expr driver) { this.driver = driver; }
    public void setResetValue(BigInteger v) { this.resetValue = v; }
    public void markClockOrReset() { this.clockOrReset = true; }

    @Override
    public String toString() {
        return owner.name() + "." + name;
    }
}
