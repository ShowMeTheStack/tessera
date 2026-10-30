package org.eninehq.tessera.studio;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.eninehq.tessera.core.Elaborated;
import org.eninehq.tessera.core.Module;
import org.eninehq.tessera.sim.HotSwap;
import org.eninehq.tessera.sim.Sim;
import org.eninehq.tessera.sim.Simulator;
import org.eninehq.tessera.sim.TimeTravel;

/**
 * The design running in the studio. Every load of a drawing re-elaborates it; when a design is
 * already running, its registers and memories carry over to the new one by name (hot swap), so
 * editing never restarts the simulation. The simulation runs on a virtual thread at a chosen rate;
 * every cycle is recorded for the waves, snapshots allow going back to any earlier cycle, and the
 * values of the drawing's wires are pushed to the browser as frames over server-sent events.
 *
 * <p>Time is counted across reloads: cycle {@code base + sim.cycle()}.
 */
public final class Session implements AutoCloseable {
    private final Elaborator elaborator;
    private final List<HttpExchange> listeners = new CopyOnWriteArrayList<>();
    private final Thread loop;
    private volatile boolean closed;

    // guarded by this
    private Elaborated<Module> design;
    private Simulator sim;
    private TimeTravel tt;
    private long base;                      // cycles before the current simulator started
    private Map<String, String> netPaths = Map.of();
    private String runningCode;
    /** The running design as a testbench names it: its package and class, its source when drawn, how to make it (null: not known). */
    private record BenchDesign(String pkg, String cls, String java, String make) {}
    private BenchDesign benchDesign;
    private final Waves waves = new Waves();
    private boolean running = true;
    private double rate = 10;               // cycles per second; <= 0 is "as fast as possible"
    private long runUntil = -1;             // stop when the cycle reaches this, or -1
    private Cond untilCond;                 // or when this holds
    private boolean viewing;                // the running design was opened from Java (not drawn)
    /** Elaborates the running design again (for its name table with operation locations), and that table. */
    private java.util.function.Supplier<Elaborated<Module>> again;
    private CriticalPath.Table timingTable;
    private Map<String, String> lastPaths = Map.of();
    private List<PipeView.Pipe> pipes = List.of();   // the running design's pipelines, for the diagram
    private List<OooView.Rob> robs = List.of();      // its reorder buffer, if it is an out-of-order core
    private long unwatchedSince;
    private String untilText;
    private long viewCycle = -1;            // the cycle shown when looking into the past, or -1 for the head
    private final Map<String, BigInteger> lastSent = new HashMap<>();
    private String lastMessage = "";
    private long frames;

    Session(Elaborator elaborator) {
        this.elaborator = elaborator;
        loop = Thread.ofVirtual().name("studio-sim").start(this::run);
    }

    /** Each clock input's {period, first edge} in ticks; a cycle of the run is one tick. */
    private Map<String, long[]> clockSpec = Map.of();

    /** The clock domains a drawing declares: name_clock gets its part's period and phase; clock is every tick unless told. */
    private static Map<String, long[]> clocksOf(Map<String, Object> doc) {
        var m = new LinkedHashMap<String, long[]>();
        for (var p : CodeGen.list(doc.get("parts"))) {
            if (!"domain".equals(p.get("type"))) continue;
            var pr = CodeGen.map(p.get("params"));
            String n = String.valueOf(pr.getOrDefault("name", ""));
            long per = Math.max(1, Parts.intParam(pr, "period", 2)), ph = Math.max(0, Parts.intParam(pr, "phase", 0));
            m.put(n.equals("clock") ? "clock" : n + "_clock", new long[]{per, ph});
        }
        return m;
    }

    /** The clocks with an edge at tick c. */
    private List<String> due(long c) {
        var out = new ArrayList<String>();
        for (String clk : sim.clocks()) {
            long[] sp = clockSpec.getOrDefault(clk, new long[]{1, 0});
            if (c >= sp[1] && (c - sp[1]) % sp[0] == 0) out.add(clk);
        }
        return out;
    }

    /** Every clock every tick, unless some clock has a period of its own. */
    private boolean simpleClocks() {
        for (String clk : sim.clocks()) { long[] sp = clockSpec.get(clk); if (sp != null && (sp[0] != 1 || sp[1] != 0)) return false; }
        return true;
    }

    private void installStepper() {
        if (simpleClocks()) return;
        tt.stepper((s, c) -> s.stepClocks(due(base + c)));
    }

    /** The design's clocks for the page: name, port, period and first edge (ticks). */
    private List<Object> clocksJson() {
        var l = new ArrayList<Object>();
        if (sim == null) return l;
        for (String clk : sim.clocks()) {
            long[] sp = clockSpec.getOrDefault(clk, new long[]{1, 0});
            l.add(Map.of("name", clk.equals("clock") ? "clock" : clk.replaceFirst("_clock$", ""), "port", clk, "period", sp[0], "phase", sp[1]));
        }
        return l;
    }

    /** Hold every reset input (reset, name_reset) for n ticks of all clocks, then release them. */
    private void resetAll(int n, boolean recorded) {
        var resets = new ArrayList<String>();
        for (var p : design.circuit().top().ports()) if (p.isClockOrReset() && p.kind() == org.eninehq.tessera.ir.Signal.Kind.INPUT && (p.name().equals("reset") || p.name().endsWith("_reset"))) resets.add(p.name());
        for (String r : resets) { if (recorded) tt.poke(r, 1); else sim.poke(r, 1); }
        for (int i = 0; i < n; i++) { if (recorded) tt.step(); else sim.stepClocks(sim.clocks()); }
        for (String r : resets) { if (recorded) tt.poke(r, 0); else sim.poke(r, 0); }
    }

    /** Generate, compile and elaborate a drawing; on success run it (carrying state over). */
    public synchronized Map<String, Object> load(Map<String, Object> doc) {
        var gen = CodeGen.generate(doc);
        var out = new LinkedHashMap<String, Object>(gen.json());
        // the same code as the design already running: nothing to rebuild (the drawing only moved)
        String code = gen.java().substring(0, Math.max(0, gen.java().lastIndexOf(CodeGen.MARK)));
        if (design != null && code.equals(runningCode) && gen.ok()) {
            out.put("elaborated", true);
            out.put("unchanged", true);
            out.put("ports", Elaborator.ports(design));
            out.put("message", lastMessage);
            out.put("cycle", cycle());
            var paths = new LinkedHashMap<String, String>(gen.netPaths());
            paths.putAll(gen.innerPaths());
            netPaths = withPorts(paths, design);
            return out;
        }
        if (!gen.ok()) {
            out.put("elaborated", false);
            out.put("message", "Not run: fix the problems first; the last good design keeps running.");
            return out;
        }
        String pkg = String.valueOf(doc.getOrDefault("package", "studio"));
        var outcome = elaborator.elaborate(gen, pkg);
        var problems = new ArrayList<Object>();
        for (var p : gen.problems()) problems.add(p.json());
        for (var p : outcome.problems()) problems.add(p.json());
        out.put("problems", problems);
        out.put("elaborateMs", outcome.millis());
        out.put("elaborated", outcome.ok());
        if (!outcome.ok()) {
            out.put("ok", false);
            out.put("message", "Not run: the design does not elaborate; the last good design keeps running.");
            return out;
        }
        var paths = new LinkedHashMap<String, String>(gen.netPaths());
        paths.putAll(gen.innerPaths());          // values inside blocks, keyed "instance/net"
        if (viewing && sim != null) { sim.close(); sim = null; }   // a drawing after a design from Java: a new run, not a hot swap
        viewing = false;
        clockSpec = clocksOf(doc);
        swapIn(outcome.design(), paths);
        out.put("clocks", clocksJson());
        runningCode = code;
        boolean clk = gen.java().contains("public " + gen.className() + "(Clock clk)");
        benchDesign = new BenchDesign(pkg, gen.className(), gen.java(), "new " + gen.className() + "(" + (clk ? "Clock.hz(" + elaborator.simHz() + ")" : "") + ")");
        again = () -> elaborator.elaborate(gen, pkg).design();
        timingTable = null;
        out.put("ports", Elaborator.ports(outcome.design()));
        out.put("message", lastMessage);
        out.put("cycle", cycle());
        return out;
    }

    /** Run a design written in Java (not drawn): its drawing is made by {@link Viewer}. */
    public synchronized Map<String, Object> loadDesign(Elaborated<Module> e, String title) {
        return loadDesign(e, title, () -> e);
    }

    /** {@link #loadDesign(Elaborated, String)}, with how to elaborate it again (for timing paths' Java lines). */
    public synchronized Map<String, Object> loadDesign(Elaborated<Module> e, String title, java.util.function.Supplier<Elaborated<Module>> again) {
        this.again = again;
        timingTable = null;
        long t0 = System.nanoTime();
        var view = Viewer.view(e, title);
        long t1 = System.nanoTime();
        @SuppressWarnings("unchecked")
        var paths = (Map<String, String>) view.get("netPaths");
        if (sim != null) { sim.close(); sim = null; }
        runningCode = null;
        clockSpec = Map.of();
        swapIn(e, paths);
        viewing = true;
        Class<?> top = e.top().getClass();
        boolean noArg = java.util.Arrays.stream(top.getConstructors()).anyMatch(k -> k.getParameterCount() == 0);
        benchDesign = new BenchDesign(top.getPackageName(), top.getSimpleName(), null, noArg && top.getEnclosingClass() == null ? "new " + top.getSimpleName() + "()" : null);
        lastMessage = "opened " + title + ": " + e.circuit().top().name();
        view.put("ports", Elaborator.ports(e));
        view.put("message", lastMessage);
        view.put("viewMs", (t1 - t0) / 1_000_000);
        view.put("simMs", (System.nanoTime() - t1) / 1_000_000);
        return view;
    }

    /** The ports' values travel as "#name" too, for the board, wired or not. */
    private static Map<String, String> withPorts(Map<String, String> paths, Elaborated<Module> e) {
        var m = new LinkedHashMap<String, String>(paths);
        for (var p : e.circuit().top().ports()) if (!p.isClockOrReset()) m.put("#" + p.name(), p.name());
        return m;
    }

    private void swapIn(Elaborated<Module> e, Map<String, String> paths) {
        if (sim == null) {
            waves.clear();   // a new timeline: the old record and its marks belong to another design
            sim = Sim.create(e, Sim.Backend.COMPILED);
            base = 0;
            design = e;
            resetAll(2, false);
            lastMessage = "started " + e.circuit().top().name();
        } else {
            leavePast();
            long now = cycle();
            // inputs keep their values too (registers and memories carry over in the swap itself)
            var inputs = new LinkedHashMap<String, BigInteger>();
            for (var p : design.circuit().top().ports())
                if (p.kind() == org.eninehq.tessera.ir.Signal.Kind.INPUT && !p.isClockOrReset()) inputs.put(p.name(), sim.peekBig(p.name()));
            var swapped = HotSwap.swap(sim, e.circuit(), Sim.Backend.COMPILED);
            sim = swapped.sim();
            base = now - sim.cycle();
            for (var p : e.circuit().top().ports()) {
                var v = inputs.get(p.name());
                if (v != null && p.kind() == org.eninehq.tessera.ir.Signal.Kind.INPUT && v.bitLength() <= p.width()) sim.pokeBig(p.name(), v);
            }
            var r = swapped.report();
            lastMessage = "reloaded " + e.circuit().top().name() + " at cycle " + now + ": kept " + r.registers() + " registers"
                    + (r.newRegisters().isEmpty() ? "" : ", new " + r.newRegisters()) + (r.droppedRegisters().isEmpty() ? "" : ", dropped " + r.droppedRegisters());
            waves.mark(now, lastMessage);
        }
        design = e;
        tt = new TimeTravel(sim, 256);
        installStepper();
        for (var p : e.circuit().top().ports())
            if (p.kind() == org.eninehq.tessera.ir.Signal.Kind.INPUT && !p.isClockOrReset()) tt.pokeBig(p.name(), sim.peekBig(p.name()));
        lastPaths = paths;
        netPaths = withPorts(paths, e);
        viewCycle = -1;
        var record = new java.util.LinkedHashSet<String>(paths.values());
        for (var p : e.circuit().top().ports()) record.add(p.name());
        pipes = dualProbes(PipeView.relabel(PipeView.find(e.circuit().top()), scheduleStages(e.top())), e.top());
        for (var p : pipes) record.addAll(p.paths());   // the diagram reads them from the record
        robs = OooView.find(e.circuit().top());
        for (var r : robs) record.addAll(r.paths());
        record.addAll(waves.paths());
        waves.retarget(signals(), record, cycle());
        sampleAll();
        lastSent.clear();
        broadcast(frame(true));
    }

    /** The cycle being shown: a past one while looking back, else the head of the simulation. */
    long cycle() {
        if (sim == null) return 0;
        return viewCycle >= 0 ? viewCycle : base + sim.cycle();
    }

    /** The earliest cycle the simulator can go back to (before it, the wave record answers). */
    private long reachable() { return base + tt.earliest(); }

    // memory bounds for long runs: time-travel snapshots kept, and the wave record (Waves.BUDGET)
    static final int MAX_SNAPSHOTS = Integer.getInteger("studio.maxSnapshots", 4096);

    private void bound() {
        if (tt.snapshotCount() > MAX_SNAPSHOTS) tt.forgetBefore(tt.head() - (long) MAX_SNAPSHOTS / 2 * 256);
        if (waves.trim(cycle())) lastMessage = "dropped the oldest half of the wave record to stay within memory";
    }

    /** The value of a signal in the cycle being shown: from the record before the last reload, else from the simulator. */
    private BigInteger value(String path) {
        if (viewCycle >= 0 && viewCycle < reachable()) return waves.at(path, viewCycle);
        return sim.peekBig(path);
    }

    /** Signals the waves can show: every named signal of the design, with its width. */
    private Map<String, Integer> signals() {
        var m = new LinkedHashMap<String, Integer>();
        for (var e : sim.netlist().signalsByPath().entrySet()) m.put(e.getKey(), e.getValue().width());
        return m;
    }

    private void sampleAll() {
        if (viewCycle >= 0) return;
        long c = cycle();
        for (var path : waves.paths()) {
            try { waves.sample(path, c, sim.peekBig(path)); } catch (RuntimeException ignored) { /* not observable */ }
        }
    }

    // ---------------------------------------------------------------- the run loop

    private void run() {
        long last = System.nanoTime();
        double owed = 0;
        final long period = 16_666_667;        // a frame at 60 Hz: simulate for most of it, then send what changed
        long next = System.nanoTime() + period;
        while (!closed) {
            try {
                long wait = next - System.nanoTime();
                if (wait > 0) Thread.sleep(wait / 1_000_000, (int) (wait % 1_000_000));
            } catch (InterruptedException e) {
                return;
            }
            next = Math.max(next + period, System.nanoTime() + period / 4);
            long now = System.nanoTime();
            double dt = Math.min(0.1, (now - last) / 1e9);
            last = now;
            String f;
            synchronized (this) {
                if (sim == null) continue;
                if (running && viewCycle < 0) {
                    owed += rate <= 0 ? 1e9 : rate * dt;
                    long n = (long) owed;
                    owed -= n;
                    long deadline = System.nanoTime() + 11_000_000;  // leave time for the frame in each 16.7 ms period
                    for (long k = 0; k < n; k++) {
                        if (runUntil >= 0 && cycle() >= runUntil) { running = false; runUntil = -1; owed = 0; break; }
                        if (!stepChecked()) { owed = 0; break; }
                        sampleAll();
                        if (untilCond != null && untilCond.holds(sim::peekBig)) {
                            running = false; owed = 0;
                            lastMessage = "stopped at cycle " + cycle() + ": " + untilText + " holds";
                            waves.markKind(cycle(), untilText + " holds", "trace");
                            var m = new LinkedHashMap<String, Object>();
                            m.put("type", "until");
                            m.put("cycle", cycle());
                            m.put("message", lastMessage);
                            broadcast(Json.write(m));
                            untilCond = null;
                            break;
                        }
                        if ((k & 63) == 63 && System.nanoTime() > deadline) { owed = 0; break; }
                    }
                    bound();
                    // nobody is watching: stop, rather than run and record for no one
                    if (listeners.isEmpty()) {
                        if (unwatchedSince == 0) unwatchedSince = System.nanoTime();
                        else if (System.nanoTime() - unwatchedSince > 5_000_000_000L) {
                            running = false;
                            lastMessage = "paused at cycle " + cycle() + ": no page was watching";
                        }
                    } else unwatchedSince = 0;
                }
                f = frame(false);
            }
            if (f != null) broadcast(f);
        }
    }

    // ---------------------------------------------------------------- stimulus

    /** Inputs scheduled for later cycles (a pattern drawn in the waves): cycle -> path -> value. */
    private final java.util.TreeMap<Long, Map<String, BigInteger>> scheduled = new java.util.TreeMap<>();

    private void applyScheduled() {
        var due = scheduled.get(cycle());
        if (due == null) return;
        for (var e : due.entrySet()) { tt.pokeBig(e.getKey(), e.getValue()); waves.sample(e.getKey(), cycle(), e.getValue()); }
    }

    /** One cycle; an assertion that fails pauses the run, marks the waves and says so (false then). */
    private boolean stepChecked() {
        try {
            applyScheduled();
            tt.step();
            return true;
        } catch (AssertionError err) {
            running = false;
            long c = cycle();
            String msg = err.getMessage() == null ? "an assertion failed" : err.getMessage();
            waves.markKind(c, msg, "fail");
            lastMessage = "stopped at cycle " + c + ": " + msg;
            var m = new LinkedHashMap<String, Object>();
            m.put("type", "assert");
            m.put("cycle", c);
            m.put("message", msg);
            broadcast(Json.write(m));
            return false;
        }
    }

    /** The values that changed since the last frame (all of them when {@code full}), as JSON. */
    private String frame(boolean full) {
        if (sim == null) return null;
        var vals = new LinkedHashMap<String, Object>();
        for (var e : netPaths.entrySet()) {
            BigInteger v;
            try { v = value(e.getValue()); } catch (RuntimeException ex) { continue; }
            if (v == null) continue;
            if (full || !v.equals(lastSent.get(e.getKey()))) {
                vals.put(e.getKey(), v.toString(16));
                lastSent.put(e.getKey(), v);
            }
        }
        frames++;
        if (!full && vals.isEmpty() && frames % 30 != 0) return null;
        var m = new LinkedHashMap<String, Object>();
        m.put("type", "frame");
        m.put("cycle", cycle());
        m.put("head", base + tt.head());
        m.put("running", running && viewCycle < 0);
        m.put("rate", rate);
        m.put("values", vals);
        m.put("full", full);
        return Json.write(m);
    }

    // ---------------------------------------------------------------- commands

    /** Run control and inputs: {@code {cmd: run|pause|step|reset|poke|goto|rate|until, ...}}. */
    public synchronized Map<String, Object> command(Map<String, Object> req) {
        if (sim == null && "rate".equals(req.get("cmd"))) {   // a setting: it needs no design
            rate = ((Number) req.getOrDefault("rate", 10)).doubleValue();
            return Map.of("cycle", 0, "running", false, "message", "");
        }
        if (sim == null) throw new IllegalArgumentException("Nothing is running yet: draw something that elaborates.");
        String cmd = String.valueOf(req.get("cmd"));
        switch (cmd) {
            case "run" -> { leavePast(); running = true; }
            case "pause" -> { running = false; untilCond = null; }
            case "rate" -> rate = ((Number) req.getOrDefault("rate", 10)).doubleValue();
            case "step" -> {
                leavePast();
                running = false;
                long n = ((Number) req.getOrDefault("n", 1)).longValue();
                if (req.get("edge") instanceof String clk) {   // to just after the next edge of one clock
                    if (!sim.clocks().contains(clk)) throw new IllegalArgumentException("There is no clock " + clk + "; the clocks are " + String.join(", ", sim.clocks()) + ".");
                    long c = cycle(), k = 0;
                    while (!due(c + k).contains(clk)) k++;
                    n = k + 1;
                }
                for (long k = 0; k < n; k++) { if (!stepChecked()) break; sampleAll(); if ((k & 65535) == 65535) bound(); }
                bound();
            }
            case "until" -> {
                leavePast();
                if (req.get("expr") instanceof String text && !text.isBlank()) {
                    var c = Cond.parse(text);
                    var missing = Cond.unknown(c, sim.netlist().signalsByPath().keySet());
                    if (!missing.isEmpty()) throw new IllegalArgumentException("There is no signal " + String.join(", ", missing) + " in this design.");
                    untilCond = c; untilText = text.trim();
                    if (c.holds(sim::peekBig)) throw new IllegalArgumentException("“" + untilText + "” already holds at cycle " + cycle() + ".");
                } else runUntil = cycle() + ((Number) req.getOrDefault("n", 100)).longValue();
                running = true;
            }
            case "restart" -> {   // a new simulation of the same design from cycle 0: a fresh timeline and record
                var e = design;
                scheduled.clear();
                sim.close();
                sim = null;
                swapIn(e, lastPaths);
                running = false;
                lastMessage = "restarted " + e.circuit().top().name() + " from cycle 0";
            }
            case "reset" -> {
                leavePast();
                boolean hasReset = design.circuit().top().ports().stream().anyMatch(p -> p.isClockOrReset() && p.name().equals("reset"));
                if (!hasReset) { lastMessage = "this design has no state, so nothing to reset"; break; }
                long rn = ((Number) req.getOrDefault("n", 2)).longValue();
                waves.markKind(cycle(), "reset asserted for " + rn + " cycles (the timeline goes on)", "reload");
                resetAll((int) rn, true);
                sampleAll();
            }
            case "poke" -> {
                String path = String.valueOf(req.get("path"));
                var v = new BigInteger(String.valueOf(req.get("value")), 16);
                if (viewCycle >= 0 && viewCycle < reachable())
                    throw new IllegalArgumentException("Cycle " + viewCycle + " is before the design was last reloaded; inputs can be changed from cycle " + base + " on.");
                if (viewCycle >= 0) {          // changing an input in the past forks a new future from there
                    waves.truncate(viewCycle);
                    viewCycle = -1;
                }
                tt.pokeBig(path, v);
                waves.sample(path, cycle(), v);
                sampleAll();
            }
            case "goto" -> {
                long c = ((Number) req.get("cycle")).longValue();
                long head = base + tt.head();
                if (c >= head) { leavePast(); }
                else if (waves.droppedBefore() > 0 && c < waves.droppedBefore())
                    throw new IllegalArgumentException("Cycle " + c + " is no longer recorded: the record keeps cycles from " + waves.droppedBefore()
                            + " on (older ones were dropped to stay within memory).");
                else if (c < reachable()) {   // before the last reload, or before the history kept: only the record knows it
                    if (viewCycle < 0 || viewCycle >= reachable()) tt.goTo(tt.head());
                    viewCycle = Math.max(c, 0);
                    running = false;
                } else {
                    tt.goTo(c - base);
                    viewCycle = c;
                    running = false;
                }
            }
            case "record" -> {
                for (Object o : req.get("paths") instanceof List<?> l ? l : List.of()) waves.record(String.valueOf(o), cycle());
                sampleAll();
            }
            case "schedule" -> {   // {path, at: [[cycle, hex], ...]}: the input takes each value from its cycle on
                String path = String.valueOf(req.get("path"));
                var port = design.circuit().top().ports().stream().filter(x -> x.name().equals(path) && x.kind() == org.eninehq.tessera.ir.Signal.Kind.INPUT && !x.isClockOrReset()).findFirst()
                        .orElseThrow(() -> new IllegalArgumentException(path + " is not an input of this design."));
                for (var m : scheduled.values()) m.remove(path);   // a new pattern replaces the old one
                scheduled.values().removeIf(Map::isEmpty);
                for (Object o : req.get("at") instanceof List<?> l ? l : List.of()) {
                    var pr = (List<?>) o;
                    long c = ((Number) pr.get(0)).longValue();
                    var v = new BigInteger(String.valueOf(pr.get(1)), 16);
                    if (v.bitLength() > port.width()) throw new IllegalArgumentException(path + " is " + port.width() + " bits: 0x" + v.toString(16) + " does not fit.");
                    if (c < cycle()) continue;                     // the past is not rewritten
                    scheduled.computeIfAbsent(c, k -> new LinkedHashMap<>()).put(path, v);
                }
                applyScheduled();
            }
            case "memwrite" -> {   // {path, addr, value}: one word of a memory, written now and kept in the history
                String path = String.valueOf(req.get("path"));
                Integer i = sim.netlist().memsByPath().get(path);
                if (i == null) throw new IllegalArgumentException("There is no memory " + path + " in this design.");
                if (viewCycle >= 0) throw new IllegalArgumentException("Memory is written at the latest cycle; this is a look at cycle " + viewCycle + ". Run or step to come back first.");
                var m = sim.netlist().mems().get(i);
                int addr = ((Number) req.get("addr")).intValue();
                if (addr < 0 || addr >= m.depth()) throw new IllegalArgumentException(path + " has addresses 0 to " + (m.depth() - 1) + ".");
                var v = new BigInteger(String.valueOf(req.get("value")), 16);
                if (v.signum() < 0 || v.bitLength() > m.width()) throw new IllegalArgumentException(path + " holds " + m.width() + "-bit words: 0x" + v.toString(16) + " does not fit.");
                long[] words = new long[m.words()];
                for (int k = 0; k < words.length; k++) words[k] = v.shiftRight(64 * k).longValue();
                tt.pokeMemory(path, addr, words);
                waves.markKind(cycle(), path + "[" + addr + "] written: 0x" + v.toString(16), "reload");
                sampleAll();
            }
            case "refresh" -> { }                 // just send a full frame
            default -> throw new IllegalArgumentException("unknown command " + cmd);
        }
        lastSent.clear();
        String f = frame(true);
        if (f != null) broadcast(f);
        var m = new LinkedHashMap<String, Object>();
        m.put("cycle", cycle());
        m.put("running", running);
        m.put("message", lastMessage);
        return m;
    }

    /** Back to the head of the simulation after looking at the past. */
    private void leavePast() {
        if (viewCycle >= 0) {
            if (viewCycle >= reachable()) tt.goTo(tt.head());
            viewCycle = -1;
        }
    }

    // ---------------------------------------------------------------- proving

    /** Run the prover on the running design: mode bmc (to a depth), prove (k-induction) or cover. */
    public Map<String, Object> prove(Map<String, Object> req) {
        Elaborated<Module> e;
        synchronized (this) { e = design; }
        if (e == null) throw new IllegalArgumentException("Nothing to prove yet: the design must elaborate first.");
        String mode = String.valueOf(req.getOrDefault("mode", "bmc"));
        int depth = ((Number) req.getOrDefault("depth", 20)).intValue();
        var out = new LinkedHashMap<String, Object>();
        var opt = proveOptions(req);
        if (opt != null) out.put("options", Map.of("freeAsyncResets", opt.freeAsyncResets(), "atLeastOneTick", opt.atLeastOneTick(),
                "maxTickGap", opt.maxTickGap(), "arbitraryMemory", opt.arbitraryMemory(), "resetCycles", opt.resetCycles()));
        var o = opt == null ? org.eninehq.tessera.formal.Formal.Options.defaults() : opt;
        long t0 = System.nanoTime();
        try {
            if (mode.equals("cover")) {
                out.put("covers", org.eninehq.tessera.formal.Formal.cover(e.circuit(), depth, o));
                out.put("status", "COVER");
            } else {
                var r = mode.equals("prove") ? org.eninehq.tessera.formal.Formal.prove(e.circuit(), depth, o) : org.eninehq.tessera.formal.Formal.bmc(e.circuit(), depth, o);
                out.put("status", r.status().toString());
                out.put("text", r.toString());
                out.put("depth", r.depth());
                if (r.failed()) {
                    out.put("check", r.check());
                    out.put("location", r.location());
                    out.put("failCycle", r.failCycle());
                    synchronized (this) { loadTrace(r); }
                    out.put("traceFrom", traceFrom);
                }
            }
        } catch (IllegalStateException ex) {
            throw new IllegalArgumentException(ex.getMessage());
        }
        out.put("ms", (System.nanoTime() - t0) / 1_000_000);
        return out;
    }

    /**
     * The prover's options from a request: {@code freeAsyncResets}, {@code atLeastOneTick} (booleans),
     * {@code maxTickGap}, {@code resetCycles} (numbers) and {@code arbitraryMemory} (boolean) over
     * {@code Formal.Options.defaults()}; null when the request names none (the defaults, as before).
     */
    static org.eninehq.tessera.formal.Formal.Options proveOptions(Map<String, Object> req) {
        var o = org.eninehq.tessera.formal.Formal.Options.defaults();
        boolean any = false;
        if (req.get("freeAsyncResets") instanceof Boolean b) { o = o.withFreeAsyncResets(b); any = true; }
        if (req.get("atLeastOneTick") instanceof Boolean b) { o = o.withAtLeastOneTick(b); any = true; }
        if (req.get("maxTickGap") instanceof Number n) {
            if (n.intValue() < 0) throw new IllegalArgumentException("maxTickGap is 0 (no bound) or a number of steps.");
            o = o.withMaxTickGap(n.intValue()); any = true;
        }
        if (req.get("resetCycles") instanceof Number n) {
            if (n.intValue() < 0) throw new IllegalArgumentException("resetCycles is 0 or more.");
            o = o.withResetCycles(n.intValue()); any = true;
        }
        if (req.get("arbitraryMemory") instanceof Boolean b) { o = o.withArbitraryMemory(b); any = true; }
        return any ? o : null;
    }

    private long traceFrom = -1;

    /** Replay a counterexample as the current run, so that the waves and the drawing show it, cycle by cycle. */
    private void loadTrace(org.eninehq.tessera.formal.Formal.Result r) {
        long start = cycle();
        if (sim != null) sim.close();
        sim = Sim.create(r.circuit(), Sim.Backend.COMPILED);
        sim.loadRegisterState(r.initialState());
        base = start;
        tt = new TimeTravel(sim, 16);
        if (r.ticks() != null) {
            // several clocks: replay (and later re-replay) the clock edges the prover chose for each cycle
            long first = sim.cycle();
            tt.stepper((s, c) -> {
                long i = c - first;
                if (i >= 0 && i < r.ticks().size()) s.stepClocks(r.ticks().get((int) i));
                else s.step();
            });
        }
        viewCycle = -1;
        running = false;
        traceFrom = start;
        waves.markKind(start, "counterexample of '" + r.check() + "' starts here (" + r.location() + ")", "trace");
        sampleAll();
        int k = 0;
        for (var row : r.counterexample()) {
            for (var en : row.entrySet()) tt.pokeBig(en.getKey(), en.getValue());
            sampleAll();
            if (k++ >= r.failCycle()) break;
            try { tt.step(); } catch (AssertionError err) { break; }
            sampleAll();
        }
        waves.markKind(cycle(), "'" + r.check() + "' fails here", "fail");
        lastMessage = "counterexample of '" + r.check() + "': " + (r.failCycle() + 1) + " cycles from reset; step back through it with the waves";
        runningCode = null;           // the next load starts the design afresh
        lastSent.clear();
        broadcast(frame(true));
    }

    // ---------------------------------------------------------------- waves

    /** The running design's elaboration again, with operation locations, for mapping timing paths (cached). */
    CriticalPath.Table timingTable() {
        java.util.function.Supplier<Elaborated<Module>> a;
        synchronized (this) {
            if (timingTable != null) return timingTable;
            a = again;
        }
        if (a == null) throw new IllegalArgumentException("Nothing is running yet.");
        var t = CriticalPath.table(a, Designs.LOC_SKIP);
        synchronized (this) { timingTable = t; }
        return t;
    }

    /** Elaborated again (as {@link #timingTable}) with its operation locations: what a timing run builds. */
    java.util.function.Supplier<Elaborated<Module>> again() {
        synchronized (this) {
            if (again == null) throw new IllegalArgumentException("Nothing is running yet.");
            return again;
        }
    }

    /**
     * A Vivado full-path timing report, mapped onto the running design by name: {@code {report}} gives
     * {@link CriticalPath#json}. The report must come from the same design (names are matched); the
     * wrapper instance Vivado's top level put it in (core, u, or none) is found by trying each.
     */
    public Map<String, Object> timing(Map<String, Object> req) {
        String report = String.valueOf(req.getOrDefault("report", ""));
        var paths = CriticalPath.parse(report);
        if (paths.isEmpty()) throw new IllegalArgumentException(CriticalPath.NOT_VIVADO);
        return CriticalPath.withSource(CriticalPath.best(paths, timingTable(), String.valueOf(req.getOrDefault("source", "a report"))),
                CriticalPath.sourceRoots(elaborator.projectSources()));
    }

    /** The running design's logic levels and, when asked, Yosys's resource estimates. */
    public Map<String, Object> analyse(Map<String, Object> req) {
        Elaborated<Module> e;
        synchronized (this) { e = design; }
        if (e == null) throw new IllegalArgumentException("Nothing is running yet.");
        var m = new LinkedHashMap<String, Object>(Analyse.levels(e));
        synchronized (this) { m.put("schedule", schedule()); }
        if (Boolean.TRUE.equals(req.get("yosys"))) m.put("resources", Analyse.resources(e));   // outside the lock: it takes seconds
        return m;
    }

    /**
     * A window of a memory's words at the cycle being shown: {@code {path, from, count}} gives
     * {@code {width, depth, from, words: [hex, ...], cycle}}. Before the history the simulator keeps,
     * memories cannot be shown (the wave record holds signals, not memories).
     */
    public synchronized Map<String, Object> mem(Map<String, Object> req) {
        if (sim == null) throw new IllegalArgumentException("Nothing is running yet.");
        String path = String.valueOf(req.get("path"));
        Integer i = sim.netlist().memsByPath().get(path);
        Integer lvt = i == null ? lvtFiles().get(path) : null;
        if (i == null && lvt == null) throw new IllegalArgumentException("There is no memory " + path + " in this design.");
        if (viewCycle >= 0 && viewCycle < reachable())
            throw new IllegalArgumentException("Memory contents are kept only from cycle " + reachable() + " on; go to a later cycle to see them.");
        if (lvt != null) {   // a live-value-table register file, read through its table
            var b0 = sim.netlist().mems().get(sim.netlist().memsByPath().get(path + "_b0_r0"));
            int from = Math.max(0, ((Number) req.getOrDefault("from", 0)).intValue());
            int count = Math.min(1024, Math.max(0, ((Number) req.getOrDefault("count", 256)).intValue()));
            int to = Math.min(b0.depth(), from + count);
            var words = new ArrayList<String>();
            for (long w : lvtWords(path, from, to)) words.add(Long.toHexString(w));
            var out = new LinkedHashMap<String, Object>();
            out.put("path", path);
            out.put("width", b0.width());
            out.put("depth", b0.depth());
            out.put("from", from);
            out.put("words", words);
            out.put("cycle", cycle());
            out.put("lvt", lvt);
            return out;
        }
        var m = sim.netlist().mems().get(i);
        int from = Math.max(0, ((Number) req.getOrDefault("from", 0)).intValue());
        int count = Math.min(1024, Math.max(0, ((Number) req.getOrDefault("count", 256)).intValue()));
        int to = Math.min(m.depth(), from + count), wpe = m.words();
        long[] raw = sim.memory(path);
        var words = new ArrayList<String>();
        for (int a = from; a < to; a++) {
            if (wpe == 1) { words.add(Long.toHexString(raw[a])); continue; }
            BigInteger v = BigInteger.ZERO;
            for (int k = wpe - 1; k >= 0; k--) v = v.shiftLeft(64).or(new BigInteger(Long.toUnsignedString(raw[a * wpe + k])));
            words.add(v.toString(16));
        }
        var out = new LinkedHashMap<String, Object>();
        out.put("path", path);
        out.put("width", m.width());
        out.put("depth", m.depth());
        out.put("from", from);
        out.put("words", words);
        out.put("cycle", cycle());
        return out;
    }

    /**
     * The pipeline diagram over {@code from..to} (default: the 40 cycles up to the one shown), from the
     * record: {@code {cycle, from, to, pipes: [...], schedule}}; see {@link PipeView}. Instructions are
     * read from a memory {@code imem} when the design has one.
     */
    public synchronized Map<String, Object> pipeview(Map<String, Object> req) {
        if (sim == null) throw new IllegalArgumentException("Nothing is running yet.");
        long now = cycle();
        long to = req.get("to") instanceof Number n ? Math.min(n.longValue(), base + sim.cycle()) : now;
        long from = req.get("from") instanceof Number n ? n.longValue() : to - 39;
        from = Math.max(Math.max(0, from), to - 400);
        if (waves.droppedBefore() > from) from = waves.droppedBefore();
        long[] imem = (!pipes.isEmpty() || !robs.isEmpty()) && sim.netlist().memsByPath().containsKey("imem") ? sim.memory("imem").clone() : null;
        java.util.function.LongFunction<String> text = pc -> imem == null || imem.length == 0 ? null : Rv32Disasm.of(imem[(int) ((pc >>> 2) % imem.length)]);
        var list = new ArrayList<Object>();
        for (var p : pipes) {
            var items = PipeView.follow(p, from, to, waves::at);
            list.add(PipeView.json(p, items, text));
        }
        var ooo = new ArrayList<Object>();
        for (var r : robs) ooo.add(OooView.json(r, OooView.follow(r, from, to, waves::at), text));
        var out = new LinkedHashMap<String, Object>();
        out.put("cycle", now);
        out.put("from", from);
        out.put("to", to);
        out.put("pipes", list);
        out.put("ooo", ooo);
        out.put("schedule", schedule());
        return out;
    }

    /**
     * The Level-2 pipeline generator's own account of what it inferred (forwarding, stalls, redirects,
     * halts), for each pipeline the running design keeps in a public field; empty for other designs.
     */
    List<Object> schedule() {
        var out = new ArrayList<Object>();
        if (design == null) return out;
        Object top = design.top();
        for (var f : pipelineFields(top)) {
            try {
                Object p = f.get(top);
                if (p == null) continue;
                String r = (String) p.getClass().getMethod("report").invoke(p);
                out.add(Map.of("field", f.getName(), "lines", r.lines().toList()));
            } catch (ReflectiveOperationException ex) {
                out.add(Map.of("field", f.getName(), "lines", List.of("(the report could not be read: " + ex + ")")));
            }
        }
        return out;
    }

    /** The design's public fields holding a Level-2 pipeline (single or dual issue). */
    private static List<java.lang.reflect.Field> pipelineFields(Object top) {
        var out = new ArrayList<java.lang.reflect.Field>();
        for (Class<?> c = top.getClass(); c != null && c != Object.class; c = c.getSuperclass())
            for (var f : c.getDeclaredFields())
                if (java.lang.reflect.Modifier.isPublic(f.getModifiers()) && List.of("org.eninehq.tessera.pipeline.InOrderPipeline",
                        "org.eninehq.tessera.pipeline.DualIssuePipeline", OOO_PIPELINE).contains(f.getType().getName())) out.add(f);
        return out;
    }

    /** The out-of-order generator: its report is the schedule's account; it has no stages to draw by name. */
    private static final String OOO_PIPELINE = "org.eninehq.tessera.pipeline.OutOfOrderPipeline";

    /**
     * A dual-issue core's pipeline as two lanes: lane 1 travels in registers its signals do not name, so the generator's
     * own probes ({@code DualIssuePipeline.laneProbes()}) replace the ones found by name.
     */
    private static List<PipeView.Pipe> dualProbes(List<PipeView.Pipe> pipes, Object top) {
        for (var f : pipelineFields(top)) {
            if (!f.getType().getName().equals("org.eninehq.tessera.pipeline.DualIssuePipeline")) continue;
            try {
                Object p = f.get(top);
                if (p == null) continue;
                var probes = new ArrayList<PipeView.Probe>();
                for (Object lp : (List<?>) p.getClass().getMethod("laneProbes").invoke(p)) {
                    var c = lp.getClass();
                    probes.add(new PipeView.Probe((Integer) c.getMethod("stage").invoke(lp), (Integer) c.getMethod("lane").invoke(lp),
                            (String) c.getMethod("valid").invoke(lp), (String) c.getMethod("hold").invoke(lp), (String) c.getMethod("pc").invoke(lp), null));
                }
                @SuppressWarnings("unchecked")
                var names = (List<String>) p.getClass().getMethod("stageNames").invoke(p);
                var out = new ArrayList<PipeView.Pipe>();
                for (var pipe : pipes) out.add(pipe.name().equals("core") ? new PipeView.Pipe("core", List.copyOf(names), 2, probes) : pipe);
                return out;
            } catch (ReflectiveOperationException ex) {
                return pipes;
            }
        }
        return pipes;
    }

    /** The stage names of the design's Level-2 pipeline, or null (the signals name the first stage "s0"). */
    private static List<String> scheduleStages(Object top) {
        for (var f : pipelineFields(top)) {
            if (f.getType().getName().equals(OOO_PIPELINE)) continue;
            try {
                Object p = f.get(top);
                if (p != null) {
                    @SuppressWarnings("unchecked")
                    var names = (List<String>) p.getClass().getMethod("stageNames").invoke(p);
                    return names;
                }
            } catch (ReflectiveOperationException ex) {
                return null;
            }
        }
        return null;
    }

    /** The memories of the running design: [{path, width, depth}]. */
    public synchronized List<Object> mems() {
        var l = new ArrayList<Object>();
        if (sim == null) return l;
        for (var m : sim.netlist().mems()) l.add(Map.of("path", m.path(), "width", m.width(), "depth", m.depth()));
        for (var e : lvtFiles().entrySet()) {
            var b0 = sim.netlist().mems().get(sim.netlist().memsByPath().get(e.getKey() + "_b0_r0"));
            l.add(Map.of("path", e.getKey(), "width", b0.width(), "depth", b0.depth(), "lvt", e.getValue()));
        }
        return l;
    }

    /**
     * The register files built as a live-value table (stdlib LiveValueTable) that have no memory of their own
     * name: {@code <name>_lvt} (the table of last writers) and banks {@code <name>_b<w>_r<r>}. By name, the number of
     * write banks. {@link #mem} reads such a name as the register file: word a is bank {@code lvt[a]}'s copy at a.
     */
    private Map<String, Integer> lvtFiles() {
        var by = sim.netlist().memsByPath();
        var out = new LinkedHashMap<String, Integer>();
        for (String p : by.keySet()) {
            if (!p.endsWith("_lvt")) continue;
            String base = p.substring(0, p.length() - 4);
            if (by.containsKey(base) || !by.containsKey(base + "_b0_r0")) continue;
            int w = 0;
            while (by.containsKey(base + "_b" + w + "_r0")) w++;
            out.put(base, w);
        }
        return out;
    }

    /** A live-value-table register file's words {@code from..to} (single-word entries: at most 64 bits wide). */
    private long[] lvtWords(String base, int from, int to) {
        var by = sim.netlist().memsByPath();
        long[] table = sim.memory(base + "_lvt");
        var banks = new ArrayList<long[]>();
        for (int w = 0; by.containsKey(base + "_b" + w + "_r0"); w++) banks.add(sim.memory(base + "_b" + w + "_r0"));
        long[] out = new long[Math.max(0, to - from)];
        for (int a = from; a < to; a++) {
            int w = (int) table[a];
            out[a - from] = w < banks.size() ? banks.get(w)[a] : 0;
        }
        return out;
    }

    /** The running design as SystemVerilog. */
    public Map<String, Object> sv() {
        Elaborated<Module> e;
        synchronized (this) { e = design; }
        if (e == null) throw new IllegalArgumentException("Nothing is running yet.");
        return Map.of("sv", Analyse.sv(e));
    }

    /**
     * The recorded waves of {@code paths} as FST (base64), on the clock's real time: a cycle is 1/hz
     * seconds, written in the coarsest unit (s, ms, us, ns, ps, fs) that holds it exactly.
     */
    public synchronized Map<String, Object> fst(Map<String, Object> req) {
        long hz = elaborator.simHz();
        int exp = 0;
        long per = 0;
        for (int e = 0; e >= -15; e -= 3) {
            var p = java.math.BigInteger.TEN.pow(-e);
            var qr = p.divideAndRemainder(java.math.BigInteger.valueOf(hz));
            if (qr[1].signum() == 0 && qr[0].signum() > 0) { exp = e; per = qr[0].longValueExact(); break; }
            if (e == -15) { exp = e; per = Math.max(1, Math.round(1e15 / hz)); }
        }
        long head = sim == null ? 0 : base + tt.head();
        var vars = new ArrayList<FstWriter.Var>();
        var names = new java.util.HashSet<String>();
        for (Object o : req.get("paths") instanceof List<?> l ? l : List.of()) {
            String path = String.valueOf(o);
            var w = waves.window(path, 0, head, Integer.MAX_VALUE / 4);
            if (w.get("missing") != null) continue;
            var times = new ArrayList<long[]>();
            var vals = new ArrayList<java.math.BigInteger>();
            for (Object c : (List<?>) w.get("changes")) {
                var cl = (List<?>) c;
                times.add(new long[]{((Number) cl.get(0)).longValue() * per});
                vals.add(new java.math.BigInteger(String.valueOf(cl.get(1)), 16));
            }
            String name = path.replaceAll("[^\\w.\\[\\]]", "_");
            while (!names.add(name)) name += "_";
            vars.add(new FstWriter.Var(name, ((Number) w.get("width")).intValue(), times, vals));
        }
        var m = new LinkedHashMap<String, Object>();
        String top = design == null ? "top" : design.circuit().top().name();
        m.put("fst", java.util.Base64.getEncoder().encodeToString(FstWriter.write(top, vars, exp)));
        m.put("unit", new String[]{"s", "ms", "us", "ns", "ps", "fs"}[-exp / 3]);
        m.put("perCycle", per);
        return m;
    }

    /**
     * 8N1 serial decoded from the recorded output {@code tx}, from cycle {@code from} on, at {@code baud}
     * (a bit is hz/baud cycles): each falling edge from idle starts a frame, its bits are sampled at their
     * middles, the stop bit must be 1. Returns the bytes with their start cycles and where the next call
     * should start (a frame not yet finished is left for it).
     */
    public synchronized Map<String, Object> uart(Map<String, Object> req) {
        String tx = String.valueOf(req.get("tx"));
        double cpb = (double) elaborator.simHz() / ((Number) req.get("baud")).doubleValue();
        if (cpb < 2) throw new IllegalArgumentException("At " + req.get("baud") + " baud a bit is " + String.format("%.2f", cpb) + " cycles of the " + elaborator.simHz() + " Hz clock; it needs at least 2.");
        long head = sim == null ? 0 : base + tt.head();
        long from = ((Number) req.getOrDefault("from", 0)).longValue();
        var w = waves.window(tx, Math.max(0, from - 1), head, Integer.MAX_VALUE / 4);
        if (w.get("missing") != null) throw new IllegalArgumentException(tx + " is not recorded.");
        var ch = new ArrayList<long[]>();   // [cycle, bit]
        for (Object o : (List<?>) w.get("changes")) { var l = (List<?>) o; ch.add(new long[]{((Number) l.get(0)).longValue(), new java.math.BigInteger(String.valueOf(l.get(1)), 16).testBit(0) ? 1 : 0}); }
        java.util.function.LongUnaryOperator at = (c) -> {
            long v = 1;    // before the first sample the line is taken to be idle, as a UART line is
            for (var x : ch) { if (x[0] <= c) v = x[1]; else break; }
            return v;
        };
        var bytes = new ArrayList<Object>();
        long next = from;
        for (int i = 0; i < ch.size(); i++) {
            long c = ch.get(i)[0];
            if (c < next || ch.get(i)[1] != 0 || at.applyAsLong(c - 1) != 1) continue;   // a fall from 1
            long end = c + (long) Math.ceil(10 * cpb);
            if (end > head) { next = c; break; }                                      // not finished yet
            if (at.applyAsLong(c + (long) (0.5 * cpb)) != 0) { next = c + 1; continue; }  // a glitch, not a start bit
            int v = 0;
            for (int b = 0; b < 8; b++) if (at.applyAsLong(c + (long) ((1.5 + b) * cpb)) == 1) v |= 1 << b;
            boolean stop = at.applyAsLong(c + (long) (9.5 * cpb)) == 1;
            bytes.add(List.of(c, v, stop));
            next = c + (long) (9.5 * cpb);
        }
        if (bytes.isEmpty() && next == from) next = Math.max(from, head - (long) Math.ceil(10 * cpb));
        var m = new LinkedHashMap<String, Object>();
        m.put("bytes", bytes);
        m.put("next", Math.max(next, from));
        m.put("cyclesPerBit", cpb);
        return m;
    }

    /** How many times each of {@code paths} changed in the last {@code span} cycles (up to the cycle shown). */
    public synchronized Map<String, Object> activity(Map<String, Object> req) {
        long span = ((Number) req.getOrDefault("span", 256)).longValue();
        long to = cycle(), from = Math.max(0, to - span);
        var counts = new LinkedHashMap<String, Object>();
        for (Object o : req.get("paths") instanceof List<?> l ? l : List.of()) {
            int c = waves.changes(String.valueOf(o), from, to);
            if (c >= 0) counts.put(String.valueOf(o), c);
        }
        return Map.of("counts", counts, "span", to - from, "cycle", to);
    }

    /**
     * A stream's transactions between {@code from} and {@code to}, from its recorded base_valid, base_ready
     * and base_bits: runs {c0, c1, kind, bits} where kind is "fire" (valid and ready: one transfer every
     * cycle of the run), "stall" (valid, not ready: the sender waits) or "starve" (ready, not valid).
     */
    public synchronized Map<String, Object> transactions(Map<String, Object> req) {
        String b = String.valueOf(req.get("stream"));
        long from = ((Number) req.getOrDefault("from", 0)).longValue(), to = ((Number) req.getOrDefault("to", cycle())).longValue();
        long head = sim == null ? 0 : base + tt.head();
        to = Math.min(to, head);
        var names = List.of(b + "_valid", b + "_ready", b + "_bits");
        var ch = new ArrayList<List<long[]>>();
        var bits = new java.util.TreeMap<Long, String>();
        for (int k = 0; k < 3; k++) {
            var w = waves.window(names.get(k), from, to, Integer.MAX_VALUE / 4);
            if (w.get("missing") != null) throw new IllegalArgumentException("The stream " + b + " needs " + names.get(k) + " in the record.");
            var l = new ArrayList<long[]>();
            for (Object o : (List<?>) w.get("changes")) {
                var c = (List<?>) o;
                long at = Math.max(from, ((Number) c.get(0)).longValue());
                if (k < 2) l.add(new long[]{at, new BigInteger(String.valueOf(c.get(1)), 16).signum() != 0 ? 1 : 0});
                else bits.put(at, String.valueOf(c.get(1)));
            }
            ch.add(l);
        }
        var cuts = new java.util.TreeSet<Long>();
        cuts.add(from);
        for (var l : ch) for (var x : l) cuts.add(x[0]);
        cuts.addAll(bits.keySet());
        var runs = new ArrayList<Object>();
        long fires = 0, stalls = 0;
        Long[] cs = cuts.toArray(new Long[0]);
        for (int i = 0; i < cs.length; i++) {
            long c0 = cs[i], c1 = i + 1 < cs.length ? cs[i + 1] : to + 1;
            if (c0 > to) break;
            long v = at(ch.get(0), c0), r = at(ch.get(1), c0);
            var be = bits.floorEntry(c0);
            String kind = v == 1 && r == 1 ? "fire" : v == 1 ? "stall" : r == 1 ? "starve" : null;
            if (kind == null) continue;
            if (kind.equals("fire")) fires += c1 - c0; else if (kind.equals("stall")) stalls += c1 - c0;
            // runs of one kind and one value join up
            if (!runs.isEmpty()) {
                var last = (Object[]) runs.getLast();
                if ((long) last[1] == c0 && last[2].equals(kind) && (kind.equals("starve") || last[3].equals(be == null ? "0" : be.getValue()))) { last[1] = c1; continue; }
            }
            runs.add(new Object[]{c0, c1, kind, be == null ? "0" : be.getValue()});
            if (runs.size() > 20_000) break;
        }
        var m = new LinkedHashMap<String, Object>();
        m.put("runs", runs.stream().map(o -> List.of((Object[]) o)).toList());
        m.put("fires", fires);
        m.put("stalls", stalls);
        return m;
    }

    private static long at(List<long[]> l, long c) {
        long v = 0;
        for (var x : l) { if (x[0] <= c) v = x[1]; else break; }
        return v;
    }

    /**
     * Run a testbench: a class whose {@code @Test} methods (JUnit's annotation) each take a {@link
     * org.eninehq.tessera.sim.Simulator}. They run in order against the live run, through a simulator that
     * pokes, steps and resets the way the toolbar does, so everything they do is in the waves and in the
     * history; JUnit's assertions (or any exception) fail a test, with its line. With {@code fromReset}
     * each test starts the way it does under JUnit: inputs at 0 and every reset held for two cycles.
     * The action {@code template} gives a new testbench for the running design, and {@code junit} the
     * class as a JUnit test for the project (see {@link #benchJUnit}).
     */
    public Map<String, Object> bench(Map<String, Object> req) {
        BenchDesign bd;
        synchronized (this) { bd = benchDesign; }
        switch (String.valueOf(req.getOrDefault("action", "run"))) {
            case "template" -> { return Map.of("source", benchTemplate(bd, String.valueOf(req.getOrDefault("in", "en")), String.valueOf(req.getOrDefault("out", "value")))); }
            case "junit" -> { return benchJUnit(bd, req); }
            default -> { }
        }
        boolean fromReset = Boolean.TRUE.equals(req.get("fromReset"));
        String src = String.valueOf(req.getOrDefault("source", ""));
        var cm = java.util.regex.Pattern.compile("\\bclass\\s+(\\w+)").matcher(src);
        if (!cm.find()) throw new IllegalArgumentException("The testbench needs a class.");
        String cls = cm.group(1);
        boolean pkg = java.util.regex.Pattern.compile("^\\s*package\\s", java.util.regex.Pattern.MULTILINE).matcher(src).find();
        int shift = pkg ? 0 : 1;
        Class<?> k;
        try {
            Path dir = elaborator.scratch("bench"), file = dir.resolve("src/studio_bench/" + cls + ".java");
            java.nio.file.Files.createDirectories(file.getParent());
            java.nio.file.Files.writeString(file, (pkg ? "" : "package studio_bench;\n") + src);
            var roots = new ArrayList<Path>();
            for (Path r : elaborator.projectSources()) if (java.nio.file.Files.isDirectory(r)) roots.add(r);
            roots.add(dir.resolve("src"));
            // the drawing's own class, so that a @Design method can name it (unless the project has it already)
            if (bd != null && bd.java() != null && projectFile(bd) == null) {
                Path d = dir.resolve("src").resolve(bd.pkg().replace('.', '/')).resolve(bd.cls() + ".java");
                java.nio.file.Files.createDirectories(d.getParent());
                java.nio.file.Files.writeString(d, bd.java());
            }
            var c = org.eninehq.tessera.cli.DesignCompiler.compile(roots, dir.resolve("classes"));
            if (!c.ok()) {
                var errs = new ArrayList<String>();
                for (String e : c.errors()) {
                    var lm = java.util.regex.Pattern.compile("^" + cls + "\\.java:(\\d+): (.*)$", java.util.regex.Pattern.DOTALL).matcher(e);
                    errs.add(lm.matches() ? "line " + (Integer.parseInt(lm.group(1)) - shift) + ": " + lm.group(2).lines().findFirst().orElse("") : e.lines().findFirst().orElse(""));
                }
                return Map.of("ok", false, "errors", errs);
            }
            k = Class.forName((pkg ? src.replaceAll("(?s).*?package\\s+([\\w.]+)\\s*;.*", "$1") + "." : "studio_bench.") + cls, true, c.loader());
        } catch (java.io.IOException | ClassNotFoundException e) {
            throw new IllegalArgumentException("The testbench could not be loaded: " + e.getMessage());
        }
        // the tests, in the order the source gives them
        var tests = new ArrayList<java.lang.reflect.Method>();
        for (var m : k.getDeclaredMethods())
            if (java.util.Arrays.stream(m.getAnnotations()).anyMatch(a -> a.annotationType().getName().equals("org.junit.jupiter.api.Test"))
                    && m.getParameterCount() == 1 && m.getParameterTypes()[0] == org.eninehq.tessera.sim.Simulator.class) tests.add(m);
        tests.sort(java.util.Comparator.comparingInt(m -> { int i = src.indexOf(" " + m.getName() + "("); return i < 0 ? Integer.MAX_VALUE : i; }));
        if (tests.isEmpty()) throw new IllegalArgumentException("No test to run: a test is a method marked @Test that takes a Simulator, such as @Test void counts(Simulator sim).");
        var results = new ArrayList<Object>();
        synchronized (this) {
            if (sim == null) throw new IllegalArgumentException("Nothing is running yet.");
            running = false;
            leavePast();
            for (var m : tests) {
                if (fromReset) {
                    // as under JUnit, where each test gets a new simulator: inputs at 0, then the resets
                    waves.markKind(cycle(), "test " + m.getName() + ": inputs to 0 and reset", "reload");
                    for (var p : design.circuit().top().ports())
                        if (p.kind() == org.eninehq.tessera.ir.Signal.Kind.INPUT && !p.isClockOrReset()) {
                            tt.pokeBig(p.name(), BigInteger.ZERO);
                            waves.sample(p.name(), cycle(), BigInteger.ZERO);
                        }
                    // memories as a new simulator has them: their initial contents, zero beyond (only the words
                    // that differ are written, through the history, so going back and forth replays them)
                    int restored = 0;
                    for (var mem : sim.netlist().mems()) {
                        long[] now = sim.memory(mem.path());
                        int w = mem.words();
                        for (int a = 0; a < mem.depth(); a++) {
                            long[] want = new long[w];
                            if (mem.init() != null && a < mem.init().length) want[0] = mem.init()[a];
                            if (!java.util.Arrays.equals(now, a * w, a * w + w, want, 0, w)) {
                                tt.pokeMemory(mem.path(), a, want);
                                restored++;
                            }
                        }
                    }
                    if (restored > 0) waves.markKind(cycle(), "test " + m.getName() + ": " + restored + " memory word" + (restored == 1 ? "" : "s")
                            + " back to the initial contents", "reload");
                    resetAll(2, true);
                    sampleAll();
                }
                long c0 = cycle();
                waves.markKind(c0, "test " + m.getName() + " starts", "trace");
                var r = new LinkedHashMap<String, Object>();
                r.put("test", m.getName());
                r.put("from", c0);
                try {
                    var ctor = k.getDeclaredConstructor();
                    ctor.setAccessible(true);
                    m.setAccessible(true);
                    m.invoke(ctor.newInstance(), benchSim());
                    r.put("ok", true);
                } catch (java.lang.reflect.InvocationTargetException e) {
                    Throwable t = e.getCause();
                    r.put("ok", false);
                    r.put("message", t instanceof AssertionError ? String.valueOf(t.getMessage()) : t.getClass().getSimpleName() + ": " + t.getMessage());
                    for (var el : t.getStackTrace()) if (el.getClassName().equals(k.getName())) { r.put("line", el.getLineNumber() - shift); break; }
                    waves.markKind(cycle(), "test " + m.getName() + " failed: " + r.get("message"), "fail");
                } catch (ReflectiveOperationException e) {
                    r.put("ok", false);
                    r.put("message", "The test class needs a constructor without arguments: " + e.getMessage());
                }
                r.put("to", cycle());
                results.add(r);
            }
            lastSent.clear();
            String f = frame(true);
            if (f != null) broadcast(f);
        }
        return Map.of("ok", true, "results", results);
    }

    private static final long BENCH_LIMIT = 5_000_000;

    /**
     * A new testbench for the running design, with one test that pokes input {@code in} and reads output
     * {@code out}. When the Studio knows how to make the design it adds a {@code @Design} method and
     * Tessera's JUnit extension, so the same source also runs under JUnit.
     */
    static String benchTemplate(BenchDesign bd, String in, String out) {
        boolean bound = bd != null && bd.make() != null;
        var sb = new StringBuilder("import org.eninehq.tessera.sim.Simulator;\n");
        if (bound) {
            if (bd.make().contains("Clock.hz")) sb.append("import org.eninehq.tessera.stdlib.Clock;\n");
            sb.append("import org.eninehq.tessera.test.Design;\nimport org.eninehq.tessera.test.HwTestExtension;\n");
        }
        sb.append("import org.junit.jupiter.api.Test;\n");
        if (bound) sb.append("import org.junit.jupiter.api.extension.ExtendWith;\n");
        if (bound && !bd.pkg().isEmpty()) sb.append("import ").append(bd.pkg()).append('.').append(bd.cls()).append(";\n");
        sb.append("import static org.junit.jupiter.api.Assertions.*;\n\n");
        if (bound) sb.append("// In the Studio the tests drive the live run; under JUnit (Gradle) each one starts from reset.\n")
                .append("@ExtendWith(HwTestExtension.class)\n");
        else sb.append("// To run these tests under JUnit too, add a @Design method that makes the design.\n");
        sb.append("class ").append(bd == null ? "Bench" : bd.cls() + "Test").append(" {\n");
        if (bound) sb.append("    @Design ").append(bd.cls()).append(" dut() { return ").append(bd.make()).append("; }\n\n");
        sb.append("    @Test\n    void firstTest(Simulator sim) {\n")
                .append("        sim.poke(\"").append(in).append("\", 1);\n")
                .append("        sim.step(10);\n")
                .append("        long v = sim.peek(\"").append(out).append("\");\n")
                .append("        assertTrue(v >= 0, \"").append(out).append(" is \" + v);\n")
                .append("    }\n}\n");
        return sb.toString();
    }

    /** The drawing's class in the project's sources, if it is there (saved as .java into a source root). */
    private Path projectFile(BenchDesign bd) {
        for (Path r : elaborator.projectSources()) {
            Path f = r.resolve(bd.pkg().replace('.', '/')).resolve(bd.cls() + ".java");
            if (java.nio.file.Files.isRegularFile(f)) return f;
        }
        return null;
    }

    /**
     * The testbench as a JUnit test of the project: with a package, and with Tessera's extension, which
     * gives each test a new simulator from reset. With a source root ending in {@code src/main/java} it goes
     * to the {@code src/test/java} beside it, written when {@code write} is true (and only over an existing
     * file with {@code overwrite}); otherwise the page downloads it.
     */
    private Map<String, Object> benchJUnit(BenchDesign bd, Map<String, Object> req) {
        String src = String.valueOf(req.getOrDefault("source", ""));
        var cm = java.util.regex.Pattern.compile("\\bclass\\s+(\\w+)").matcher(src);
        if (!cm.find()) throw new IllegalArgumentException("The testbench needs a class.");
        String cls = cm.group(1);
        // @Design or @TestsDesign, also written with the package (@org.eninehq.tessera.test.Design)
        if (!java.util.regex.Pattern.compile("@(?:[\\w.]+\\.)?(?:Design|TestsDesign)\\b").matcher(src).find())
            throw new IllegalArgumentException("Under JUnit a testbench must say which design it tests: add a @Design method that makes it,"
                    + " such as @Design Counter dut() { return new Counter(); } (“New from the ports” writes one).");
        var pm = java.util.regex.Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;").matcher(src);
        boolean hasPkg = pm.find();
        String pkg = hasPkg ? pm.group(1) : bd != null ? bd.pkg() : "studio";
        String out = hasPkg ? src : (pkg.isEmpty() ? "" : "package " + pkg + ";\n\n") + src;
        if (!out.contains("HwTestExtension")) {
            out = out.replaceFirst("(?m)^([ \\t]*(?:(?:public|final|abstract)\\s+)*class\\s+" + cls + "\\b)", "@ExtendWith(HwTestExtension.class)\n$1");
            // the imports go before the first import, else after the package line, else at the top
            String imports = "import org.eninehq.tessera.test.HwTestExtension;\nimport org.junit.jupiter.api.extension.ExtendWith;\n";
            var im = java.util.regex.Pattern.compile("(?m)^import\\s").matcher(out);
            var pk = java.util.regex.Pattern.compile("(?m)^\\s*package\\s+[\\w.]+\\s*;[^\\n]*\\n").matcher(out);
            if (im.find()) out = out.substring(0, im.start()) + imports + out.substring(im.start());
            else if (pk.find()) out = out.substring(0, pk.end()) + "\n" + imports + out.substring(pk.end());
            else out = imports + "\n" + out;
        }
        var r = new LinkedHashMap<String, Object>();
        r.put("ok", true);
        r.put("java", out);
        r.put("file", cls + ".java");
        Path root = null;
        for (Path p : elaborator.projectSources())
            if (p.toAbsolutePath().normalize().endsWith(Path.of("src", "main", "java"))) { root = p.toAbsolutePath().normalize(); break; }
        if (root == null) {
            r.put("note", "The Studio was started without a project source root (--src …/src/main/java), so the test is downloaded.");
            return r;
        }
        Path target = root.getParent().getParent().resolve("test/java").resolve(pkg.replace('.', '/')).resolve(cls + ".java");
        r.put("path", target.toString());
        boolean exists = java.nio.file.Files.exists(target);
        r.put("exists", exists);
        if (bd != null && bd.java() != null && pkg.equals(bd.pkg()) && projectFile(bd) == null)
            r.put("note", "The project has no " + bd.pkg().replace('.', '/') + "/" + bd.cls() + ".java yet: save the drawing as .java into "
                    + root.resolve(bd.pkg().replace('.', '/')) + " so that the test compiles.");
        boolean wrote = false;
        if (Boolean.TRUE.equals(req.get("write")) && (!exists || Boolean.TRUE.equals(req.get("overwrite")))) {
            try {
                java.nio.file.Files.createDirectories(target.getParent());
                java.nio.file.Files.writeString(target, out);
                wrote = true;
            } catch (java.io.IOException e) {
                throw new IllegalArgumentException("The test could not be written to " + target + ": " + e.getMessage());
            }
        }
        r.put("wrote", wrote);
        return r;
    }

    /** The simulator a testbench gets: the session's run, with its pokes logged and its steps recorded. */
    private org.eninehq.tessera.sim.Simulator benchSim() {
        long[] steps = {0};
        Runnable one = () -> {
            if (++steps[0] > BENCH_LIMIT) throw new IllegalStateException("The testbench ran " + BENCH_LIMIT + " cycles; it stops there.");
            if (!stepChecked()) throw new AssertionError("A design assertion failed at cycle " + cycle() + ": " + lastMessage);
            sampleAll();
        };
        return (org.eninehq.tessera.sim.Simulator) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{org.eninehq.tessera.sim.Simulator.class}, (proxy, m, args) -> {
                    String n = m.getName();
                    int ac = args == null ? 0 : args.length;
                    switch (n) {
                        case "poke", "pokeBig" -> {
                            if (!(args[0] instanceof String path)) throw new UnsupportedOperationException("A testbench pokes inputs by name: sim.poke(\"en\", 1).");
                            BigInteger v = args[1] instanceof BigInteger b ? b : BigInteger.valueOf((Long) args[1]);
                            tt.pokeBig(path, v);
                            waves.sample(path, cycle(), v);
                            return null;
                        }
                        case "step" -> {
                            if (ac == 0) one.run();
                            else if (args[0] instanceof Integer cyc) for (int i = 0; i < cyc; i++) one.run();
                            else throw new UnsupportedOperationException("A testbench steps all clocks: sim.step() or sim.step(n).");
                            return null;
                        }
                        case "reset" -> { resetAll((Integer) args[0], true); sampleAll(); return null; }
                        case "cycle" -> { return cycle(); }
                        case "peek" -> { if (args[0] instanceof String path) return sim.peek(path); throw new UnsupportedOperationException("A testbench reads signals by name: sim.peek(\"count\")."); }
                        case "peekBig" -> { if (args[0] instanceof String path) return sim.peekBig(path); throw new UnsupportedOperationException("A testbench reads signals by name."); }
                        case "memory" -> { if (args[0] instanceof String path) return sim.memory(path).clone(); throw new UnsupportedOperationException("A testbench reads memories by path."); }
                        case "clocks" -> { return sim.clocks(); }
                        case "coverHits" -> { return sim.coverHits(); }
                        case "netlist" -> { return sim.netlist(); }
                        case "toString" -> { return "the Studio's run at cycle " + cycle(); }
                        case "hashCode" -> { return System.identityHashCode(proxy); }
                        case "equals" -> { return proxy == args[0]; }
                        default -> {
                            if (m.isDefault()) return java.lang.reflect.InvocationHandler.invokeDefault(proxy, m, args);
                            throw new UnsupportedOperationException("sim." + n + " is not for testbenches in the Studio (it would bypass the run's history).");
                        }
                    }
                });
    }

    public synchronized Map<String, Object> waves(Map<String, Object> req) {
        var m = new LinkedHashMap<String, Object>();
        m.put("head", sim == null ? 0 : base + tt.head());
        if (req.get("list") != null) {
            m.put("signals", waves.widths());
            return m;
        }
        var sched = new LinkedHashMap<String, List<Object>>();
        for (var e : scheduled.entrySet()) for (var pv : e.getValue().entrySet()) sched.computeIfAbsent(pv.getKey(), k -> new ArrayList<>()).add(List.of(e.getKey(), pv.getValue().toString(16)));
        m.put("scheduled", sched);
        if (req.get("find") instanceof String text) {   // the next or previous cycle at which a condition holds
            var c = Cond.parse(text);
            long from = ((Number) req.getOrDefault("from", cycle())).longValue();
            int dir = ((Number) req.getOrDefault("dir", 1)).intValue() >= 0 ? 1 : -1;
            Long at = waves.find(c, from, dir, base + tt.head());
            m.put("cycle", at);
            return m;
        }
        long from = ((Number) req.getOrDefault("from", 0)).longValue();
        long to = ((Number) req.getOrDefault("to", cycle())).longValue();
        int cols = ((Number) req.getOrDefault("columns", 1000)).intValue();
        var out = new LinkedHashMap<String, Object>();
        for (Object o : req.get("paths") instanceof List<?> l ? l : List.of()) {
            String p = String.valueOf(o);
            out.put(p, waves.window(p, from, to, cols));
        }
        m.put("waves", out);
        m.put("marks", waves.marks());
        return m;
    }

    // ---------------------------------------------------------------- events

    /** Hold the connection open and send frames to it (server-sent events). */
    void stream(HttpExchange ex) throws IOException {
        ex.getResponseHeaders().set("Content-Type", "text/event-stream");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(200, 0);
        listeners.add(ex);
        String f;
        synchronized (this) {
            lastSent.clear();
            f = frame(true);
        }
        if (f != null) send(ex, f);
        while (!closed && listeners.contains(ex)) {
            try { Thread.sleep(1000); } catch (InterruptedException e) { break; }
        }
    }

    private void broadcast(String json) {
        for (var ex : listeners) send(ex, json);
    }

    private void send(HttpExchange ex, String json) {
        try {
            OutputStream o = ex.getResponseBody();
            synchronized (ex) {
                o.write(("data: " + json + "\n\n").getBytes(StandardCharsets.UTF_8));
                o.flush();
            }
        } catch (IOException e) {
            listeners.remove(ex);
        }
    }

    @Override public synchronized void close() {
        closed = true;
        loop.interrupt();
        if (sim != null) sim.close();
        for (var ex : listeners) ex.close();
    }
}
