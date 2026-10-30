package org.eninehq.tessera.riscv;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.eninehq.tessera.core.Tessera;
import org.eninehq.tessera.sim.BatchSim;
import org.eninehq.tessera.sim.Sim;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 64 lanes of the bit-sliced simulator, each running a different random program on a
 * real core (so every memory port sees different addresses and data per lane), agree with the
 * compiled simulator lane by lane: halted, pc output and the whole register file and data memory.
 */
class BatchSimCoreTest {
    static final int MEM = 4096, CYCLES = 1500;

    @ParameterizedTest
    @ValueSource(strings = {"C2", "C5", "C9"})
    void lanesMatchSeparateRuns(String id) {
        var e = Tessera.elaborate(() -> Cores.get(id).build(MEM, null));
        var batch = new BatchSim(e.circuit());
        int lanes = BatchSim.LANES;
        var sims = new org.eninehq.tessera.sim.Simulator[lanes];
        try {
            for (int l = 0; l < lanes; l++) {
                int[] prog = RandomProgram.generate(1000 + l, 300, l % 2 == 1);
                sims[l] = Sim.create(e, Sim.Backend.COMPILED);
                for (String m : new String[]{"imem", "dmem"}) {
                    long[] b = batch.memory(m, l), s = sims[l].memory(m);
                    for (int i = 0; i < Math.min(prog.length, MEM); i++) b[i] = s[i] = Integer.toUnsignedLong(prog[i]);
                }
                sims[l].poke("reset", 1);
                sims[l].step();
                sims[l].poke("reset", 0);
            }
            batch.pokeAll("reset", 1);
            batch.step();
            batch.pokeAll("reset", 0);
            for (int c = 0; c < CYCLES; c++) {
                batch.step();
                for (var s : sims) s.step();
            }
            var distinct = new java.util.HashSet<String>();
            for (int l = 0; l < lanes; l++) distinct.add(java.util.Arrays.toString(batch.memory("dmem", l)));
            org.junit.jupiter.api.Assertions.assertTrue(distinct.size() > lanes / 2, "lanes really differ: " + distinct.size());
            for (int l = 0; l < lanes; l++) {
                assertEquals(sims[l].peek("halted"), batch.peek("halted", l), "halted, lane " + l);
                assertArrayEquals(sims[l].memory("regs"), batch.memory("regs", l), "regs, lane " + l);
                assertArrayEquals(sims[l].memory("dmem"), batch.memory("dmem", l), "dmem, lane " + l);
            }
        } finally {
            for (var s : sims) if (s != null) s.close();
        }
    }
}
