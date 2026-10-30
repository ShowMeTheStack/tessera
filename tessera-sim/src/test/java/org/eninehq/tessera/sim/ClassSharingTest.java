package org.eninehq.tessera.sim;

import static org.eninehq.tessera.core.Hw.when;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.eninehq.tessera.core.Bool;
import org.eninehq.tessera.core.Module;
import org.eninehq.tessera.core.Tessera;
import org.eninehq.tessera.core.UInt;
import org.junit.jupiter.api.Test;

/** Simulators of one design share generated classes (unless turned off), and behave the same either way. */
class ClassSharingTest {
    static final class Counter extends Module {
        final Bool en = input("en", Bool.TYPE);
        final UInt count;

        Counter(int width) {
            count = output("count", UInt.w(width));
            UInt value = reg("value", UInt.w(width), 0);
            when(en, () -> value.set(value.addWrap(1)));
            count.set(value);
        }
    }

    private static long run(Simulator s) {
        s.reset(1);
        s.poke("en", 1);
        s.step(37);
        return s.peek("count");
    }

    @Test
    void sharedAndFreshAgree() {
        var e = Tessera.elaborate(() -> new Counter(5));
        long h0 = CompiledSim.sharedHits();
        long a, b, c;
        try (var s1 = Sim.create(e, Sim.Backend.COMPILED)) { a = run(s1); }
        try (var s2 = Sim.create(e, Sim.Backend.COMPILED)) { b = run(s2); }
        assertEquals(h0 + 1, CompiledSim.sharedHits(), "the second simulator reuses the first one's classes");
        boolean was = CompiledSim.shareClasses();
        CompiledSim.setShareClasses(false);
        try (var s3 = Sim.create(e, Sim.Backend.COMPILED)) {
            c = run(s3);
            assertEquals(h0 + 1, CompiledSim.sharedHits(), "no reuse with sharing off");
        } finally {
            CompiledSim.setShareClasses(was);
        }
        long ref;
        try (var s4 = Sim.create(e, Sim.Backend.INTERPRETER)) { ref = run(s4); }
        assertEquals(37 % 32, ref);
        assertEquals(ref, a);
        assertEquals(ref, b);
        assertEquals(ref, c);
    }

    @Test
    void cacheIsBounded() {
        var cache = new CodeCache<Integer, Object>();
        for (int i = 0; i < 3 * CodeCache.MAX; i++) cache.put(i, new Object());
        assertEquals(CodeCache.MAX, cache.size());
    }
}
