package org.eninehq.tessera.riscv;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.eninehq.tessera.core.Elaborated;
import org.eninehq.tessera.core.Tessera;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The dual-issue cores with a live-value-table register file (C9-lvt, C10-lvt): the dual-issue pairing
 * programs and the memory-pair programs against the ISS (retirements, memory writes, CSRs, registers at halt), and the
 * shape of the register file (two write ports, five read ports: four for the lanes and the debug read).
 */
class LvtCoreTest {
    static final Map<String, Elaborated<? extends RvCore>> BUILT = new ConcurrentHashMap<>();

    static Elaborated<? extends RvCore> core(String id) {
        return BUILT.computeIfAbsent(id, k -> Tessera.elaborate(() -> Cores.get(k).build(RiscvTests.MEM_WORDS, null)));
    }

    static List<Arguments> cases() {
        var out = new ArrayList<Arguments>();
        for (var c : List.of("C9-lvt", "C10-lvt")) for (long seed = 0; seed < 60; seed++) {
            out.add(Arguments.of(c, "pairs", seed));
            out.add(Arguments.of(c, "mem", seed));
        }
        return out;
    }

    @ParameterizedTest(name = "{0} {1} seed {2}")
    @MethodSource("cases")
    void matchesIss(String id, String kind, long seed) {
        var e = core(id);
        int[] img = kind.equals("mem") ? RandomProgram.memPairs(seed, 300) : RandomProgram.pairs(seed, 300);
        try (var sim = CoreRunner.sim(e)) {
            var r = CoreRunner.run(e, sim, img, 200_000, true);
            assertTrue(r.retired() > 20, "too short: " + r.retired());
        }
    }

    @Test
    void registerFileIsALiveValueTable() {
        RvCore c = core("C9-lvt").top();
        assertNull(c.regs);
        assertEquals(2, c.lvtRegs.writePorts());
        assertEquals(5, c.lvtRegs.readPorts());
        assertEquals(11, c.lvtRegs.memories().size());   // 2 banks x 5 copies, and the table
        assertTrue(c.lvtRegs.table().def().registers(), "the table is built from registers");
        for (var m : c.lvtRegs.memories())
            if (m != c.lvtRegs.table()) assertEquals(1, m.def().writes().size(), m.def().name() + " has one write port");
    }
}
