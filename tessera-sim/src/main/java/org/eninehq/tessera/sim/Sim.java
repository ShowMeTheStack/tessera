package org.eninehq.tessera.sim;

import org.eninehq.tessera.core.Elaborated;
import org.eninehq.tessera.ir.Circuit;

/** Factory for simulators. */
public final class Sim {
    private Sim() {}

    public enum Backend { INTERPRETER, COMPILED }

    /** The backend used by default; override with {@code -Dtessera.sim=interpreter|compiled}. */
    public static Backend defaultBackend() {
        String p = System.getProperty("tessera.sim", "interpreter");
        return p.equalsIgnoreCase("compiled") ? Backend.COMPILED : Backend.INTERPRETER;
    }

    public static Simulator create(Elaborated<?> e) {
        return create(e.circuit(), defaultBackend());
    }

    public static Simulator create(Elaborated<?> e, Backend b) {
        return create(e.circuit(), b);
    }

    public static Simulator create(Circuit c, Backend b) {
        var net = Netlist.build(c);
        return switch (b) {
            case INTERPRETER -> new Interpreter(net);
            case COMPILED -> new CompiledSim(net);
        };
    }
}
