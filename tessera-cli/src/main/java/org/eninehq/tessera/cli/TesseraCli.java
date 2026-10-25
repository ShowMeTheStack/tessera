package org.eninehq.tessera.cli;

import java.io.PrintStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import org.eninehq.tessera.core.Elaborated;
import org.eninehq.tessera.core.Module;
import org.eninehq.tessera.core.Tessera;
import org.eninehq.tessera.core.TesseraException;
import org.eninehq.tessera.emit.SvEmitter;
import org.eninehq.tessera.formal.Btor2Emitter;
import org.eninehq.tessera.formal.Formal;
import org.eninehq.tessera.sim.Netlist;
import org.eninehq.tessera.sim.Sim;
import org.eninehq.tessera.sim.TypeSidecar;
import org.eninehq.tessera.test.Verilator;
import org.eninehq.tessera.test.Yosys;

/**
 * The {@code tessera} command line.
 * <pre>
 * tessera emit   --top com.example.Blinky [--out build/tessera]
 * tessera lint   --top com.example.Blinky
 * tessera synth  --top com.example.Blinky [--target generic|xc7|ice40|ecp5]
 * tessera formal --top com.example.Fifo --depth 20 [--prove 4]
 * tessera btor2  --top com.example.Fifo [--out build/tessera]
 * tessera sim    --top com.example.Blinky --cycles 1000 [--trace waves.fst]
 * </pre>
 * {@code --top} names a module class with a no-argument constructor, or a static factory
 * method {@code com.example.Designs::blinky} that returns a module.
 */
public final class TesseraCli {
    private TesseraCli() {}

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    /** Runs a command; returns the exit status (0 ok, 1 check failed, 2 usage or elaboration error). */
    public static int run(String[] args, PrintStream out, PrintStream err) {
        if (args.length == 0 || args[0].equals("--help") || args[0].equals("help")) {
            out.println(USAGE);
            return args.length == 0 ? 2 : 0;
        }
        String cmd = args[0];
        Map<String, String> opt = new LinkedHashMap<>();
        for (int i = 1; i < args.length; i++) {
            if (!args[i].startsWith("--")) {
                err.println("unexpected argument " + args[i]);
                return 2;
            }
            String k = args[i].substring(2);
            String v = i + 1 < args.length && !args[i + 1].startsWith("--") ? args[++i] : "true";
            opt.put(k, v);
        }
        try {
            if (cmd.equals("fix")) return fix(opt, out, err);
            String top = opt.get("top");
            if (top == null) {
                err.println("missing --top <class> (or <class>::<method>)");
                return 2;
            }
            Elaborated<Module> e = Tessera.elaborate(factory(top));
            Path outDir = Path.of(opt.getOrDefault("out", "build/tessera"));
            String name = e.circuit().top().name();
            return switch (cmd) {
                case "emit" -> {
                    Files.createDirectories(outDir);
                    Path sv = outDir.resolve(name + ".sv");
                    SvEmitter.write(e.circuit(), sv);
                    TypeSidecar.write(Netlist.build(e.circuit()), sv);
                    out.println("wrote " + sv);
                    yield 0;
                }
                case "lint" -> {
                    Verilator.lint(e.circuit(), "lint-" + name);
                    out.println(name + ": verilator -Wall clean");
                    yield 0;
                }
                case "synth" -> {
                    String target = opt.getOrDefault("target", "generic");
                    String script = switch (target) {
                        case "generic" -> "synth -top {top}";
                        case "xc7" -> "synth_xilinx -family xc7 -top {top}";
                        case "ice40" -> "synth_ice40 -top {top}";
                        case "ecp5" -> "synth_ecp5 -top {top}";
                        default -> throw new IllegalArgumentException("unknown target " + target);
                    };
                    var st = Yosys.synth(e.circuit(), "synth-" + name + "-" + target, script);
                    out.println(name + " (" + target + "): " + st.cells() + " cells");
                    st.byType().forEach((k, v) -> out.println("  " + k + " " + v));
                    yield 0;
                }
                case "formal" -> {
                    int depth = Integer.parseInt(opt.getOrDefault("depth", "20"));
                    var r = Formal.bmc(e.circuit(), depth, Formal.Options.defaults());
                    out.println(r);
                    if (r.failed()) {
                        Files.createDirectories(outDir);
                        Path vcd = outDir.resolve(name + "-cex.vcd");
                        r.writeTrace(vcd);
                        out.println("counterexample: " + vcd);
                        for (int t = 0; t < r.counterexample().size(); t++) out.println("  cycle " + t + ": " + r.counterexample().get(t));
                        yield 1;
                    }
                    if (opt.containsKey("prove")) {
                        var p = Formal.prove(e.circuit(), Integer.parseInt(opt.get("prove")), Formal.Options.defaults());
                        out.println(p);
                        yield p.status() == Formal.Status.PROVEN ? 0 : 1;
                    }
                    yield 0;
                }
                case "btor2" -> {
                    Files.createDirectories(outDir);
                    Path f = outDir.resolve(name + ".btor2");
                    Files.writeString(f, Btor2Emitter.emit(Netlist.build(e.circuit())).text());
                    out.println("wrote " + f);
                    yield 0;
                }
                case "sim" -> {
                    long cycles = Long.parseLong(opt.getOrDefault("cycles", "100"));
                    try (var sim = Sim.create(e, Sim.Backend.COMPILED)) {
                        if (opt.containsKey("trace")) sim.trace(Path.of(opt.get("trace")));
                        sim.reset(1);
                        long t0 = System.nanoTime();
                        sim.step((int) cycles);
                        double s = (System.nanoTime() - t0) / 1e9;
                        out.printf("simulated %d cycles in %.3f s (%.0f kHz)%n", cycles, s, cycles / s / 1e3);
                        for (var p : e.circuit().top().ports())
                            if (p.kind() == org.eninehq.tessera.ir.Signal.Kind.OUTPUT)
                                out.println("  " + p.name() + " = 0x" + sim.peekBig(p.name()).toString(16));
                    }
                    yield 0;
                }
                default -> {
                    err.println("unknown command " + cmd + "\n" + USAGE);
                    yield 2;
                }
            };
        } catch (TesseraException ex) {
            err.println(ex.getMessage());
            return 2;
        } catch (AssertionError ex) {
            err.println(ex.getMessage());
            return 1;
        } catch (Exception ex) {
            err.println(ex);
            return 2;
        }
    }

    /** {@code fix}: report (and with --apply, apply) the suggested fixes; --loop recompiles and repeats until clean. */
    static int fix(Map<String, String> opt, PrintStream out, PrintStream err) throws Exception {
        String top = opt.get("top");
        if (top == null || !opt.containsKey("src")) {
            err.println("usage: tessera fix --top <class> --src <dir>[:<dir>] [--apply] [--loop] [--json]");
            return 2;
        }
        var roots = new java.util.ArrayList<Path>();
        for (var s : opt.get("src").split(java.io.File.pathSeparator)) roots.add(Path.of(s));
        boolean loop = opt.containsKey("loop"), apply = loop || opt.containsKey("apply");
        for (int round = 0; round < 10; round++) {
            ClassLoader loader = Thread.currentThread().getContextClassLoader();
            if (loop) {
                var r = DesignCompiler.compile(roots, Files.createTempDirectory("tessera-fix"));
                if (!r.ok()) {
                    r.errors().forEach(err::println);
                    return 2;
                }
                loader = r.loader();
            }
            var prev = Thread.currentThread().getContextClassLoader();
            Thread.currentThread().setContextClassLoader(loader);
            try {
                Tessera.elaborate(factory(top));
                out.println(round == 0 ? "no errors" : "clean after " + round + " round(s) of fixes");
                return 0;
            } catch (TesseraException ex) {
                if (opt.containsKey("json")) out.println(Fixer.json(ex, roots));
                else out.println(ex.getMessage());
                if (!apply) return 1;
                var applied = Fixer.apply(ex, roots);
                if (applied.isEmpty()) {
                    out.println("no automatic fix available");
                    return 1;
                }
                for (var a : applied)
                    out.println("fixed " + a.file().getFileName() + ":" + a.line() + " (" + a.description() + ")\n  - " + a.before()
                            + "\n  + " + a.after());
                if (!loop) return 0;
            } finally {
                Thread.currentThread().setContextClassLoader(prev);
            }
        }
        out.println("still failing after 10 rounds");
        return 1;
    }

    static final String USAGE = """
            usage: tessera <command> --top <class>[::<method>] [options]
              emit    write SystemVerilog (+ .types.json)     [--out dir]
              lint    verilator --lint-only -Wall
              synth   Yosys synthesis and cell counts          [--target generic|xc7|ice40|ecp5]
              formal  bounded model check of assertions        [--depth n] [--prove k] [--out dir]
              btor2   write a BTOR2 model                      [--out dir]
              sim     run from reset                           [--cycles n] [--trace file.vcd|.fst]
              fix     suggested fixes for elaboration errors  --src dir [--apply] [--loop] [--json]""";

    /** A supplier for {@code com.example.Top} (no-arg constructor) or {@code com.example.Designs::method}. */
    @SuppressWarnings("unchecked")
    public static Supplier<Module> factory(String spec) throws ReflectiveOperationException {
        var loader = Thread.currentThread().getContextClassLoader();
        int sep = spec.indexOf("::");
        if (sep >= 0) {
            Class<?> cls = Class.forName(spec.substring(0, sep), true, loader);
            String mname = spec.substring(sep + 2);
            Method m = null;
            for (var cand : cls.getDeclaredMethods())
                if (cand.getName().equals(mname) && cand.getParameterCount() == 0 && Modifier.isStatic(cand.getModifiers())) m = cand;
            if (m == null) throw new NoSuchMethodException("no static no-argument method " + spec);
            m.setAccessible(true);
            Method fm = m;
            return () -> {
                try {
                    return (Module) fm.invoke(null);
                } catch (java.lang.reflect.InvocationTargetException ex) {
                    if (ex.getCause() instanceof RuntimeException re) throw re;
                    throw new IllegalStateException(ex.getCause());
                } catch (ReflectiveOperationException ex) {
                    throw new IllegalStateException(ex);
                }
            };
        }
        Class<? extends Module> cls = (Class<? extends Module>) Class.forName(spec, true, loader);
        var ctor = cls.getDeclaredConstructor();
        ctor.setAccessible(true);
        return () -> {
            try {
                return ctor.newInstance();
            } catch (java.lang.reflect.InvocationTargetException ex) {
                if (ex.getCause() instanceof RuntimeException re) throw re;
                throw new IllegalStateException(ex.getCause());
            } catch (ReflectiveOperationException ex) {
                throw new IllegalStateException(ex);
            }
        };
    }

}
