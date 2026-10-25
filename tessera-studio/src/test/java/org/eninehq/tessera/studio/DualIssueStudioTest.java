package org.eninehq.tessera.studio;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import org.eninehq.tessera.core.Module;
import org.eninehq.tessera.core.Tessera;
import org.eninehq.tessera.riscv.Benchmarks;
import org.eninehq.tessera.riscv.Cores;
import org.junit.jupiter.api.Test;

/** The dual-issue core opens in the Studio like the other cores, runs, and retires on both RVFI channels. */
class DualIssueStudioTest {
    @Test
    @SuppressWarnings("unchecked")
    void c9OpensAndRetiresInBothLanes() {
        var s = new Session(new Elaborator(List.of(), 1000));
        var e = (org.eninehq.tessera.core.Elaborated<Module>) (org.eninehq.tessera.core.Elaborated<?>)
                Tessera.elaborate(() -> Cores.get("C9").build(4096, Benchmarks.loop(1000)));
        var r = s.loadDesign(e, "C9");
        assertTrue(!r.containsKey("error"), r.toString());
        s.command(Map.of("cmd", "pause"));
        s.command(Map.of("cmd", "step", "n", 400));
        for (String port : List.of("rvfi_valid", "rvfi1_valid")) {
            var w = (Map<String, Object>) ((Map<String, Object>) s.waves(Map.of("paths", List.of(port), "from", 0, "to", 400, "columns", 400))
                    .get("waves")).get(port);
            var changes = (List<List<Object>>) w.get("changes");
            boolean one = changes.stream().anyMatch(ch -> new BigInteger(String.valueOf(ch.get(1)), 16).signum() != 0);
            assertTrue(one, port + " never 1 in 400 cycles: " + changes);
        }
    }
}
