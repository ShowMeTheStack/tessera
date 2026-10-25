package org.eninehq.tessera.core;

import java.util.function.Supplier;
import org.eninehq.tessera.ir.Circuit;

/** Entry point: elaborate a design. */
public final class Tessera {
    private Tessera() {}

    /** Run the top module's constructor (and all children), then check the whole design. */
    public static <M extends Module> Elaborated<M> elaborate(Supplier<M> top) {
        var s = Elab.openSession();
        try {
            s.constructing = true;
            M m = top.get();
            if (m == null || s.stack.peek() != m.builder())
                throw TesseraException.here("elaborate(...) must be given a supplier that creates a new module");
            s.stack.pop();
            m.builder().finish();
            var circuit = new Circuit(m.builder().def);
            CombLoopCheck.check(circuit);
            CdcCheck.check(circuit);
            return new Elaborated<>(m, circuit);
        } finally {
            Elab.closeSession();
        }
    }
}
