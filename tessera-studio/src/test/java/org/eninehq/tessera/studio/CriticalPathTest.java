package org.eninehq.tessera.studio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.eninehq.tessera.core.Elaborated;
import org.eninehq.tessera.core.Module;
import org.eninehq.tessera.core.Tessera;
import org.eninehq.tessera.emit.SvEmitter;
import org.eninehq.tessera.riscv.Cores;
import org.junit.jupiter.api.Test;

/**
 * Vivado's timing paths mapped back to the design. The reports are real: the first paths of the
 * out-of-context runs of C5, C7 and C1a (Vivado 2020.1, xc7z020-1, 5 ns; vivado/qor_paths.tcl).
 */
class CriticalPathTest {
    static String report(String name) throws Exception {
        try (var in = CriticalPathTest.class.getResourceAsStream("/timing/" + name + ".paths.rpt")) {
            return new String(in.readAllBytes());
        }
    }

    @SuppressWarnings("unchecked")
    static java.util.function.Supplier<Elaborated<Module>> core(String id, int words, int[] image) {
        return () -> (Elaborated<Module>) (Elaborated<?>) Tessera.elaborate(() -> Cores.get(id).build(words, image));
    }

    @Test
    void parsesEveryPathAndItsHopsAddUpToTheDataPathDelay() throws Exception {
        for (String r : List.of("core_c5", "core_c7", "core_c1a")) {
            var paths = CriticalPath.parse(report(r));
            assertEquals(r.equals("core_c1a") ? 5 : 10, paths.size(), r);
            for (var p : paths) {
                // the source clock pin's own line has no delay; every hop after it adds up to the data path
                double sum = p.hops().stream().mapToDouble(CriticalPath.Hop::incr).sum();
                assertEquals(p.delay(), sum, 0.0105, r + " path " + p.rank() + ": " + p.hops());
                assertEquals(p.delay(), p.logic() + p.route(), 0.0015, r + " path " + p.rank());
                assertTrue(p.end().startsWith("core/"), p.end());
            }
        }
        var c5 = CriticalPath.parse(report("core_c5")).getFirst();
        assertEquals(-8.444, c5.slack(), 1e-9);
        assertEquals(13.437, c5.delay(), 1e-9);
        assertEquals(14, c5.levels());
        assertEquals("CARRY4=7 DSP48E1=1 LUT2=1 LUT4=1 LUT5=2 LUT6=2", c5.levelsDetail());
        assertEquals("core/_t998/CLK", c5.hops().getFirst().object());
        assertEquals("DSP48E1", c5.hops().get(1).prim());
        assertEquals(2.291, c5.hops().get(1).incr(), 1e-9);
        assertEquals("core/p_WB_1_reg[9]/D", c5.hops().getLast().object());
    }

    @Test
    void c5sWorstPathIsTheMultipliersFinishInRv32i() throws Exception {
        // the report is of C5 before the multiply was split, the core that is now C5-v1
        var t = CriticalPath.table(core("C5-v1", 1024, null), Designs.LOC_SKIP);
        @SuppressWarnings("unchecked")
        var path = (Map<String, Object>) ((List<?>) CriticalPath.json(CriticalPath.parse(report("core_c5")), t, "core", "test").get("paths")).getFirst();
        var lines = new ArrayList<String>();
        for (var o : (List<?>) path.get("hops")) {
            var h = (Map<?, ?>) o;
            if (h.get("line") != null && !lines.contains(h.get("file") + ":" + h.get("line"))) lines.add(h.get("file") + ":" + h.get("line"));
        }
        // the DSP's post-adder (carry's add), the high word, MULH's correction: the M finish of Rv32i
        assertTrue(lines.containsAll(List.of("Rv32i.java:214", "Rv32i.java:239", "Rv32i.java:240")), lines.toString());
        var last = (Map<?, ?>) ((List<?>) path.get("hops")).getLast();
        assertEquals("exact", last.get("quality"));
        assertEquals("p_WB_1", last.get("signal"));
        assertEquals("reg", last.get("kind"));
    }

    @Test
    void theSplitMultipliersWorstPathIsItsCorrectionInWriteBack() throws Exception {
        // c5.paths.rpt: C5 (multiply summed in MEM, corrected in WB), the same SV as var_c5_m2wb
        var t = CriticalPath.table(core("C5", 1024, null), Designs.LOC_SKIP);
        @SuppressWarnings("unchecked")
        var path = (Map<String, Object>) ((List<?>) CriticalPath.json(CriticalPath.parse(report("c5")), t, "core", "test").get("paths")).getFirst();
        var lines = new ArrayList<String>();
        for (var o : (List<?>) path.get("hops")) {
            var h = (Map<?, ?>) o;
            if (h.get("line") != null && !lines.contains(h.get("file") + ":" + h.get("line"))) lines.add(h.get("file") + ":" + h.get("line"));
        }
        // from a DSP register in MEM through the sum's carry (Rv32i's carry helper) into a DSP that Vivado used in WB
        assertTrue(lines.contains("Rv32i.java:214"), lines.toString());
        var last = (Map<?, ?>) ((List<?>) path.get("hops")).getLast();
        assertEquals("exact", last.get("quality"));
        assertEquals("p_WB_4", last.get("signal"));
        assertEquals(-4.899, ((Number) path.get("slack")).doubleValue(), 1e-9);
    }

    @Test
    void mappingRatesOnRealReports() throws Exception {
        // measured on the full 50-path reports: unmapped hops are 0-2.6% (names synthesis made up); here, the first paths
        for (String id : List.of("C5", "C7", "C1a")) {
            // core_c5's report is of the core that is now C5-v1
            var t = CriticalPath.table(core(id.equals("C5") ? "C5-v1" : id, 1024, null), Designs.LOC_SKIP);
            var j = CriticalPath.best(CriticalPath.parse(report("core_" + id.toLowerCase())), t, "test");
            assertEquals("core", j.get("wrapper"), id);
            var m = (Map<?, ?>) j.get("mapping");
            int ex = (Integer) m.get("exact"), ap = (Integer) m.get("approximate"), un = (Integer) m.get("unmapped");
            assertEquals(0, m.get("outside"), id);
            assertTrue(un * 20 < ex + ap + un, id + ": " + m);
            assertTrue(ex > 0, id + ": " + m);
        }
    }

    @Test
    void aStudioCoreHasTheSameNamesAsTheOneVivadoTimed() {
        // the studio runs the cores with 4096 words and a program; the timed runs use 1024 words and none
        int[] prog = org.eninehq.tessera.riscv.Benchmarks.loop(1000);
        for (String id : List.of("C1a", "C5", "C7")) {
            var a = SvEmitter.names(core(id, 1024, null).get().circuit()).stream().map(n -> n.module() + "." + n.name() + "." + n.kind()).toList();
            var b = SvEmitter.names(core(id, 4096, prog).get().circuit()).stream().map(n -> n.module() + "." + n.name() + "." + n.kind()).toList();
            assertEquals(a, b, id);
        }
    }

    @Test
    void aBoardBuildsResetPathsBelongToTheWrapper() throws Exception {
        // a real board build (Fpga's build.tcl, 100 MHz) of a 32-bit counter with its top byte on the LEDs: its worst
        // paths are the wrapper's power-on reset into count_reg's R pins, through LUTs synthesis put inside u
        String json;
        try (var in = CriticalPathTest.class.getResourceAsStream("/timing/board_count.json")) { json = new String(in.readAllBytes()); }
        var gen = CodeGen.generate(Json.object(json));
        var locs = new java.util.IdentityHashMap<org.eninehq.tessera.ir.Expr, org.eninehq.tessera.core.SourceLoc>();
        var out = org.eninehq.tessera.core.OpLocations.record(locs, Designs.LOC_SKIP, () -> new Elaborator(List.of(), Fpga.BOARD_HZ).elaborate(gen, "studio"));
        assertTrue(out.ok(), String.valueOf(out.problems()));
        var t = new CriticalPath.Table(SvEmitter.names(out.design().circuit(), locs), SvEmitter.topName(out.design().circuit()));
        var j = CriticalPath.json(CriticalPath.parse(report("board_count")), t, "u", "test");
        var m = (Map<?, ?>) j.get("mapping");
        // the wrapper's logic is the wrapper's, not "unmapped"; the end of each path is count, exactly
        assertEquals(0, m.get("unmapped"), m.toString());
        for (var o : (List<?>) j.get("paths")) {
            var hops = (List<?>) ((Map<?, ?>) o).get("hops");
            var end = (Map<?, ?>) hops.getLast();
            assertEquals("exact", end.get("quality"));
            assertEquals("count", end.get("signal"));
            assertEquals("outside", ((Map<?, ?>) hops.getFirst()).get("quality"));
        }
    }

    @Test
    void sessionMapsAReportOntoTheRunningCore() throws Exception {
        var s = new Session(new Elaborator(List.of(), 1000));
        var again = core("C5-v1", 4096, org.eninehq.tessera.riscv.Benchmarks.loop(1000));
        s.loadDesign(again.get(), "C5-v1", again);
        var r = s.timing(Map.of("report", report("core_c5"), "source", "core_c5"));
        assertEquals("core", r.get("wrapper"));
        assertEquals(10, ((List<?>) r.get("paths")).size());
        var direct = CriticalPath.json(CriticalPath.parse(report("core_c5")), CriticalPath.table(core("C5-v1", 1024, null), Designs.LOC_SKIP), "core", "x");
        assertEquals(direct.get("mapping"), r.get("mapping"));
        var e = assertThrows(IllegalArgumentException.class, () -> s.timing(Map.of("report", "Info: Max frequency for clock 'clk': 44.55 MHz (nextpnr)")));
        assertTrue(e.getMessage().contains("openXC7"), e.getMessage());
    }
}
