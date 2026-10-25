package org.eninehq.tessera.ir;

import java.util.ArrayList;
import java.util.List;

/** The elaborated definition of one module instance. Each instance has its own definition. */
public final class ModuleDef {
    private final String name;
    private final List<Signal> ports = new ArrayList<>();
    private final List<Signal> signals = new ArrayList<>();
    private final List<MemDef> mems = new ArrayList<>();
    private final List<Instance> instances = new ArrayList<>();
    private Instance instantiatedAs;
    private Signal clock, reset;
    private ClockDomainDef defaultDomain;
    private final List<ClockDomainDef> domains = new ArrayList<>();
    private final List<Assertion> assertions = new ArrayList<>();

    public ModuleDef(String name) {
        this.name = name;
    }

    public String name() { return name; }

    /** Ports in declaration order (clock and reset first, when present). */
    public List<Signal> ports() { return ports; }

    /** Non-port signals (wires, registers, nodes). */
    public List<Signal> signals() { return signals; }

    public List<MemDef> mems() { return mems; }
    public List<Instance> instances() { return instances; }
    public Instance instantiatedAs() { return instantiatedAs; }
    public Signal clock() { return clock; }
    public Signal reset() { return reset; }

    public void setInstantiatedAs(Instance i) { this.instantiatedAs = i; }
    public void setClockAndReset(Signal clock, Signal reset) {
        this.clock = clock;
        this.reset = reset;
    }

    /** The implicit domain (clock and reset ports), or null when the module has no state. */
    public ClockDomainDef defaultDomain() { return defaultDomain; }
    public void setDefaultDomain(ClockDomainDef d) { this.defaultDomain = d; }

    /** All clock domains declared in this module, the implicit one first when present. */
    public List<ClockDomainDef> domains() { return domains; }

    /** Formal properties (assert/assume/cover) of this module. */
    public List<Assertion> assertions() { return assertions; }

    public Iterable<Signal> allSignals() {
        var all = new ArrayList<Signal>(ports.size() + signals.size());
        all.addAll(ports);
        all.addAll(signals);
        return all;
    }

    @Override public String toString() { return name; }
}
