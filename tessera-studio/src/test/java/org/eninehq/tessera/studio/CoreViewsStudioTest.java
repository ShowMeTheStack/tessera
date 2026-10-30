package org.eninehq.tessera.studio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.eninehq.tessera.core.Elaborated;
import org.eninehq.tessera.core.Module;
import org.eninehq.tessera.core.Tessera;
import org.eninehq.tessera.riscv.Benchmarks;
import org.eninehq.tessera.riscv.Cores;
import org.eninehq.tessera.studio.CodeGenTest.D;
import org.junit.jupiter.api.Test;

/**
 * The studio on the newer cores: the core variants in the designs menu, the reorder-buffer diagram of the out-of-order
 * cores (C7, C11 and its variants, the generated C7g), the live-value-table register file read as one memory, the
 * generator's report for generated cores, the new library parts, and the prover's multi-clock options.
 */
class CoreViewsStudioTest {
    @SuppressWarnings("unchecked")
    static Elaborated<Module> core(String id, int[] image) {
        return (Elaborated<Module>) (Elaborated<?>) Tessera.elaborate(() -> Cores.get(id).build(4096, image));
    }

    /** A recorded signal's value in each cycle 0..to. */
    @SuppressWarnings("unchecked")
    static long[] values(Session s, String path, int to) {
        var w = (Map<String, Object>) ((Map<String, Object>) s.waves(Map.of("paths", List.of(path), "from", 0, "to", to, "columns", to + 1)).get("waves")).get(path);
        long[] v = new long[to + 1];
        var ch = (List<List<Object>>) w.get("changes");
        for (int i = 0; i < ch.size(); i++) {
            int c0 = ((Number) ch.get(i).get(0)).intValue(), c1 = i + 1 < ch.size() ? ((Number) ch.get(i + 1).get(0)).intValue() : to + 1;
            long x = new BigInteger(String.valueOf(ch.get(i).get(1)), 16).longValue();
            for (int c = Math.max(0, c0); c < Math.min(c1, to + 1); c++) v[c] = x;
        }
        return v;
    }

    @Test
    @SuppressWarnings("unchecked")
    void everyCoreVariantIsADesignInItsGroupAndTheFirstDesignsKeepTheirOrder() {
        var m = Designs.available(List.of());
        var names = new ArrayList<>(m.keySet());
        var all = Cores.ALL.stream().map(c -> c.id() + " " + c.name()).toList();
        assertEquals(all, names.subList(0, all.size()), "Cores.ALL first, in its order");
        // every core of Cores.all() exactly once, and each runs a program
        var ids = names.stream().map(n -> n.substring(0, n.indexOf(' '))).toList();
        assertEquals(new HashSet<>(ids).size(), ids.size(), "no duplicates: " + ids);
        for (var c : Cores.all()) assertTrue(ids.contains(c.id()), c.id() + " missing");
        assertEquals(Cores.all().size(), names.size());
        assertTrue(Designs.CORES.keySet().containsAll(names), "every core takes a program");
        var groups = Designs.groupsJson();
        var byName = new java.util.LinkedHashMap<String, List<String>>();
        for (Object g : groups) {
            var gm = (Map<String, Object>) g;
            byName.put((String) gm.get("group"), ((List<?>) gm.get("designs")).stream().map(x -> ((String) x).split(" ")[0]).toList());
        }
        assertEquals(Designs.MAIN_GROUP, byName.keySet().iterator().next());
        assertEquals(Boolean.TRUE, ((Map<?, ?>) groups.getFirst()).get("flat"));
        assertEquals(List.of("C11-br", "C11-tdp"), byName.get("Out-of-order variants of C11"));
        assertTrue(byName.get("Generated from one model (out-of-order and in-order generators)").containsAll(List.of("C7g", "C7g-er-lsq", "C5g")));
        assertEquals(List.of("C9s", "C10s", "C9s-rep", "C10s-rep"), byName.get("Dual issue, memory in both lanes (true dual-port memory)"));
        assertEquals(List.of("C9-lvt", "C10-lvt"), byName.get("Dual issue, live-value-table register file"));
        assertTrue(byName.get("Branch predictors (BTB, gshare, return-address stack)").containsAll(List.of("C8-gshare", "C8-ras", "C8-gsras", "C10-gsras", "C8-gs12ras", "C10-gs12ras")));
        assertFalse(byName.containsKey(Designs.REGISTERED_GROUP), "no registered designs, no empty group");
    }

    /**
     * The reorder-buffer diagram follows each instruction from dispatch to commit: the instructions it says committed,
     * in the order they committed, are the ones the core reports on RVFI, in that order.
     */
    @Test
    @SuppressWarnings("unchecked")
    void theReorderBufferDiagramCommitsWhatRvfiRetires() {
        for (String id : List.of("C7", "C11", "C11-br", "C11-tdp", "C7g", "C7g-er-lsq")) {
            var s = new Session(new Elaborator(List.of(), 1000));
            s.loadDesign(core(id, Benchmarks.loop(1000)), id);
            s.command(Map.of("cmd", "pause"));
            int to = ((Number) s.command(Map.of("cmd", "step", "n", 300)).get("cycle")).intValue();
            var pv = s.pipeview(Map.of("from", to - 250, "to", to));
            assertEquals(List.of(), pv.get("pipes"), id);
            var ooo = (List<?>) pv.get("ooo");
            assertEquals(1, ooo.size(), id + ": one reorder buffer");
            var rob = (Map<?, ?>) ooo.getFirst();
            assertEquals(OooView.STATES, rob.get("stages"));
            assertEquals(id.equals("C7g-4x3") ? 4 : 16, rob.get("entries"), id);
            var items = (List<Map<String, Object>>) rob.get("items");
            int from = ((Number) pv.get("from")).intValue();
            // committed by the diagram: (cycle, pc), in cycle order
            var diag = new ArrayList<String>();
            items.stream().filter(it -> it.get("end").equals("retired")).sorted((a, b) -> Long.compare(((Number) a.get("endCycle")).longValue(), ((Number) b.get("endCycle")).longValue()))
                    .forEach(it -> diag.add(it.get("endCycle") + ":" + it.get("pc")));
            long[] valid = values(s, "rvfi_valid", to), pc = values(s, "rvfi_pc_rdata", to);
            String best = null;
            for (int d : new int[]{0, 1}) {   // RVFI may report a commit in the cycle after it
                var rv = new ArrayList<String>();
                for (int c = from + d; c <= to; c++) if (valid[c] != 0 && c - d <= to) rv.add((c - d) + ":" + Long.toHexString(pc[c]));
                // the diagram's last cycle may commit what RVFI reports one cycle later, beyond the window
                var dg = diag.stream().filter(x -> Long.parseLong(x.split(":")[0]) <= to - d).toList();
                if (rv.equals(dg)) best = "offset " + d;
            }
            assertTrue(diag.size() > 40, id + ": committed " + diag.size());
            assertTrue(best != null, id + ": diagram commits " + diag.subList(0, Math.min(12, diag.size())) + " vs RVFI (pc at valid)");
            assertTrue(items.stream().allMatch(it -> it.get("text") != null), id + ": every row disassembled");
            var occ = (Map<String, List<Integer>>) rob.get("occupancy");
            assertEquals(to - from + 1, occ.get("ROB").size(), id);
            assertTrue(occ.get("ROB").stream().anyMatch(x -> x > 1), id + ": more than one instruction in flight at times");
            if (id.startsWith("C11") && !id.equals("C11-br")) assertTrue(occ.containsKey("LQ") && occ.containsKey("SQ") && occ.containsKey("checkpoints"), id + " " + occ.keySet());
        }
    }

    /** In-order cores have no reorder buffer; their diagram is as before. */
    @Test
    void inOrderCoresHaveNoReorderBuffer() {
        var s = new Session(new Elaborator(List.of(), 1000));
        s.loadDesign(core("C5", Benchmarks.loop(1000)), "C5");
        s.command(Map.of("cmd", "pause"));
        s.command(Map.of("cmd", "step", "n", 60));
        var pv = s.pipeview(Map.of());
        assertEquals(List.of(), pv.get("ooo"));
        assertEquals(1, ((List<?>) pv.get("pipes")).size());
    }

    /** The out-of-order generator's own account of what it built is the generated core's schedule, as the in-order generator's is. */
    @Test
    void aGeneratedOutOfOrderCoreShowsTheGeneratorsReport() {
        var s = new Session(new Elaborator(List.of(), 1000));
        s.loadDesign(core("C7g", Benchmarks.loop(1000)), "C7g");
        var sch = (List<?>) s.analyse(Map.of()).get("schedule");
        assertEquals(1, sch.size(), String.valueOf(sch));
        var m = (Map<?, ?>) sch.getFirst();
        assertEquals("ooo", m.get("field"));
        assertTrue(!((List<?>) m.get("lines")).isEmpty(), String.valueOf(m));
        // C5g is the same model through the in-order generator: its pipeline is drawn as before
        var s2 = new Session(new Elaborator(List.of(), 1000));
        s2.loadDesign(core("C5g", Benchmarks.loop(1000)), "C5g");
        s2.command(Map.of("cmd", "pause"));
        s2.command(Map.of("cmd", "step", "n", 60));
        var pv = s2.pipeview(Map.of());
        assertEquals(List.of("IF", "ID", "EX", "MEM", "WB"), ((Map<?, ?>) ((List<?>) pv.get("pipes")).getFirst()).get("stages"));
        assertEquals("inOrder", ((Map<?, ?>) ((List<?>) pv.get("schedule")).getFirst()).get("field"));
    }

    /**
     * C9-lvt keeps its registers in a live-value table (banks of one-write RAMs and a table of last writers): read
     * through the table, as the memory "regs", they are C9's registers after the same riscv-test.
     */
    @Test
    @SuppressWarnings("unchecked")
    void aLiveValueTableRegisterFileReadsAsOneMemory() throws Exception {
        int[] image = Designs.image(Designs.programs().get("rv32ui-add"));
        var words = new ArrayList<List<String>>();
        for (String id : List.of("C9", "C9-lvt")) {
            var s = new Session(new Elaborator(List.of(), 1000));
            s.loadDesign(core(id, image), id);
            s.command(Map.of("cmd", "pause"));
            s.command(Map.of("cmd", "step", "n", 3000));
            var r = s.mem(Map.of("path", "regs", "from", 0, "count", 32));
            assertEquals(32, r.get("width"), id);
            assertEquals(32, r.get("depth"), id);
            words.add((List<String>) r.get("words"));
            if (id.equals("C9-lvt")) {
                assertEquals(2, r.get("lvt"), "two write ports, two banks");
                var mems = s.mems();
                assertTrue(mems.stream().anyMatch(x -> ((Map<?, ?>) x).get("path").equals("regs") && Integer.valueOf(2).equals(((Map<?, ?>) x).get("lvt"))), String.valueOf(mems));
                assertTrue(mems.stream().anyMatch(x -> ((Map<?, ?>) x).get("path").equals("regs_lvt")), "the table itself is still there");
            } else assertNull(r.get("lvt"));
        }
        assertEquals("1", words.get(0).get(3), "C9 passes rv32ui-add (gp = 1)");
        assertEquals(words.get(0), words.get(1), "C9-lvt's registers, read through the table, are C9's");
        var s = new Session(new Elaborator(List.of(), 1000));
        s.loadDesign(core("C9-lvt", image), "C9-lvt");
        var e = assertThrows(IllegalArgumentException.class, () -> s.mem(Map.of("path", "regz")));
        assertTrue(e.getMessage().contains("no memory regz"), e.getMessage());
    }

    /** The standard library's true dual-port RAM and live-value-table register file are parts to place, with their pins. */
    @Test
    @SuppressWarnings("unchecked")
    void theNewLibraryPartsArePlaceable() throws Exception {
        var mods = new Modules(List.of(), 1000);
        var list = mods.list();
        for (String[] want : new String[][]{{"True dual-port RAM", "new TrueDualPortRam(256, 32)", "addrA", "rdataB"},
                {"Register file (live-value table)", "new LvtRegFile(32, 32, 4, 2, true)", null, null}}) {
            var c = list.stream().map(x -> (Map<String, Object>) x).filter(x -> want[0].equals(x.get("title"))).findFirst().orElseThrow(() -> new AssertionError(want[0] + " not listed"));
            assertEquals(want[1], c.get("expr"));
            var r = mods.probe(want[1], (List<String>) c.get("imports"));
            assertNull(r.get("error"), want[0] + ": " + r);
            var pins = String.valueOf(r.get("pins"));
            if (want[2] != null) assertTrue(pins.contains(want[2]) && pins.contains(want[3]), pins);
        }
    }

    /**
     * Vivado's paths of the new cores (out-of-context runs, xc7z020-1, the first 10 of 50 paths, 29 Oct) map onto the
     * cores the studio runs: every hop inside the core, fewer than 10% unmapped (synthesis-made names such as phys_opt's
     * _repN copies), and the core the studio builds (4096 words and a program) has the names of the one Vivado timed.
     */
    @Test
    void theNewCoresVivadoPathsMapOntoTheStudiosCores() throws Exception {
        var reports = Map.of("C11", "core_c11", "C11-br", "var_c11_br", "C11-tdp", "var_c11_tdp", "C7g", "gen_c7g",
                "C9-lvt", "var_c9_lvt", "C8-gs12ras", "var_c8_gs12ras");
        for (var e : reports.entrySet()) {
            String id = e.getKey();
            var paths = CriticalPath.parse(CriticalPathTest.report(e.getValue()));
            assertEquals(10, paths.size(), id);
            var t = CriticalPath.table(CriticalPathTest.core(id, 1024, null), Designs.LOC_SKIP);
            var j = CriticalPath.best(paths, t, "test");
            assertEquals("core", j.get("wrapper"), id);
            var m = (Map<?, ?>) j.get("mapping");
            int ex = (Integer) m.get("exact"), ap = (Integer) m.get("approximate"), un = (Integer) m.get("unmapped");
            assertEquals(0, m.get("outside"), id);
            assertTrue(ex > 0 && un * 10 < ex + ap + un, id + ": " + m);
            var a = org.eninehq.tessera.emit.SvEmitter.names(CriticalPathTest.core(id, 1024, null).get().circuit()).stream().map(n -> n.module() + "." + n.name() + "." + n.kind()).toList();
            var b = org.eninehq.tessera.emit.SvEmitter.names(CriticalPathTest.core(id, 4096, Benchmarks.loop(1000)).get().circuit()).stream().map(n -> n.module() + "." + n.name() + "." + n.kind()).toList();
            assertEquals(a, b, id + ": the studio's core has the names of the one Vivado timed");
        }
    }

    /** The Prove pane's options: none given, the defaults as before; given, they are used and echoed. */
    @Test
    void proveOptionsAreReadFromTheRequest() {
        assertNull(Session.proveOptions(Map.of("mode", "bmc", "depth", 4)));
        var o = Session.proveOptions(Map.of("freeAsyncResets", true, "atLeastOneTick", true, "maxTickGap", 3));
        assertTrue(o.freeAsyncResets() && o.atLeastOneTick());
        assertEquals(3, o.maxTickGap());
        var d = org.eninehq.tessera.formal.Formal.Options.defaults();
        assertEquals(d.resetCycles(), o.resetCycles());
        assertEquals(d.arbitraryMemory(), o.arbitraryMemory());
        assertThrows(IllegalArgumentException.class, () -> Session.proveOptions(Map.of("maxTickGap", -1)));
    }

    /**
     * Two counters on independent clocks are not in lock step (MultiClockTest), but with every clock ticking at
     * every step (maxTickGap 1) they are: the option changes the verdict.
     */
    @Test
    void theTickGapOptionReachesTheProver() {
        var d = new D("LockStep")
                .part("c", "domain", "name", "clock", "period", 1, "phase", 0)
                .part("f", "domain", "name", "fast", "period", 1, "phase", 0)
                .part("rs", "reg", "name", "count", "width", 8, "init", "0")
                .part("ks", "const", "value", "1", "width", 8)
                .part("as", "add", "mode", "wrap")
                .part("rf", "reg", "name", "fcount", "width", 8, "init", "0", "domain", "fast")
                .part("kf", "const", "value", "1", "width", 8)
                .part("af", "add", "mode", "wrap")
                .part("os", "out", "name", "slow_out", "width", 8)
                .part("of", "out", "name", "fast_out", "width", 8)
                .part("eq", "cmp", "op", "eq")
                .part("ok", "assert", "name", "lock_step")
                .net("n1", "rs.q", "as.a", "os.a", "eq.a").net("n2", "ks.y", "as.b").net("n3", "as.y", "rs.d")
                .net("m1", "rf.q", "af.a", "of.a", "eq.b").net("m2", "kf.y", "af.b").net("m3", "af.y", "rf.d")
                .net("e1", "eq.y", "ok.cond");
        var s = new Session(new Elaborator(List.of(), 1000));
        assertEquals(true, s.load(d.doc).get("elaborated"));
        assertEquals("FAIL", s.prove(Map.of("mode", "bmc", "depth", 6)).get("status"));
        var s2 = new Session(new Elaborator(List.of(), 1000));
        assertEquals(true, s2.load(d.doc).get("elaborated"));
        var p = s2.prove(Map.of("mode", "bmc", "depth", 6, "maxTickGap", 1));
        assertEquals("PASS", p.get("status"), p.toString());
        assertEquals(1, ((Map<?, ?>) p.get("options")).get("maxTickGap"));
    }
}
