package org.eninehq.tessera.studio;

import java.util.TreeMap;
import org.eninehq.tessera.core.Tessera;
import org.junit.jupiter.api.Test;

class CountProbe {
    @Test
    void count() {
        for (var spec : org.eninehq.tessera.riscv.Cores.ALL) {
            var e = Tessera.elaborate(() -> spec.build(1024, null));
            var m = e.circuit().top();
            var k = new TreeMap<String, Integer>();
            for (var s : m.allSignals()) k.merge(s.kind() + (s.autoNamed() ? "-auto" : ""), 1, Integer::sum);
            System.out.println("COUNT " + spec.id() + " " + m.name() + " instances=" + m.instances().size() + " mems=" + m.mems().size() + " " + k);
        }
    }
}
