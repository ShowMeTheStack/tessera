package org.eninehq.tessera.formal;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.eninehq.tessera.core.Elaborated;
import org.eninehq.tessera.ir.Assertion;
import org.eninehq.tessera.ir.Circuit;
import org.eninehq.tessera.sim.Netlist;
import org.eninehq.tessera.sim.Sim;
import org.eninehq.tessera.sim.Simulator;

/**
 * Formal verification of {@code Hw.assertThat}/{@code assume}/{@code cover} properties with an
 * SMT solver (z3, bitwuzla or yices; the first one found). Bounded model checking finds the
 * shortest counterexample from reset; k-induction proves properties for all time.
 * Counterexamples are input traces that replay in the simulator.
 *
 * <pre>{@code
 * var r = Formal.bmc(Tessera.elaborate(MyDesign::new), 20);
 * if (r.failed()) r.writeTrace(Path.of("cex.vcd"));
 * }</pre>
 */
public final class Formal {
    private Formal() {}

    public enum Status {
        /** No property can fail within the bound. */
        PASS,
        /** A property fails; see {@link Result#counterexample()}. */
        FAIL,
        /** Proven for all time by k-induction. */
        PROVEN,
        /** Induction did not close at this depth (the property may still hold). */
        UNKNOWN
    }

    /** Options: solver binary, reset cycles at the start, whether memories start arbitrary. */
    public record Options(String solver, int resetCycles, boolean arbitraryMemory) {
        public static Options defaults() {
            return new Options(findSolver(), 1, false);
        }

        public Options withSolver(String s) { return new Options(s, resetCycles, arbitraryMemory); }
        public Options withArbitraryMemory(boolean b) { return new Options(solver, resetCycles, b); }
    }

    /**
     * Outcome of a check. For FAIL, {@code counterexample} holds the top-level inputs for every
     * cycle up to and including {@code failCycle}; {@code check} names the property and its source line.
     */
    public record Result(Status status, int depth, String check, String location, int failCycle,
                         List<Map<String, BigInteger>> counterexample, Map<String, BigInteger> initialState,
                         Circuit circuit) {
        public boolean failed() { return status == Status.FAIL; }

        /** Replay the counterexample in a simulator (and optionally record a waveform). */
        public AssertionError replay(Path trace) {
            if (counterexample == null) throw new IllegalStateException("no counterexample");
            try (Simulator sim = Sim.create(circuit, Sim.defaultBackend())) {
                if (trace != null) sim.trace(trace);
                sim.loadRegisterState(initialState);
                for (var row : counterexample) {
                    for (var e : row.entrySet()) sim.pokeBig(e.getKey(), e.getValue());
                    try {
                        sim.step();
                    } catch (AssertionError err) {
                        return err;
                    }
                }
            }
            return null;
        }

        public void writeTrace(Path vcd) {
            replay(vcd);
        }

        @Override public String toString() {
            return switch (status) {
                case FAIL -> "FAIL: '" + check + "' (" + location + ") fails at cycle " + failCycle;
                case PASS -> "PASS: no failure within " + depth + " cycles";
                case PROVEN -> "PROVEN by " + depth + "-induction";
                case UNKNOWN -> "UNKNOWN: induction did not close at depth " + depth;
            };
        }
    }

    static String findSolver() {
        for (var s : List.of("bitwuzla", "yices-smt2", "z3")) {
            try {
                var p = new ProcessBuilder(s, "--version").redirectErrorStream(true).start();
                p.getInputStream().readAllBytes();
                if (p.waitFor() == 0) return s;
            } catch (IOException | InterruptedException e) {
                // try the next one
            }
        }
        return null;
    }

    public static Result bmc(Elaborated<?> e, int depth) {
        return bmc(e.circuit(), depth, Options.defaults());
    }

    /** Bounded model check: the shortest run from reset (at most {@code depth} cycles) that violates an assertion. */
    public static Result bmc(Circuit c, int depth, Options opt) {
        var net = Netlist.build(c);
        try (var s = new Solver(opt.solver())) {
            var u = new SmtUnroller(net, opt.arbitraryMemory());
            u.constArrays = !opt.solver().startsWith("yices");
            s.send("(set-option :produce-models true)\n(set-logic QF_ABV)\n");
            for (int t = 0; t <= depth; t++) {
                var sb = new StringBuilder();
                if (t > 0) u.transition(sb, t - 1);
                u.declareStep(sb, t, false);
                constrainResets(sb, net, u, t, opt.resetCycles());
                assumes(sb, net, u, t);
                s.send(sb.toString());
                // any assertion failing at step t?
                for (int i = 0; i < net.checks().size(); i++) {
                    var ch = net.checks().get(i);
                    if (ch.kind() != Assertion.Kind.ASSERT) continue;
                    s.send("(push 1)\n(assert (and (= " + u.term(t, ch.en(), 1) + " #b1) (= " + u.term(t, ch.cond(), 1) + " #b0)))\n");
                    if (s.checkSat()) {
                        var cex = model(s, net, u, t);
                        var init = initialRegs(s, net, u);
                        s.send("(pop 1)\n");
                        return new Result(Status.FAIL, depth, ch.name(), ch.loc(), t, cex, init, c);
                    }
                    s.send("(pop 1)\n");
                }
            }
        }
        return new Result(Status.PASS, depth, null, null, -1, null, null, c);
    }

    public static Result prove(Elaborated<?> e, int k) {
        return prove(e.circuit(), k, Options.defaults());
    }

    /**
     * k-induction: BMC for {@code k} cycles, then show that from any state where the
     * assertions held for {@code k} cycles in a row they also hold in the next one.
     */
    public static Result prove(Circuit c, int k, Options opt) {
        var base = bmc(c, k, opt);
        if (base.failed()) return base;
        var net = Netlist.build(c);
        try (var s = new Solver(opt.solver())) {
            var u = new SmtUnroller(net, true);
            s.send("(set-logic QF_ABV)\n");
            for (int t = 0; t <= k; t++) {
                var sb = new StringBuilder();
                if (t > 0) u.transition(sb, t - 1);
                u.declareStep(sb, t, false);
                constrainResets(sb, net, u, t, 0);
                assumes(sb, net, u, t);
                if (t < k)
                    for (var ch : net.checks())
                        if (ch.kind() == Assertion.Kind.ASSERT)
                            sb.append("(assert (=> (= ").append(u.term(t, ch.en(), 1)).append(" #b1) (= ")
                                    .append(u.term(t, ch.cond(), 1)).append(" #b1)))\n");
                s.send(sb.toString());
            }
            var bad = new StringBuilder("(assert (or false");
            for (var ch : net.checks())
                if (ch.kind() == Assertion.Kind.ASSERT)
                    bad.append(" (and (= ").append(u.term(k, ch.en(), 1)).append(" #b1) (= ").append(u.term(k, ch.cond(), 1)).append(" #b0))");
            s.send(bad.append("))\n").toString());
            boolean sat = s.checkSat();
            return new Result(sat ? Status.UNKNOWN : Status.PROVEN, k, null, null, -1, null, null, c);
        }
    }

    /** For each cover property, the first cycle it can be reached at (within {@code depth}), or -1. */
    public static Map<String, Integer> cover(Elaborated<?> e, int depth) {
        var c = e.circuit();
        var opt = Options.defaults();
        var net = Netlist.build(c);
        var out = new LinkedHashMap<String, Integer>();
        for (var ch : net.checks()) if (ch.kind() == Assertion.Kind.COVER) out.put(ch.name(), -1);
        try (var s = new Solver(opt.solver())) {
            var u = new SmtUnroller(net, false);
            u.constArrays = !opt.solver().startsWith("yices");
            s.send("(set-logic QF_ABV)\n");
            for (int t = 0; t <= depth; t++) {
                var sb = new StringBuilder();
                if (t > 0) u.transition(sb, t - 1);
                u.declareStep(sb, t, false);
                constrainResets(sb, net, u, t, opt.resetCycles());
                assumes(sb, net, u, t);
                s.send(sb.toString());
                for (var ch : net.checks()) {
                    if (ch.kind() != Assertion.Kind.COVER || out.get(ch.name()) >= 0) continue;
                    s.send("(push 1)\n(assert (and (= " + u.term(t, ch.en(), 1) + " #b1) (= " + u.term(t, ch.cond(), 1) + " #b1)))\n");
                    if (s.checkSat()) out.put(ch.name(), t);
                    s.send("(pop 1)\n");
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------ helpers

    private static void constrainResets(StringBuilder sb, Netlist net, SmtUnroller u, int t, int resetCycles) {
        var resets = new LinkedHashMap<Integer, Long>();
        for (var r : net.regs()) if (r.hasReset()) resets.put(r.resetSlot(), r.resetActive());
        var inputs = net.inputSlots().values();
        for (var e : resets.entrySet()) {
            if (!inputs.contains(e.getKey())) continue; // derived resets follow from the logic
            long level = t < resetCycles ? e.getValue() : 1 - e.getValue();
            sb.append("(assert (= ").append(u.name(t, e.getKey())).append(" #b").append(level).append("))\n");
        }
    }

    private static void assumes(StringBuilder sb, Netlist net, SmtUnroller u, int t) {
        for (var ch : net.checks())
            if (ch.kind() == Assertion.Kind.ASSUME)
                sb.append("(assert (=> (= ").append(u.term(t, ch.en(), 1)).append(" #b1) (= ")
                        .append(u.term(t, ch.cond(), 1)).append(" #b1)))\n");
    }

    private static List<Map<String, BigInteger>> model(Solver s, Netlist net, SmtUnroller u, int last) {
        var rows = new ArrayList<Map<String, BigInteger>>();
        for (int t = 0; t <= last; t++) {
            var names = u.inputNames(t);
            var vals = names.isEmpty() ? Map.<String, BigInteger>of() : s.getValues(names);
            var row = new LinkedHashMap<String, BigInteger>();
            for (var e : net.inputSlots().entrySet()) {
                var v = vals.get(u.name(t, e.getValue()));
                row.put(e.getKey(), v == null ? BigInteger.ZERO : v);
            }
            rows.add(row);
        }
        return rows;
    }

    private static Map<String, BigInteger> initialRegs(Solver s, Netlist net, SmtUnroller u) {
        var names = new ArrayList<String>();
        for (var r : net.regs()) names.add(u.name(0, r.slot()));
        var vals = names.isEmpty() ? Map.<String, BigInteger>of() : s.getValues(names);
        var out = new LinkedHashMap<String, BigInteger>();
        for (var r : net.regs()) if (r.path() != null) out.put(r.path(), vals.getOrDefault(u.name(0, r.slot()), BigInteger.ZERO));
        return out;
    }

    /** An interactive SMT-LIB solver process. */
    static final class Solver implements AutoCloseable {
        private final Process p;
        private final Writer in;
        private final BufferedReader out;

        Solver(String solver) {
            if (solver == null) throw new IllegalStateException("no SMT solver found (install bitwuzla, yices2 or z3)");
            var cmd = switch (solver) {
                case "z3" -> List.of("z3", "-in", "-smt2");
                case "bitwuzla" -> List.of("bitwuzla", "--lang", "smt2", "-m");
                case "yices-smt2" -> List.of("yices-smt2", "--incremental");
                default -> List.of(solver);
            };
            try {
                p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            in = new java.io.OutputStreamWriter(p.getOutputStream(), StandardCharsets.UTF_8);
            out = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
        }

        void send(String s) {
            try {
                in.write(s);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        private String readLine() {
            try {
                in.flush();
                String l = out.readLine();
                if (l == null) throw new IllegalStateException("solver exited");
                return l;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        boolean checkSat() {
            send("(check-sat)\n");
            String l = readLine().trim();
            return switch (l) {
                case "sat" -> true;
                case "unsat" -> false;
                default -> throw new IllegalStateException("solver: " + l);
            };
        }

        private static final Pattern VALUE = Pattern.compile("\\(\\s*([A-Za-z0-9_]+)\\s+(#b[01]+|#x[0-9a-fA-F]+|\\(_ bv\\d+ \\d+\\))\\s*\\)");

        Map<String, BigInteger> getValues(List<String> names) {
            send("(get-value (" + String.join(" ", names) + "))\n");
            var sb = new StringBuilder();
            int depth = 0;
            boolean started = false;
            while (!started || depth > 0) {
                String l = readLine();
                for (char ch : l.toCharArray()) {
                    if (ch == '(') {
                        depth++;
                        started = true;
                    } else if (ch == ')') depth--;
                }
                sb.append(l).append('\n');
                if (l.startsWith("(error")) throw new IllegalStateException(l);
            }
            var res = new LinkedHashMap<String, BigInteger>();
            var m = VALUE.matcher(sb);
            while (m.find()) res.put(m.group(1), parse(m.group(2)));
            return res;
        }

        static BigInteger parse(String v) {
            if (v.startsWith("#b")) return new BigInteger(v.substring(2), 2);
            if (v.startsWith("#x")) return new BigInteger(v.substring(2), 16);
            var parts = v.replace("(_ bv", "").replace(")", "").trim().split("\\s+");
            return new BigInteger(parts[0]);
        }

        @Override public void close() {
            try {
                in.write("(exit)\n");
                in.flush();
            } catch (IOException ignored) {
                // solver already gone
            }
            p.destroy();
        }
    }
}
