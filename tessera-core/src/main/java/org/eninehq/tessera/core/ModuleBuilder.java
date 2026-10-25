package org.eninehq.tessera.core;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.eninehq.tessera.core.TesseraException.Diagnostic;
import org.eninehq.tessera.ir.ClockDomainDef;
import org.eninehq.tessera.ir.Expr;
import org.eninehq.tessera.ir.Instance;
import org.eninehq.tessera.ir.MemDef;
import org.eninehq.tessera.ir.ModuleDef;
import org.eninehq.tessera.ir.Ops;
import org.eninehq.tessera.ir.Signal;

/** Elaboration state of one module while its constructor runs. */
final class ModuleBuilder {

    /** A when/elseWhen/otherwise chain. {@code conds} holds the local conditions of the non-otherwise branches. */
    static final class Chain {
        final List<Expr> conds = new ArrayList<>();
        boolean hasOtherwise;
        boolean closed;
    }

    record Scope(Chain chain, int branch, Expr full) {}

    record Asg(List<Scope> path, Expr value, SourceLoc loc) {}

    record InstPort(Instance inst, Signal port) {}

    /** A value being folded: {@code e} is null when nothing is driven; {@code complete} when every path drives it. */
    private record Val(Expr e, boolean complete) {}

    final Module module;
    final ModuleDef def;
    final Map<Object, List<Asg>> assigns = new LinkedHashMap<>();
    final Map<Object, SourceLoc> declLocs = new LinkedHashMap<>();
    final Deque<Scope> scopes = new ArrayDeque<>();
    StageHook hook;
    private int autoCounter;
    /** Domains entered with withClock, innermost first. */
    final Deque<ClockDomainDef> domainStack = new ArrayDeque<>();
    ClockDomainDef.ResetKind defaultResetKind = ClockDomainDef.ResetKind.SYNC_HIGH;
    String clockName = "clock", resetName = "reset";
    /** Number of assignments and state elements created so far (for purity checks). */
    int effects;

    ModuleBuilder(Module module, String name) {
        this.module = module;
        this.def = new ModuleDef(name);
    }

    String autoName(String prefix) {
        return prefix + (autoCounter++);
    }

    Expr fullCondition() {
        return scopes.isEmpty() ? Ops.TRUE : scopes.peek().full();
    }

    private final List<Chain> openChains = new ArrayList<>();

    /** Register a new chain at the current depth; closes any earlier chain at that depth. */
    void openChain(Chain c) {
        closeChainAtDepth();
        openChains.set(scopes.size(), c);
    }

    private void closeChainAtDepth() {
        int d = scopes.size();
        while (openChains.size() <= d) openChains.add(null);
        var prev = openChains.get(d);
        if (prev != null) prev.closed = true;
        openChains.set(d, null);
        for (int i = d + 1; i < openChains.size(); i++) openChains.set(i, null);
    }

    void addAssign(Object key, Expr value, SourceLoc loc) {
        closeChainAtDepth();
        effects++;
        assigns.computeIfAbsent(key, k -> new ArrayList<>())
                .add(new Asg(List.copyOf(scopes.reversed()), value, loc));
    }

    /** Create the implicit clock domain (ports {@code clock} and {@code reset}) if it does not exist yet. */
    void ensureClock() {
        if (def.clock() != null) return;
        var clock = new Signal(Signal.Kind.INPUT, clockName, false, 1, false, def, SourceLoc.UNKNOWN);
        var reset = new Signal(Signal.Kind.INPUT, resetName, false, 1, false, def, SourceLoc.UNKNOWN);
        clock.markClockOrReset();
        reset.markClockOrReset();
        def.ports().add(0, reset);
        def.ports().add(0, clock);
        def.setClockAndReset(clock, reset);
        var d = new ClockDomainDef("default", clock, reset, defaultResetKind, def);
        def.setDefaultDomain(d);
        def.domains().add(0, d);
    }

    /** The domain new registers and memory ports belong to: the innermost withClock, else the implicit one. */
    ClockDomainDef currentDomain() {
        if (!domainStack.isEmpty()) return domainStack.peek();
        ensureClock();
        return def.defaultDomain();
    }

    /** Declare a register in the current domain. */
    Signal newRegSignal(String name, boolean auto, int width, boolean signed, SourceLoc loc) {
        var d = currentDomain();
        var s = new Signal(Signal.Kind.REG, name, auto, width, signed, def, loc);
        s.setDomain(d);
        effects++;
        return s;
    }

    // ---------------------------------------------------------------- finishing

    void finish() {
        if (!scopes.isEmpty()) throw new TesseraException(SourceLoc.here(), "unclosed when block");
        nameFromFields();
        var errors = new ArrayList<Diagnostic>();
        foldAssignments(errors);
        checkDriven(errors);
        if (!errors.isEmpty()) throw new TesseraException(errors);
        uniquifyNames();
    }

    private void foldAssignments(List<Diagnostic> errors) {
        for (var e : assigns.entrySet()) {
            Object key = e.getKey();
            boolean isReg = key instanceof Signal s && s.kind() == Signal.Kind.REG;
            Val start = isReg ? new Val(new Expr.Ref((Signal) key), true) : new Val(null, false);
            Val v = fold(e.getValue(), 0, start);
            if (!v.complete()) {
                var first = e.getValue().getFirst().loc();
                errors.add(new Diagnostic(declLocs.getOrDefault(key, first),
                        describe(key) + " is not assigned on every path (first assigned under a condition at "
                                + first + "); give it a default value or add .otherwise(...)"));
                continue;
            }
            switch (key) {
                case Signal s -> s.setDriver(v.e());
                case InstPort ip -> ip.inst().inputs().put(ip.port(), v.e());
                default -> throw new IllegalStateException();
            }
        }
    }

    private Val fold(List<Asg> events, int depth, Val prev) {
        int i = 0;
        while (i < events.size()) {
            Asg ev = events.get(i);
            if (ev.path().size() == depth) {
                prev = new Val(ev.value(), true);
                i++;
                continue;
            }
            Chain ch = ev.path().get(depth).chain();
            int j = i;
            while (j < events.size() && events.get(j).path().size() > depth
                    && events.get(j).path().get(depth).chain() == ch) j++;
            var group = events.subList(i, j);
            int nb = ch.conds.size() + (ch.hasOtherwise ? 1 : 0);
            Val[] branchVals = new Val[nb];
            for (int b = 0; b < nb; b++) {
                final int bb = b;
                var sub = group.stream().filter(a -> a.path().get(depth).branch() == bb).toList();
                branchVals[b] = sub.isEmpty() ? prev : fold(sub, depth + 1, prev);
            }
            Val res = ch.hasOtherwise ? branchVals[nb - 1] : prev;
            for (int b = ch.conds.size() - 1; b >= 0; b--) res = mux(ch.conds.get(b), branchVals[b], res);
            prev = res;
            i = j;
        }
        return prev;
    }

    private static Val mux(Expr c, Val t, Val f) {
        if (t == f) return t;
        if (t.complete() && f.complete()) return new Val(Ops.mux(c, t.e(), f.e()), true);
        Expr e = t.e() != null && f.e() != null ? Ops.mux(c, t.e(), f.e()) : (t.e() != null ? t.e() : f.e());
        return new Val(e, false);
    }

    private void checkDriven(List<Diagnostic> errors) {
        for (var s : def.allSignals()) {
            if ((s.kind() == Signal.Kind.OUTPUT || s.kind() == Signal.Kind.WIRE) && s.driver() == null
                    && !assigns.containsKey(s)) {
                errors.add(new Diagnostic(s.loc(), (s.kind() == Signal.Kind.OUTPUT ? "output " : "wire ")
                        + "'" + s.name() + "' of module " + def.name() + " is never assigned"));
            }
        }
        for (var inst : def.instances()) {
            for (var p : inst.def().ports()) {
                if (p.kind() == Signal.Kind.INPUT && !inst.inputs().containsKey(p)) {
                    errors.add(new Diagnostic(inst.loc(), (p.isClockOrReset() ? "clock/reset input '" : "input '")
                            + p.name() + "' of instance '" + inst.name() + "' (" + inst.def().name() + ") is not connected"
                            + (p.isClockOrReset() ? "; connect its clock domain with connectClock(...)" : "")));
                }
            }
        }
    }

    private String describe(Object key) {
        return switch (key) {
            case Signal s -> s.kind().name().toLowerCase() + " '" + s.name() + "'";
            case InstPort ip -> "input '" + ip.port().name() + "' of instance '" + ip.inst().name() + "'";
            default -> key.toString();
        };
    }

    // ---------------------------------------------------------------- naming

    private void nameFromFields() {
        for (Class<?> c = module.getClass(); c != null && c != Module.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || f.isSynthetic()) continue;
                Object v;
                try {
                    f.setAccessible(true);
                    v = f.get(module);
                } catch (ReflectiveOperationException | RuntimeException ex) {
                    continue;
                }
                nameValue(f.getName(), v, 0);
            }
        }
    }

    private void nameValue(String name, Object v, int depth) {
        switch (v) {
            case null -> {}
            case Data d -> {
                if (d.signal != null && d.signal.owner() == def && d.signal.autoNamed()) d.signal.rename(name, false);
            }
            case Mem<?> m -> {
                if (m.def.owner() == def) m.def.rename(name);
            }
            case Module m -> {
                var inst = m.builder().def.instantiatedAs();
                if (inst != null && inst.parent() == def && m.builder().autoInstanceName) {
                    inst.rename(name);
                    m.builder().autoInstanceName = false;
                }
            }
            case Data[] arr -> {
                for (int i = 0; i < arr.length; i++) nameValue(name + "_" + i, arr[i], depth + 1);
            }
            case List<?> list when depth < 2 -> {
                for (int i = 0; i < list.size(); i++) nameValue(name + "_" + i, list.get(i), depth + 1);
            }
            case Vec<?> vec when depth < 6 -> {
                for (int i = 0; i < vec.size(); i++) nameValue(name + "_" + i, vec.get(i), depth + 1);
            }
            case ClockDomain cd -> {}
            case Record r when depth < 6 -> {
                for (var rc : r.getClass().getRecordComponents()) {
                    try {
                        rc.getAccessor().setAccessible(true);
                        nameValue(name + "_" + rc.getName(), rc.getAccessor().invoke(r), depth + 1);
                    } catch (ReflectiveOperationException | RuntimeException ignored) {
                        // not accessible: leave unnamed
                    }
                }
            }
            default -> {}
        }
    }

    boolean autoInstanceName = true;

    private void uniquifyNames() {
        Set<String> used = new HashSet<>();
        for (var s : def.ports()) s.rename(unique(used, s.name()), s.autoNamed());
        for (var m : def.mems()) m.rename(unique(used, m.name()));
        for (var i : def.instances()) i.rename(unique(used, i.name()));
        for (var s : def.signals()) s.rename(unique(used, s.name()), s.autoNamed());
    }

    private static String unique(Set<String> used, String base) {
        String n = base;
        for (int k = 1; !used.add(n); k++) n = base + "_" + k;
        return n;
    }

    static BigInteger checkFits(long value, int width, boolean signed) {
        BigInteger v = BigInteger.valueOf(value);
        boolean ok = signed
                ? v.bitLength() < width
                : v.signum() >= 0 && v.bitLength() <= width;
        if (!ok) throw TesseraException.here("value " + value + " does not fit in "
                + (signed ? "SInt" : "UInt") + "<" + width + ">");
        return signed && v.signum() < 0 ? v.add(BigInteger.ONE.shiftLeft(width)) : v;
    }
}
