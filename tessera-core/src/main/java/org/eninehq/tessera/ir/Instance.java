package org.eninehq.tessera.ir;

import java.util.LinkedHashMap;
import java.util.Map;
import org.eninehq.tessera.core.SourceLoc;

/** An instance of a child module inside a parent module. */
public final class Instance {
    private String name;
    private final ModuleDef def;
    private final ModuleDef parent;
    private final SourceLoc loc;
    private final Map<Signal, Expr> inputs = new LinkedHashMap<>();

    public Instance(String name, ModuleDef def, ModuleDef parent, SourceLoc loc) {
        this.name = name;
        this.def = def;
        this.parent = parent;
        this.loc = loc;
    }

    public String name() { return name; }
    public ModuleDef def() { return def; }
    public ModuleDef parent() { return parent; }
    public SourceLoc loc() { return loc; }

    /** Child input port to driving expression (in the parent's scope). */
    public Map<Signal, Expr> inputs() { return inputs; }

    public void rename(String n) { this.name = n; }

    @Override public String toString() { return parent.name() + "." + name; }
}
