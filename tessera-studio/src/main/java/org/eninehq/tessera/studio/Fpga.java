package org.eninehq.tessera.studio;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import org.eninehq.tessera.emit.SvEmitter;
import org.eninehq.tessera.ir.Signal;

/**
 * Builds a drawing for the ZedBoard's programmable logic and programs the board, on the host the board
 * is attached to (Vivado 2020.1 in Docker there, as {@code scripts/zed.sh} does).
 *
 * <p>The drawing is elaborated again for the board's 100 MHz clock (so a one-second blink blinks once a
 * second), emitted as SystemVerilog, and wrapped in a top level that puts ports named {@code led*} on
 * LD0-LD7, {@code sw*} on SW0-SW7 and {@code btn*} on the push buttons, with a power-on reset. The LED pins
 * are the ones this repository's board tests use (bank 33, 3.3 V); the switch and button pins and their
 * 1.8 V standard are the ZedBoard master constraints' defaults, and they are inputs only.
 */
final class Fpga {
    static final long BOARD_HZ = 100_000_000L;
    private static final String[] LD = {"T22", "T21", "U22", "U21", "V22", "W22", "U19", "U14"};
    private static final String[] SW = {"F22", "G22", "H22", "F21", "H19", "H18", "H17", "M15"};
    private static final String[][] BTN = {{"BTNC", "P16"}, {"BTNU", "T18"}, {"BTNL", "N15"}, {"BTNR", "R18"}, {"BTND", "R16"}};
    private static final String IMG = "kkrizka/vivado:2020.1";
    private static final String VIV = "source /opt/Xilinx/Vivado/2020.1/settings64.sh; export DISPLAY=:99";

    /** A build in progress or done: what it said, and what it found. */
    static final class Job {
        final int id;
        volatile String state = "running";      // running, built, programmed, failed
        final List<String> log = java.util.Collections.synchronizedList(new ArrayList<>());
        final Map<String, Object> result = new ConcurrentHashMap<>();
        /** The design's name table, to map the build's timing paths back to it. */
        volatile CriticalPath.Table names;
        Job(int id) { this.id = id; }
        void say(String s) { log.add(s); if (log.size() > 400) log.removeFirst(); }
        Map<String, Object> json() {
            var m = new LinkedHashMap<String, Object>();
            m.put("job", id); m.put("state", state);
            synchronized (log) { m.put("log", new ArrayList<>(log)); }
            m.put("result", new LinkedHashMap<>(result));
            return m;
        }
    }

    private final List<Path> sources;
    private final String host = System.getenv().getOrDefault("ZED_HOST", "user@zed-host");
    private final String dir = System.getenv().getOrDefault("ZED_DIR", "tessera-builds") + "/studio";
    private final Map<Integer, Job> jobs = new ConcurrentHashMap<>();
    private final AtomicInteger next = new AtomicInteger(1);

    Fpga(List<Path> sources) { this.sources = sources; }

    /** What the board would do with the drawing's ports, before building: {map, warnings}. */
    static Map<String, Object> plan(List<Signal> ports) {
        var map = new ArrayList<Object>();
        var warn = new ArrayList<String>();
        int led = 0, sw = 0, btn = 0;
        for (Signal p : ports) {
            if (p.isClockOrReset()) continue;
            String n = p.name().toLowerCase();
            boolean in = p.kind() == Signal.Kind.INPUT;
            String where;
            if (!in && n.startsWith("led")) { where = led + p.width() <= 8 ? "LD" + led + (p.width() > 1 ? "–LD" + (led + p.width() - 1) : "") : null; led += p.width(); }
            else if (in && (n.startsWith("sw") || n.startsWith("switch"))) { where = sw + p.width() <= 8 ? "SW" + sw + (p.width() > 1 ? "–SW" + (sw + p.width() - 1) : "") : null; sw += p.width(); }
            else if (in && (n.startsWith("btn") || n.startsWith("button"))) { where = btn + p.width() <= 5 ? String.join(", ", java.util.Arrays.stream(BTN, btn, btn + p.width()).map(b -> b[0]).toList()) : null; btn += p.width(); }
            else where = in ? "tied to 0" : "not connected";
            if (where == null) warn.add("The board has no room for " + p.name() + " (" + p.width() + " bits): 8 LEDs, 8 switches and 5 buttons in all.");
            else if (where.equals("tied to 0")) warn.add("Input " + p.name() + " has no board name (sw…, btn…), so it is tied to 0.");
            else if (where.equals("not connected")) warn.add("Output " + p.name() + " has no board name (led…), so the board does not show it.");
            map.add(Map.of("port", p.name(), "dir", in ? "in" : "out", "width", p.width(), "board", where == null ? "no room" : where));
        }
        var m = new LinkedHashMap<String, Object>();
        m.put("map", map);
        m.put("warnings", warn);
        m.put("ok", warn.stream().noneMatch(w -> w.startsWith("The board has no room")));
        return m;
    }

    /** The port plan for a drawing, before building. */
    Map<String, Object> planFor(Map<String, Object> doc) {
        var gen = CodeGen.generate(doc);
        if (!gen.ok()) throw new IllegalArgumentException("The drawing has problems; fix them before building for the board.");
        var out = new Elaborator(sources, BOARD_HZ).elaborate(gen, String.valueOf(doc.getOrDefault("package", "studio")));
        if (!out.ok()) throw new IllegalArgumentException("The drawing does not elaborate for the board's clock: " + out.problems().getFirst().message());
        var m = plan(out.design().circuit().top().ports());
        m.put("host", host);
        return m;
    }

    /** Elaborate the drawing for the board, check its ports, and start building (and programming, when asked). */
    @SuppressWarnings("unchecked")
    Map<String, Object> start(Map<String, Object> doc, boolean program) throws IOException {
        var gen = CodeGen.generate(doc);
        if (!gen.ok()) throw new IllegalArgumentException("The drawing has problems; fix them before building for the board.");
        var locs = new java.util.IdentityHashMap<org.eninehq.tessera.ir.Expr, org.eninehq.tessera.core.SourceLoc>();
        var out = org.eninehq.tessera.core.OpLocations.record(locs, Designs.LOC_SKIP,
                () -> new Elaborator(sources, BOARD_HZ).elaborate(gen, String.valueOf(doc.getOrDefault("package", "studio"))));
        if (!out.ok()) throw new IllegalArgumentException("The drawing does not elaborate for the board's clock: " + out.problems().getFirst().message());
        var top = out.design().circuit().top();
        var plan = plan(top.ports());
        if (!(Boolean) plan.get("ok")) throw new IllegalArgumentException(String.join(" ", (List<String>) plan.get("warnings")));
        Path work = Files.createTempDirectory("tessera-studio-fpga");
        Files.writeString(work.resolve("design.sv"), SvEmitter.emit(out.design()));
        Files.writeString(work.resolve("top.v"), wrapper(top.name(), top.ports()));
        Files.writeString(work.resolve("pins.xdc"), xdc());
        Files.writeString(work.resolve("build.tcl"), BUILD_TCL);
        Files.writeString(work.resolve("program.tcl"), PROGRAM_TCL);
        var job = new Job(next.getAndIncrement());
        jobs.put(job.id, job);
        job.names = new CriticalPath.Table(SvEmitter.names(out.design().circuit(), locs), SvEmitter.topName(out.design().circuit()));
        job.result.put("module", top.name());
        job.result.put("plan", plan);
        Thread.ofVirtual().start(() -> run(job, work, program));
        return job.json();
    }

    /**
     * Times a design out of context with Vivado on the build host: synthesis, placement and routing for
     * a clock of {@code period} ns on its {@code clock} port, with every port kept, then its worst {@code n}
     * paths mapped back to the design ({@code result.timing}, as {@link CriticalPath#json}).
     */
    Map<String, Object> startTiming(java.util.function.Supplier<org.eninehq.tessera.core.Elaborated<org.eninehq.tessera.core.Module>> elaborate,
                                    double period, int n) throws IOException {
        if (!(period > 0 && period < 1000)) throw new IllegalArgumentException("The clock period is in ns, between 0 and 1000.");
        var locs = new java.util.IdentityHashMap<org.eninehq.tessera.ir.Expr, org.eninehq.tessera.core.SourceLoc>();
        var e = org.eninehq.tessera.core.OpLocations.record(locs, Designs.LOC_SKIP, elaborate);
        var c = e.circuit();
        if (c.top().ports().stream().noneMatch(p -> p.name().equals("clock")))
            throw new IllegalArgumentException("Timing needs the design's clock on a port named clock.");
        Path work = Files.createTempDirectory("tessera-studio-timing");
        String top = SvEmitter.write(c, work.resolve("design.sv"));
        Files.writeString(work.resolve("timing.tcl"), TIMING_TCL);
        var job = new Job(next.getAndIncrement());
        jobs.put(job.id, job);
        job.names = new CriticalPath.Table(SvEmitter.names(c, locs), top);
        job.result.put("module", top);
        job.result.put("period", period);
        int paths = Math.max(1, Math.min(100, n));
        Thread.ofVirtual().start(() -> runTiming(job, work, top, period, paths));
        return job.json();
    }

    private void runTiming(Job job, Path work, String top, double period, int n) {
        String d = dir + "/timing-" + job.id;
        try {
            job.say("Copying the design to " + host + ":" + d);
            sh(job, List.of("ssh", "-o", "ConnectTimeout=10", host, "rm -rf " + d + " && mkdir -p " + d));
            sh(job, List.of("scp", "-q", work.resolve("design.sv").toString(), work.resolve("timing.tcl").toString(), host + ":" + d + "/"));
            job.say("Out-of-context synthesis, placement and routing (Vivado 2020.1) for a " + period + " ns clock…");
            long t0 = System.nanoTime();
            sh(job, List.of("ssh", host, "cd " + d + " && sudo docker run --rm --cpuset-cpus=${ZED_CPUS:-16-31} -v $PWD:/w -w /w " + IMG
                    + " bash -c '" + VIV + "; vivado -mode batch -nojournal -nolog -source timing.tcl -tclargs " + top + " " + period + " " + n
                    + " > vivado.log 2>&1; grep -E \"^QOR|^ERROR\" vivado.log | head -20'; sudo chown -R $(id -u):$(id -g) ."));
            job.result.put("buildSeconds", (System.nanoTime() - t0) / 1_000_000_000);
            readQor(job);
            String report = capture(List.of("ssh", host, "cat " + d + "/paths.rpt"));
            var paths = CriticalPath.parse(report);
            if (paths.isEmpty()) { job.state = "failed"; job.say("Vivado wrote no timing paths; vivado.log is in " + d + " on " + host + "."); return; }
            if (paths.stream().allMatch(p -> Double.isInfinite(p.slack())))
                job.say("Vivado timed no register-to-register path: every path in its report starts at an input port, which out of context has no input delay"
                        + " (a design that fits in a DSP's own registers has no other paths).");
            job.result.put("timing", CriticalPath.withSource(CriticalPath.json(paths, job.names, "", "Vivado out of context, " + period + " ns, " + host),
                    CriticalPath.sourceRoots(sources)));
            job.state = "built";
            job.say("Timed: worst slack " + job.result.get("wns") + " ns; " + paths.size() + " paths.");
        } catch (Exception e) {
            job.state = "failed";
            job.say("Failed: " + e.getMessage());
        } finally {
            Elaborator.deleteTree(work);
        }
    }

    private static void readQor(Job job) {
        synchronized (job.log) {
            for (String l : job.log) {
                var m = QOR.matcher(l);
                if (m.find()) { job.result.put("luts", Integer.parseInt(m.group(1))); job.result.put("ffs", Integer.parseInt(m.group(2))); job.result.put("brams", m.group(3)); job.result.put("dsps", Integer.parseInt(m.group(4))); job.result.put("wns", Double.parseDouble(m.group(5))); }
            }
        }
    }

    /** A command's output, not logged (a report). */
    private static String capture(List<String> cmd) throws IOException, InterruptedException {
        var p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String s = new String(p.getInputStream().readAllBytes());
        int rc = p.waitFor();
        if (rc != 0) throw new IOException(String.join(" ", cmd.subList(0, Math.min(2, cmd.size()))) + " exited with " + rc);
        return s;
    }

    Map<String, Object> status(int id) {
        var j = jobs.get(id);
        if (j == null) throw new IllegalArgumentException("No build " + id + ".");
        return j.json();
    }

    private static final Pattern QOR = Pattern.compile("QOR LUT (\\d+) FF (\\d+) BRAM (\\S+) DSP (\\d+) WNS (\\S+)");

    private void run(Job job, Path work, boolean program) {
        try {
            job.say("Copying the design to " + host + ":" + dir);
            sh(job, List.of("ssh", "-o", "ConnectTimeout=10", host, "mkdir -p " + dir + " && rm -f " + dir + "/top.bit " + dir + "/paths.rpt"));
            var scp = new ArrayList<>(List.of("scp", "-q"));
            for (String f : List.of("design.sv", "top.v", "pins.xdc", "build.tcl", "program.tcl")) scp.add(work.resolve(f).toString());
            scp.add(host + ":" + dir + "/");
            sh(job, scp);
            job.say("Synthesis, placement, routing and the bitstream (Vivado 2020.1)…");
            long t0 = System.nanoTime();
            sh(job, List.of("ssh", host, "cd " + dir + " && sudo docker run --rm --cpuset-cpus=${ZED_CPUS:-16-31} -v $PWD:/w -w /w " + IMG
                    + " bash -c '" + VIV + "; vivado -mode batch -nojournal -nolog -source build.tcl > vivado.log 2>&1; grep -E \"^QOR|^ERROR|TESSERA_PL_DONE|CRITICAL WARNING\" vivado.log | head -20'; sudo chown -R $(id -u):$(id -g) ."));
            job.result.put("buildSeconds", (System.nanoTime() - t0) / 1_000_000_000);
            boolean built = false;
            synchronized (job.log) {
                for (String l : job.log) {
                    var m = QOR.matcher(l);
                    if (m.find()) { job.result.put("luts", Integer.parseInt(m.group(1))); job.result.put("ffs", Integer.parseInt(m.group(2))); job.result.put("brams", m.group(3)); job.result.put("dsps", Integer.parseInt(m.group(4))); job.result.put("wns", Double.parseDouble(m.group(5))); }
                    if (l.contains("TESSERA_PL_DONE")) built = true;
                }
            }
            if (!built) { job.state = "failed"; job.say("The build did not finish; vivado.log is in " + dir + " on " + host + "."); return; }
            // the worst paths, mapped back to the drawing (the drawing is instance u of the board's top level)
            try {
                var paths = CriticalPath.parse(capture(List.of("ssh", host, "cat " + dir + "/paths.rpt")));
                if (!paths.isEmpty()) job.result.put("timing", CriticalPath.withSource(CriticalPath.json(paths, job.names, "u",
                        "the board build (Vivado, 100 MHz), " + host), CriticalPath.sourceRoots(sources)));
            } catch (IOException e) {
                job.say("The timing paths could not be read back: " + e.getMessage());
            }
            job.state = "built";
            job.say("Built: " + job.result.get("luts") + " LUTs, " + job.result.get("ffs") + " flip-flops, worst slack " + job.result.get("wns") + " ns at 100 MHz.");
            if (!program) return;
            job.say("Programming the board over JTAG…");
            // as XsctBus does: xsct asks for xlsclients, which a stub in the host's fakebin answers
            sh(job, List.of("ssh", host, "cd " + dir + " && sudo docker run --rm --privileged --net=host -v /dev/bus/usb:/dev/bus/usb -v $HOME/tessera-builds:/z -v $PWD:/w -w /w " + IMG
                    + " bash -c '" + VIV + "; export PATH=/z/fakebin:/opt/Xilinx/Vitis/2020.1/bin:$PATH; xsct program.tcl'"));
            boolean done;
            synchronized (job.log) { done = job.log.stream().anyMatch(l -> l.startsWith("TESSERA_PROGRAMMED")); }
            job.state = done ? "programmed" : "failed";
            if (!done) job.say("Programming did not report success.");
        } catch (Exception e) {
            job.state = "failed";
            job.say("Failed: " + e.getMessage());
        } finally {
            Elaborator.deleteTree(work);
        }
    }

    private static void sh(Job job, List<String> cmd) throws IOException, InterruptedException {
        var p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        try (var r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            for (String l; (l = r.readLine()) != null; ) job.say(l);
        }
        int rc = p.waitFor();
        if (rc != 0) throw new IOException(String.join(" ", cmd.subList(0, Math.min(2, cmd.size()))) + " exited with " + rc);
    }

    // ---------------------------------------------------------------- the files

    static String wrapper(String module, List<Signal> ports) {
        var sb = new StringBuilder("// The drawing on the ZedBoard: led* on LD0-7, sw* on SW0-7, btn* on the buttons; a power-on reset.\n");
        sb.append("module zed_pl_top(input wire GCLK, output wire [7:0] LD, input wire [7:0] SW");
        for (var b : BTN) sb.append(", input wire ").append(b[0]);
        sb.append(");\n  reg [7:0] por = 8'd0;\n  wire reset = ~&por;\n  always @(posedge GCLK) if (reset) por <= por + 8'd1;\n");
        int led = 0, sw = 0, btn = 0;
        var conns = new ArrayList<String>(List.of(".clock(GCLK)", ".reset(reset)"));
        for (Signal p : ports) {
            if (p.isClockOrReset()) continue;
            String n = p.name().toLowerCase();
            boolean in = p.kind() == Signal.Kind.INPUT;
            int w = p.width();
            String range = w == 1 ? "" : "[" + (w - 1) + ":0]";
            if (!in && n.startsWith("led")) { sb.append("  wire ").append(range).append(" o_").append(p.name()).append(";\n  assign LD[").append(led + w - 1).append(":").append(led).append("] = o_").append(p.name()).append(";\n"); conns.add("." + p.name() + "(o_" + p.name() + ")"); led += w; }
            else if (!in) { sb.append("  wire ").append(range).append(" o_").append(p.name()).append(";   // not on the board\n"); conns.add("." + p.name() + "(o_" + p.name() + ")"); }
            else if (n.startsWith("sw") || n.startsWith("switch")) { conns.add("." + p.name() + "(SW[" + (sw + w - 1) + ":" + sw + "])"); sw += w; }
            else if (n.startsWith("btn") || n.startsWith("button")) {
                var bits = new ArrayList<String>();
                for (int k = w - 1; k >= 0; k--) bits.add(BTN[btn + k][0]);
                conns.add("." + p.name() + "({" + String.join(", ", bits) + "})"); btn += w;
            } else conns.add("." + p.name() + "(" + w + "'d0)");
        }
        if (led < 8) sb.append("  assign LD[7:").append(led).append("] = ").append(8 - led).append("'d0;\n");
        sb.append("  ").append(module).append(" u(").append(String.join(", ", conns)).append(");\nendmodule\n");
        return sb.toString();
    }

    static String xdc() {
        var sb = new StringBuilder("# ZedBoard: the 100 MHz oscillator; LD0-7 (bank 33, 3.3 V, as the board tests use);\n"
                + "# SW0-7 and the buttons (banks 35 and 34, 1.8 V: the master constraints' default; inputs only)\n");
        sb.append("set_property PACKAGE_PIN Y9 [get_ports GCLK]\nset_property IOSTANDARD LVCMOS33 [get_ports GCLK]\ncreate_clock -period 10.000 -name gclk [get_ports GCLK]\n");
        for (int i = 0; i < 8; i++) sb.append("set_property PACKAGE_PIN ").append(LD[i]).append(" [get_ports {LD[").append(i).append("]}]\nset_property IOSTANDARD LVCMOS33 [get_ports {LD[").append(i).append("]}]\n");
        for (int i = 0; i < 8; i++) sb.append("set_property PACKAGE_PIN ").append(SW[i]).append(" [get_ports {SW[").append(i).append("]}]\nset_property IOSTANDARD LVCMOS18 [get_ports {SW[").append(i).append("]}]\n");
        for (var b : BTN) sb.append("set_property PACKAGE_PIN ").append(b[1]).append(" [get_ports ").append(b[0]).append("]\nset_property IOSTANDARD LVCMOS18 [get_ports ").append(b[0]).append("]\n");
        return sb.toString();
    }

    static final String BUILD_TCL = """
            set_param general.maxThreads 8
            read_verilog -sv design.sv
            read_verilog top.v
            read_xdc pins.xdc
            synth_design -top zed_pl_top -part xc7z020clg484-1
            opt_design
            place_design
            route_design
            report_utilization -file util.rpt
            report_timing_summary -file timing.rpt
            report_timing -max_paths 20 -nworst 1 -unique_pins -setup -path_type full -file paths.rpt
            set wns [get_property SLACK [get_timing_paths -max_paths 1 -nworst 1 -setup]]
            set u [report_utilization -return_string]
            proc cell {u name} { if {[regexp "\\\\| $name\\\\s+\\\\|\\\\s+(\\\\d+)" $u -> v]} { return $v } else { return 0 } }
            puts "QOR LUT [cell $u {Slice LUTs\\*?}] FF [cell $u {Slice Registers}] BRAM [cell $u {Block RAM Tile}] DSP [cell $u DSPs] WNS $wns"
            write_bitstream -force top.bit
            puts TESSERA_PL_DONE
            """;

    /** Out of context, as tessera-fpga/vivado/qor_paths.tcl: tclargs top, period (ns), number of paths. */
    static final String TIMING_TCL = """
            set top [lindex $argv 0]; set period [lindex $argv 1]; set n [lindex $argv 2]
            set_param general.maxThreads 8
            read_verilog -sv design.sv
            synth_design -top $top -part xc7z020clg484-1 -mode out_of_context -flatten_hierarchy rebuilt
            create_clock -name clk -period $period [get_ports clock]
            opt_design
            place_design
            phys_opt_design
            route_design
            report_utilization -file util.rpt
            report_timing -max_paths $n -nworst 1 -unique_pins -setup -path_type full -file paths.rpt
            set wns [get_property SLACK [get_timing_paths -max_paths 1 -nworst 1 -setup]]
            set u [report_utilization -return_string]
            proc cell {u name} { if {[regexp "\\\\| $name\\\\s+\\\\|\\\\s+(\\\\d+)" $u -> v]} { return $v } else { return 0 } }
            puts "QOR LUT [cell $u {Slice LUTs\\*?}] FF [cell $u {Slice Registers}] BRAM [cell $u {Block RAM Tile}] DSP [cell $u DSPs] WNS $wns"
            """;

    static final String PROGRAM_TCL = """
            connect
            targets -set -nocase -filter {name =~ "xc7z020*"}
            fpga -file top.bit
            puts "TESSERA_PROGRAMMED [fpga -state]"
            """;
}
