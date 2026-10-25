package org.eninehq.tessera.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.eninehq.tessera.core.TesseraException.Diagnostic;
import org.eninehq.tessera.ir.Circuit;
import org.eninehq.tessera.ir.ClockDomainDef;
import org.eninehq.tessera.ir.ClockRoots;
import org.eninehq.tessera.ir.Expr;
import org.eninehq.tessera.ir.MemDef;
import org.eninehq.tessera.ir.Signal;

/**
 * Clock-domain-crossing lint: a register (or memory write port) must not sample logic driven
 * by registers of another clock, except the first stage of a declared synchronizer.
 * Top-level inputs are treated as unclocked and may be used anywhere.
 */
final class CdcCheck {
    private CdcCheck() {}

    private final Map<Object, Set<Signal>> memo = new IdentityHashMap<>();
    private final Map<MemDef, Set<Signal>> memRoots = new IdentityHashMap<>();

    static void check(Circuit c) {
        Set<Signal> clocks = Collections.newSetFromMap(new IdentityHashMap<>());
        var mods = c.modulesBottomUp();
        for (var m : mods) for (var d : m.domains()) clocks.add(root(d));
        if (clocks.size() < 2) return;
        var errors = new ArrayList<Diagnostic>();
        var failure = new RuntimeException[1];
        var t = new Thread(null, () -> {
            try {
                new CdcCheck().run(mods, errors);
            } catch (RuntimeException e) {
                failure[0] = e;
            }
        }, "tessera-cdc", 256L << 20);
        t.start();
        try {
            t.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (failure[0] != null) throw failure[0];
        if (!errors.isEmpty()) throw new TesseraException(errors);
    }

    private static Signal root(ClockDomainDef d) {
        try {
            return ClockRoots.rootOf(d);
        } catch (IllegalStateException e) {
            throw new TesseraException(d.clock().loc(), e.getMessage());
        }
    }

    private void run(List<org.eninehq.tessera.ir.ModuleDef> mods, List<Diagnostic> errors) {
        for (var m : mods)
            for (var mem : m.mems()) {
                Set<Signal> rs = Collections.newSetFromMap(new IdentityHashMap<>());
                for (var w : mem.writes()) rs.add(root(w.domain()));
                memRoots.put(mem, rs);
            }
        for (var m : mods) {
            for (var s : m.signals()) {
                if (s.kind() != Signal.Kind.REG || s.driver() == null || s.isCdcSync()) continue;
                Signal own = root(s.domain());
                for (var src : roots(s.driver()))
                    if (src != own) {
                        errors.add(new Diagnostic(s.loc(), "register '" + s.name() + "' (clock " + own.name()
                                + ") samples logic clocked by " + src.name()
                                + " without a synchronizer; use Hw.synchronize(...) or an async FIFO"));
                        break;
                    }
            }
            for (var mem : m.mems())
                for (var w : mem.writes()) {
                    Signal own = root(w.domain());
                    var srcs = Collections.<Signal>newSetFromMap(new IdentityHashMap<>());
                    srcs.addAll(roots(w.addr()));
                    srcs.addAll(roots(w.data()));
                    srcs.addAll(roots(w.en()));
                    for (var src : srcs)
                        if (src != own) {
                            errors.add(new Diagnostic(w.loc(), "write port of memory '" + mem.name() + "' (clock "
                                    + own.name() + ") uses logic clocked by " + src.name() + " without a synchronizer"));
                            break;
                        }
                }
        }
    }

    /** Root clocks of the registers that combinationally reach {@code e}. */
    private Set<Signal> roots(Expr e) {
        var have = memo.get(e);
        if (have != null) return have;
        Set<Signal> out = Collections.newSetFromMap(new IdentityHashMap<>());
        switch (e) {
            case Expr.Lit l -> {}
            case Expr.Ref r -> out.addAll(signalRoots(r.signal()));
            case Expr.InstRef ir -> out.addAll(signalRoots(ir.port()));
            case Expr.MemRead mr -> {
                if (!mr.mem().cdcSafe()) out.addAll(memRoots.getOrDefault(mr.mem(), Set.of()));
                out.addAll(roots(mr.addr()));
            }
            case Expr.Op op -> {
                for (var a : op.args()) out.addAll(roots(a));
            }
        }
        memo.put(e, out);
        return out;
    }

    private Set<Signal> signalRoots(Signal s) {
        var have = memo.get(s);
        if (have != null) return have;
        Set<Signal> out = Collections.newSetFromMap(new IdentityHashMap<>());
        memo.put(s, out);
        switch (s.kind()) {
            case REG -> out.add(root(s.domain()));
            case INPUT -> {
                var inst = s.owner().instantiatedAs();
                if (inst != null && !s.isClockOrReset()) {
                    Expr d = inst.inputs().get(s);
                    if (d != null) out.addAll(roots(d));
                }
            }
            default -> {
                if (s.driver() != null) out.addAll(roots(s.driver()));
            }
        }
        return out;
    }
}
