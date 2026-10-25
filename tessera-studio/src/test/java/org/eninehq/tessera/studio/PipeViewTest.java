package org.eninehq.tessera.studio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.eninehq.tessera.core.Module;
import org.eninehq.tessera.core.Tessera;
import org.eninehq.tessera.riscv.Cores;
import org.eninehq.tessera.riscv.RandomProgram;
import org.junit.jupiter.api.Test;

/** The pipeline diagram: items followed through the stages, with stalls, flushes and retirement. */
class PipeViewTest {
    /**
     * A load-use pair, then a taken branch over one instruction:
     * <pre>
     * 0x00 addi x1, x0, 5
     * 0x04 sw   x1, 0(x0)
     * 0x08 lw   x2, 0(x0)
     * 0x0c add  x3, x2, x2     uses the load in the next instruction: one stall in ID
     * 0x10 beq  x0, x0, +8     taken, resolved in EX: the two fetched after it are flushed
     * 0x14 addi x4, x0, 1      flushed
     * 0x18 addi x5, x0, 2      fetched once (flushed), then again from the target
     * 0x1c jal  x0, 0          a loop
     * </pre>
     */
    static final int[] PROGRAM = {
            RandomProgram.i(5, 0, 0, 1, 0x13), RandomProgram.s(0, 1, 0, 2), RandomProgram.i(0, 0, 2, 2, 0x03),
            RandomProgram.r(0, 2, 2, 0, 3, 0x33), RandomProgram.b(8, 0, 0, 0), RandomProgram.i(1, 0, 0, 4, 0x13),
            RandomProgram.i(2, 0, 0, 5, 0x13), RandomProgram.j(0, 0)};

    @SuppressWarnings("unchecked")
    static Map<String, Object> diagram(String core, int cycles) {
        var s = new Session(new Elaborator(List.of(), 1000));
        var e = (org.eninehq.tessera.core.Elaborated<Module>) (org.eninehq.tessera.core.Elaborated<?>) Tessera.elaborate(() -> Cores.get(core).build(1024, PROGRAM));
        s.loadDesign(e, core);
        s.command(Map.of("cmd", "pause"));
        s.command(Map.of("cmd", "step", "n", cycles));
        return s.pipeview(Map.of("from", 0, "to", cycles));
    }

    /** The first item at {@code pc} that reached IF, and its cells as (cycle, stage, stalled). */
    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> items(Map<String, Object> d) {
        var pipes = (List<Map<String, Object>>) d.get("pipes");
        assertEquals(1, pipes.size(), d.toString());
        return (List<Map<String, Object>>) pipes.getFirst().get("items");
    }

    @SuppressWarnings("unchecked")
    static List<long[]> cells(Map<String, Object> item) {
        var out = new ArrayList<long[]>();
        for (var c : (List<List<Object>>) item.get("cells"))
            out.add(new long[]{((Number) c.get(0)).longValue(), ((Number) c.get(1)).intValue(), ((Number) c.get(3)).intValue()});
        return out;
    }

    static String trace(Map<String, Object> item) {
        var sb = new StringBuilder();
        for (var c : cells(item)) sb.append(c[1]).append(c[2] == 1 ? "*" : "").append(' ');
        return sb.toString().trim();
    }

    @Test
    void c5ShowsTheLoadUseStallAndTheBranchFlush() {
        var d = diagram("C5", 24);
        @SuppressWarnings("unchecked") var pipe = ((List<Map<String, Object>>) d.get("pipes")).getFirst();
        // the signals call the first stage "s0"; the schedule's name for it is shown
        assertEquals(List.of("IF", "ID", "EX", "MEM", "WB"), pipe.get("stages"));
        var its = items(d);
        // the first fetch of each address, from the first time pc 0 is in IF
        Map<String, Map<String, Object>> first = new HashMap<>();
        Map<String, Map<String, Object>> last = new HashMap<>();
        for (var it : its) {
            if (it.get("pc") == null) continue;
            String pc = (String) it.get("pc");
            first.putIfAbsent(pc, it);
            last.put(pc, it);
        }
        long c0 = cells(first.get("0")).getFirst()[0];
        assertEquals(0, cells(first.get("0")).getFirst()[1], "pc 0 starts in IF");
        assertEquals("addi x1, x0, 5", first.get("0").get("text"));
        // in order through the five stages, retired
        assertEquals("0 1 2 3 4", trace(first.get("0")));
        assertEquals("retired", first.get("0").get("end"));
        assertEquals("lw x2, 0(x0)", first.get("8").get("text"));
        assertEquals("0 1 2 3 4", trace(first.get("8")));
        // the add waits one cycle in ID for the load (in EX at c0+4, forwarded from MEM at c0+5)
        assertEquals("0 1 1* 2 3 4", trace(first.get("c")));
        assertEquals(c0 + 4, cells(first.get("c")).get(1)[0]);
        // the branch is held in IF behind the stalled add, then resolves in EX at c0+7
        var beq = first.get("10");
        assertEquals("beq x0, x0, 8", beq.get("text"));
        assertEquals("0 0* 1 2 3 4", trace(beq));
        assertEquals(c0 + 7, cells(beq).get(3)[0]);
        // the two fetched behind it are flushed by the redirect in EX
        assertEquals("flushed", first.get("14").get("end"));
        assertEquals("0 1", trace(first.get("14")));
        assertEquals(c0 + 7, ((Number) first.get("14").get("endCycle")).longValue());
        assertEquals("flushed", first.get("18").get("end"));
        assertEquals("0", trace(first.get("18")));
        // and the target is fetched in the next cycle and runs to the end
        var target = its.get(its.indexOf(first.get("18")) + 1);
        assertEquals("18", target.get("pc"));
        assertEquals(c0 + 8, cells(target).getFirst()[0]);
        assertEquals("0 1 2 3 4", trace(target));
        assertEquals("retired", target.get("end"));
        assertFalse((Boolean) target.get("early"));
    }

    /** A loop of pairs: two independent ALU instructions, then one that needs both (it cannot pair), and a branch back. */
    static final int[] PAIRS = {
            RandomProgram.i(1, 0, 0, 1, 0x13), RandomProgram.i(2, 0, 0, 2, 0x13), RandomProgram.r(0, 2, 1, 0, 3, 0x33),
            RandomProgram.i(3, 0, 0, 4, 0x13), RandomProgram.i(4, 0, 0, 5, 0x13), RandomProgram.r(0, 5, 4, 0, 6, 0x33),
            RandomProgram.b(-24, 0, 0, 0)};

    /** A signal's value in every cycle {@code 0..to} of a session's record. */
    @SuppressWarnings("unchecked")
    static BigInteger[] series(Session s, String path, int to) {
        var w = (Map<String, Object>) ((Map<String, Object>) s.waves(Map.of("paths", List.of(path), "from", 0, "to", to, "columns", 1_000_000)).get("waves")).get(path);
        var out = new BigInteger[to + 1];
        BigInteger v = BigInteger.ZERO;
        int k = 0;
        for (Object o : (List<?>) w.get("changes")) {
            var ch = (List<?>) o;
            long c = ((Number) ch.get(0)).longValue();
            while (k <= to && k < c) out[k++] = v;
            v = new BigInteger(String.valueOf(ch.get(1)), 16);
        }
        while (k <= to) out[k++] = v;
        return out;
    }

    /**
     * A dual-issue core is drawn in two lanes, from the generator's own probes, and what the diagram calls retired is
     * what the core reports on its two RVFI channels: the same instructions in the same cycles.
     */
    @Test
    void dualIssueLanesMatchBothRetirementChannels() {
        for (String core : List.of("C9", "C10")) {
            var s = new Session(new Elaborator(List.of(), 1000));
            @SuppressWarnings("unchecked")
            var e = (org.eninehq.tessera.core.Elaborated<Module>) (org.eninehq.tessera.core.Elaborated<?>) Tessera.elaborate(() -> Cores.get(core).build(1024, PAIRS));
            s.loadDesign(e, core);
            s.command(Map.of("cmd", "pause"));
            int n = 80;
            s.command(Map.of("cmd", "step", "n", n));
            var d = s.pipeview(Map.of("from", 0, "to", n));
            @SuppressWarnings("unchecked") var pipe = ((List<Map<String, Object>>) d.get("pipes")).getFirst();
            assertEquals(2, ((Number) pipe.get("lanes")).intValue(), core);
            assertEquals(List.of("IF", "ID", "EX", "MEM", "WB"), pipe.get("stages"), core);
            var its = items(d);
            // what the diagram retires: (cycle, pc)
            var drawn = new java.util.TreeSet<String>();
            for (var it : its)
                if ("retired".equals(it.get("end")) && it.get("pc") != null)
                    drawn.add(((Number) it.get("endCycle")).longValue() + ":" + it.get("pc"));
            // what the core reports, on both channels, in the same cycles
            var v0 = series(s, "rvfi_valid", n); var p0 = series(s, "rvfi_pc_rdata", n);
            var v1 = series(s, "rvfi1_valid", n); var p1 = series(s, "rvfi1_pc_rdata", n);
            var reported = new java.util.TreeSet<String>();
            for (int c = 0; c < n; c++) {
                if (v0[c].signum() != 0) reported.add(c + ":" + p0[c].toString(16));
                if (v1[c].signum() != 0) reported.add(c + ":" + p1[c].toString(16));
            }
            assertFalse(reported.isEmpty(), core);
            assertEquals(reported, drawn, core + ": retired in the diagram = reported on RVFI");
            // two in one cycle happens (a pair), and lane 1 is drawn
            var perCycle = new HashMap<String, Integer>();
            for (var r : reported) perCycle.merge(r.split(":")[0], 1, Integer::sum);
            assertTrue(perCycle.containsValue(2), core + ": a pair retires together");
            // a cell is [cycle, stage, lane, stalled]
            assertTrue(its.stream().anyMatch(it -> ((List<?>) it.get("cells")).stream().anyMatch(c -> ((Number) ((List<?>) c).get(2)).intValue() == 1
                    && ((Number) ((List<?>) c).get(1)).intValue() >= 2)), core + ": lane 1 drawn past ID");
        }
    }

    /** The hand-written five-stage core (Level 1) names the same signals, so it gets a diagram too. */
    @Test
    void theHandWrittenPipelineToo() {
        var its = items(diagram("C2", 24));
        var pc0 = its.stream().filter(it -> "0".equals(it.get("pc"))).findFirst().orElseThrow();
        assertEquals("0 1 2 3 4", trace(pc0));
        assertEquals("retired", pc0.get("end"));
        assertTrue(its.stream().anyMatch(it -> "flushed".equals(it.get("end"))), "the taken branch flushes something");
    }

    @Test
    void theScheduleReportComesFromTheGenerator() {
        var s = new Session(new Elaborator(List.of(), 1000));
        @SuppressWarnings("unchecked")
        var e = (org.eninehq.tessera.core.Elaborated<Module>) (org.eninehq.tessera.core.Elaborated<?>) Tessera.elaborate(() -> Cores.get("C5").build(64, PROGRAM));
        s.loadDesign(e, "C5");
        @SuppressWarnings("unchecked") var sch = (List<Map<String, Object>>) s.pipeview(Map.of()).get("schedule");
        assertEquals(1, sch.size());
        @SuppressWarnings("unchecked") var lines = (List<String>) sch.getFirst().get("lines");
        assertTrue(lines.contains("redirect: resolved in EX; younger stages are flushed"), lines.toString());
        assertTrue(lines.stream().anyMatch(l -> l.startsWith("register reads in ID, writes in WB")), lines.toString());
        // the out-of-order core has no generated pipeline
        var s7 = new Session(new Elaborator(List.of(), 1000));
        @SuppressWarnings("unchecked")
        var e7 = (org.eninehq.tessera.core.Elaborated<Module>) (org.eninehq.tessera.core.Elaborated<?>) Tessera.elaborate(() -> Cores.get("C7").build(64, PROGRAM));
        s7.loadDesign(e7, "C7");
        var r7 = s7.pipeview(Map.of());
        assertEquals(List.of(), r7.get("schedule"));
        assertEquals(List.of(), r7.get("pipes"));
        // a dual-issue core's generator reports too (DualIssuePipeline, in the field dualPipeline)
        var s9 = new Session(new Elaborator(List.of(), 1000));
        @SuppressWarnings("unchecked")
        var e9 = (org.eninehq.tessera.core.Elaborated<Module>) (org.eninehq.tessera.core.Elaborated<?>) Tessera.elaborate(() -> Cores.get("C9").build(64, PROGRAM));
        s9.loadDesign(e9, "C9");
        @SuppressWarnings("unchecked") var sch9 = (List<Map<String, Object>>) s9.pipeview(Map.of()).get("schedule");
        assertEquals(1, sch9.size());
        assertEquals("dualPipeline", sch9.getFirst().get("field"));
        @SuppressWarnings("unchecked") var lines9 = (List<String>) sch9.getFirst().get("lines");
        assertTrue(lines9.stream().anyMatch(l -> l.startsWith("issue: two lanes")), lines9.toString());
    }

    /** Two issue lanes (the naming a dual-issue generator would use): pairs advance, a split pair waits. */
    @Test
    void twoLanesFollowPairsInAgeOrder() {
        var stages = List.of("s0", "ID");
        var probes = List.of(
                new PipeView.Probe(0, 0, null, "h0", "pc0", null), new PipeView.Probe(0, 1, null, "h0", "pc0b", null),
                new PipeView.Probe(1, 0, "v1", "h1", "pc1", null), new PipeView.Probe(1, 1, "v1b", "h1b", "pc1b", null));
        var pipe = new PipeView.Pipe("p", stages, 2, probes);
        // cycle 0: IF holds A(0), B(4); cycle 1: both in ID, C(8) D(c) in IF; ID retires A only (B held: split)
        // cycle 2: B in ID lane 0, lane 1 empty; IF held (C, D stay)
        Map<String, long[]> v = new HashMap<>();
        v.put("pc0", new long[]{0, 8, 8});
        v.put("pc0b", new long[]{4, 12, 12});
        v.put("v1", new long[]{0, 1, 1});
        v.put("v1b", new long[]{0, 1, 0});
        v.put("pc1", new long[]{0, 0, 4});
        v.put("pc1b", new long[]{0, 4, 0});
        v.put("h0", new long[]{0, 1, 0});
        v.put("h1", new long[]{0, 0, 0});
        v.put("h1b", new long[]{0, 1, 0});
        var items = PipeView.follow(pipe, 0, 2, (p, c) -> BigInteger.valueOf(v.get(p)[(int) (long) c]));
        assertEquals(4, items.size());
        var a = items.get(0);
        var b = items.get(1);
        assertEquals(0, a.pc);
        assertEquals(4, b.pc);
        assertEquals("retired", a.end);
        assertEquals(1, a.endCycle);
        assertEquals("inflight", b.end);
        // B was held in ID (lane 1) while its older partner left, and takes lane 0 of the next cycle
        assertEquals(List.of(new PipeView.Cell(0, 0, 1, false), new PipeView.Cell(1, 1, 1, false), new PipeView.Cell(2, 1, 0, true)), b.cells);
        assertEquals(8, items.get(2).pc);
        assertEquals(List.of(new PipeView.Cell(1, 0, 0, false), new PipeView.Cell(2, 0, 0, true)), items.get(2).cells);
    }
}
