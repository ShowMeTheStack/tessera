package org.eninehq.tessera.studio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.eninehq.tessera.studio.CodeGenTest.D;
import org.junit.jupiter.api.Test;

/** A memory word written from the memory view: the design sees it, and time travel replays it. */
class MemWriteTest {
    @Test
    void aWrittenWordIsReadAndReplayed() {
        // a ROM read at addr; a word written by hand appears on q
        var d = new D("Rom").part("a", "in", "name", "addr", "width", 2)
                .part("m", "mem", "name", "rom", "width", 8, "depth", 4, "write", false, "init", "1 2 3 4")
                .part("o", "out", "name", "q", "width", 8)
                .net("n1", "a.y", "m.addr").net("n2", "m.rdata", "o.a");
        var s = new Session(new Elaborator(List.of(), 1000));
        assertEquals(true, s.load(d.doc).get("elaborated"));
        s.command(Map.of("cmd", "poke", "path", "addr", "value", "2"));
        s.command(Map.of("cmd", "step", "n", 3));
        assertEquals(3L, MultiClockTest.last(s, "q"));
        long c0 = ((Number) s.command(Map.of("cmd", "step", "n", 0)).get("cycle")).longValue();
        s.command(Map.of("cmd", "memwrite", "path", "rom", "addr", 2, "value", "5a"));
        s.command(Map.of("cmd", "step", "n", 2));
        assertEquals(0x5aL, MultiClockTest.last(s, "q"));
        long c1 = ((Number) s.command(Map.of("cmd", "step", "n", 0)).get("cycle")).longValue();
        // back before the write: the old word; forward again: the write is replayed
        s.command(Map.of("cmd", "goto", "cycle", c0 - 1));
        assertEquals("3", ((List<?>) s.mem(Map.of("path", "rom", "from", 2, "count", 1)).get("words")).getFirst());
        s.command(Map.of("cmd", "goto", "cycle", c1));
        assertEquals("5a", ((List<?>) s.mem(Map.of("path", "rom", "from", 2, "count", 1)).get("words")).getFirst());
        // a word too wide, or an address out of range, is a sentence
        var e = assertThrows(IllegalArgumentException.class, () -> s.command(Map.of("cmd", "memwrite", "path", "rom", "addr", 7, "value", "1")));
        assertTrue(e.getMessage().contains("addresses 0 to 3"), e.getMessage());
    }
}
