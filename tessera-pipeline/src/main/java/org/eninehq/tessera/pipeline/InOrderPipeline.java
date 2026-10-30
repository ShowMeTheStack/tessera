package org.eninehq.tessera.pipeline;

import static org.eninehq.tessera.core.Hw.mux;
import static org.eninehq.tessera.core.Hw.when;

import java.util.ArrayList;
import java.util.List;
import org.eninehq.tessera.core.Bool;
import org.eninehq.tessera.core.Data;
import org.eninehq.tessera.core.Hw;
import org.eninehq.tessera.core.Internal;
import org.eninehq.tessera.core.TesseraException;
import org.eninehq.tessera.core.UInt;

/**
 * Level-2 pipelines: generate an in-order pipeline from an {@link InOrderModel} and a
 * {@link Schedule}. The generator infers
 * <ul>
 *   <li>forwarding into the {@code readRegs} stage from every later in-flight stage whose
 *       result is ready (youngest first);</li>
 *   <li>stalls when an in-flight producer's result is not ready yet (load-use and deeper);</li>
 *   <li>the fetch redirect and the flush of younger items when a redirect resolves;</li>
 *   <li>stopping fetch when a halting item can no longer be cancelled.</li>
 * </ul>
 * It also rejects unsafe schedules, such as side effects before redirects resolve.
 * Moving a step to another stage is a schedule change; the hazard logic follows.
 */
public final class InOrderPipeline {

    /** What a later stage tells the read stage about its in-flight item. */
    private record Producer(int stage, Late<Bool> hit, Late<UInt> rd, Late<UInt> value, Late<Bool> ready) {}

    private final Pipeline p;
    private final Bool halted;
    private final List<String> report = new ArrayList<>();
    private final List<String> stageNames;

    private InOrderPipeline(String name, InOrderModel m, Schedule s, Builder b) {
        stageNames = s.stageNames();
        validate(m, s);
        var ctx = new StepContext(m, s);
        UInt pcReg = b.reg(name + "_pc", m.pcWidth, m.pcReset);
        Bool stopFetch = b.flag(name + "_stop_fetch");
        halted = b.flag(name + "_halted");
        ctx.halted = halted;

        p = Pipeline.begin(name, stopFetch.not());
        ctx.pipeline = p;
        Repairs repairs = Repairs.of(m, p, name);
        ctx.step = "(pc)";
        ctx.put(m.pcKey, p.input(pcReg));

        int stages = s.stageNames().size();
        int readStage = s.indexOf(InOrderModel.READ_REGS), writeStage = s.indexOf(InOrderModel.WRITE_REGS);
        boolean predicting = m.predictKey != null || !m.resolves.isEmpty();
        // resolve points from the earliest stage to the latest
        var resolves = new ArrayList<>(m.resolves);
        resolves.sort(java.util.Comparator.comparingInt(r -> s.indexOf(r.step())));
        int redirectStage = predicting ? s.indexOf(resolves.getLast().step())
                : m.redirectStep == null ? -1 : s.indexOf(m.redirectStep);
        int haltStage = m.haltStep == null ? -1 : Math.max(s.indexOf(m.haltStep), redirectStage);

        Late<Bool> redirect = m.redirectKey != null ? p.late(Bool.TYPE) : null;
        Late<UInt> target = m.redirectKey != null ? p.late(UInt.w(m.pcWidth)) : null;
        // with prediction: fetch's guess, and each resolve point's redirect and target (the latest stage wins)
        Late<UInt> guess = null;
        var resolveRedirect = new ArrayList<Late<Bool>>();
        var resolveTarget = new ArrayList<Late<UInt>>();
        var believed = new ArrayList<Key<UInt>>();
        if (!predicting) {
            UInt sequential = mux(p.held(0), pcReg, pcReg.addWrap(m.pcIncrement));
            pcReg.set(redirect != null ? mux(redirect.get(), target.get(), sequential) : sequential);
            if (redirect != null)
                report.add("redirect: resolved in " + s.stageNames().get(redirectStage) + "; younger stages are flushed");
        } else {
            guess = p.late(UInt.w(m.pcWidth));
            UInt next = mux(p.held(0), pcReg, guess.get());
            for (int i = 0; i < resolves.size(); i++) {
                resolveRedirect.add(p.late(Bool.TYPE));
                resolveTarget.add(p.late(UInt.w(m.pcWidth)));
                next = mux(resolveRedirect.get(i).get(), resolveTarget.get(i).get(), next);
            }
            for (int i = 0; i <= resolves.size(); i++) believed.add(Key.of("(next pc guess " + i + ")"));
            pcReg.set(next);
            report.add("prediction: fetch takes " + (m.predictKey != null ? m.predictKey + " as the next pc" : "pc+" + m.pcIncrement)
                    + "; checked in " + String.join(", ", resolves.stream().map(r -> s.stageOf(r.step())).toList())
                    + "; a wrong guess redirects fetch and flushes younger stages, and the oldest item's redirect wins");
        }

        List<Producer> producers = new ArrayList<>();
        // result points seen so far, in model order; a stage's producer forwards the latest one available there
        List<Avail> avail = new ArrayList<>();
        for (int st = 0; st < stages; st++) {
            if (st > 0) p.stage(s.stageNames().get(st));
            for (String stepName : s.stepsIn(st, m.stepNames())) {
                ctx.step = stepName;
                switch (stepName) {
                    case InOrderModel.READ_REGS -> producers = readRegs(m, s, ctx, readStage, writeStage);
                    case InOrderModel.WRITE_REGS -> {
                        UInt rd = ctx.get(m.rdKey);
                        UInt v = ctx.get(m.rdValueKey);
                        when(ctx.fire().and(ctx.get(m.writesKey)), () -> m.regFile.write(rd, v));
                    }
                    default -> m.steps.stream().filter(x -> x.name().equals(stepName)).findFirst().orElseThrow()
                            .body().accept(ctx);
                }
                for (var rp : m.results) {
                    if (rp.step().equals(stepName))
                        avail.add(new Avail(rp.forward() == Forward.NEXT_STAGE ? st + 1 : st, ctx.get(rp.value()),
                                rp.ready() == null ? Hw.TRUE : ctx.get(rp.ready())));
                }
                int ri = -1;
                for (int i = 0; i < resolves.size(); i++) if (resolves.get(i).step().equals(stepName)) ri = i;
                if (ri >= 0) {
                    var r = resolves.get(ri);
                    UInt next = ctx.get(r.nextPc()), was = ctx.get(believed.get(ri));
                    Bool known = r.when() == null ? Hw.TRUE : ctx.get(r.when());
                    // only when the item leaves the stage: while it is held, what it resolves may still change
                    // (a trap is known when the instruction commits), and fetch must not follow a stale value
                    Bool always = r.always() == null ? Hw.FALSE : ctx.get(r.always());
                    Bool wrong = known.and(p.valid()).and(p.advancing()).and(next.ne(was).or(always));
                    resolveRedirect.get(ri).set(wrong);
                    resolveTarget.get(ri).set(next);
                    p.flush(wrong);
                    if (repairs != null) repairs.record(ctx, ri, 0, ri, wrong, next);
                    ctx.step = "(resolve)";
                    ctx.put(believed.get(ri + 1), mux(known, next, was));
                    ctx.step = stepName;
                }
                if (stepName.equals(m.redirectStep)) {
                    Bool taken = ctx.<Bool>get(m.redirectKey).and(p.valid());
                    redirect.set(taken);
                    target.set(ctx.get(m.targetKey));
                    p.flush(taken);
                }
            }
            if (st == 0 && predicting) {
                ctx.step = "(predict)";
                UInt g = m.predictKey != null ? ctx.get(m.predictKey) : ctx.get(m.pcKey).addWrap(m.pcIncrement);
                guess.set(g);
                ctx.put(believed.getFirst(), g);
            }
            if (st == haltStage) {
                Bool haltNow = ctx.get(m.haltKey).and(p.valid());
                p.flush(haltNow);
                when(haltNow, () -> stopFetch.set(true));
                report.add("halt: fetch stops when a halting item reaches " + s.stageNames().get(st));
            }
            Avail latest = null;
            for (var a : avail) if (a.from() <= st) latest = a;
            Data latestValue = latest == null ? null : latest.value(), latestReady = latest == null ? null : latest.ready();
            for (var prod : producers) {
                if (prod.stage() != st) continue;
                // before the write condition is known, the decode-time one (a conservative hazard; see mayWrite)
                Bool writes = m.mayWriteKey != null && !ctx.has(m.writesKey) ? ctx.get(m.mayWriteKey) : ctx.get(m.writesKey);
                prod.hit().set(writes.and(p.valid()));
                prod.rd().set(ctx.get(m.rdKey));
                if (latestValue != null) {
                    prod.value().set((UInt) latestValue);
                    prod.ready().set((Bool) latestReady);
                } else {
                    prod.value().set(UInt.lit(0, m.regFile.width()));
                    prod.ready().set(Hw.FALSE);
                }
                report.add(describeProducer(s, prod.stage(), latestValue != null, m, st));
            }
        }
        when(p.valid().and(ctx.get(m.haltKey)), () -> halted.set(true));
        if (repairs != null) repairs.finish();
        p.end();
    }

    /** A result point's value and readiness, forwardable from stage {@code from} on. */
    private record Avail(int from, Data value, Data ready) {}

    private String describeProducer(Schedule s, int stage, boolean hasResult, InOrderModel m, int st) {
        String name = s.stageNames().get(stage);
        if (!hasResult) return "forward from " + name + ": no result yet -> stall while it writes a source register";
        String point = null;
        for (var rp : m.results)
            if (s.indexOf(rp.step()) + (rp.forward() == Forward.NEXT_STAGE ? 1 : 0) <= st)
                point = rp.step() + (rp.ready() == null ? "" : " (when " + rp.ready() + ")");
        return "forward from " + name + ": result of " + point + "; stall if not ready";
    }

    private List<Producer> readRegs(InOrderModel m, Schedule s, StepContext ctx, int readStage, int writeStage) {
        var producers = new ArrayList<Producer>();
        for (int j = readStage + 1; j <= writeStage; j++)
            producers.add(new Producer(j, p.late(Bool.TYPE), p.late(UInt.w(m.regFile.addrWidth())),
                    p.late(UInt.w(m.regFile.width())), p.late(Bool.TYPE)));
        Bool stall = Hw.FALSE;
        for (var port : m.readPorts) {
            UInt idx = ctx.get(port.index());
            UInt v = m.regFile.read(idx);
            if (m.zeroRegister) v = mux(idx.eq(0), UInt.lit(0, m.regFile.width()), v);
            Bool hazardFree = m.zeroRegister ? idx.ne(0) : Hw.TRUE;
            // youngest producer (closest stage) has priority: fold from oldest to youngest
            for (int k = producers.size() - 1; k >= 0; k--) {
                var prod = producers.get(k);
                Bool hit = prod.hit().get().and(prod.rd().get().eq(idx)).and(hazardFree);
                v = mux(hit, prod.value().get(), v);
                Bool shadowed = Hw.FALSE;
                for (int y = 0; y < k; y++) {
                    var yp = producers.get(y);
                    shadowed = shadowed.or(yp.hit().get().and(yp.rd().get().eq(idx)));
                }
                stall = stall.or(ctx.<Bool>get(port.uses()).and(hit).and(shadowed.not()).and(prod.ready().get().not()));
            }
            ctx.put(port.value(), v);
        }
        p.stall(stall.and(p.valid()));
        report.add("register reads in " + s.stageNames().get(readStage) + ", writes in "
                + s.stageNames().get(writeStage) + "; " + producers.size() + " forwarding source(s)");
        return producers;
    }

    static void validate(InOrderModel m, Schedule s) {
        var errs = new ArrayList<String>();
        if (m.pcKey == null) errs.add("the model has no pc(...)");
        if (m.regFile == null) errs.add("the model has no registerFile(...)");
        int prev = 0;
        String prevName = null;
        for (var step : m.stepNames()) {
            int st = s.indexOf(step);
            if (st < 0) {
                errs.add("step '" + step + "' is not placed in any stage");
                continue;
            }
            if (st < prev) errs.add("step '" + step + "' is scheduled in " + s.stageNames().get(st)
                    + ", before step '" + prevName + "' (" + s.stageNames().get(prev) + ") which comes first in the model");
            prev = st;
            prevName = step;
        }
        int r = s.indexOf(InOrderModel.READ_REGS), w = s.indexOf(InOrderModel.WRITE_REGS);
        if (r >= 0 && w >= 0 && w < r) errs.add("registers are written before they are read");
        String redirectStep = m.redirectStep;
        if (!m.resolves.isEmpty()) {
            if (m.redirectStep != null) errs.add("the model has both redirect(...) and resolve(...); resolve replaces redirect");
            var seen = new java.util.HashMap<Integer, String>();
            for (var rs : m.resolves) {
                int st = s.indexOf(rs.step());
                if (st < 0) errs.add("resolve step '" + rs.step() + "' is not placed in any stage");
                else if (st == 0) errs.add("resolve step '" + rs.step() + "' is in the first stage, where fetch makes its guess; check it in a later stage");
                else if (seen.containsKey(st)) errs.add("steps '" + seen.get(st) + "' and '" + rs.step() + "' both resolve in " + s.stageNames().get(st));
                else seen.put(st, rs.step());
                if (redirectStep == null || st > s.indexOf(redirectStep)) redirectStep = rs.step();
            }
        } else if (m.predictKey != null) errs.add("the model predicts the next pc but nothing resolves it; add resolve(step, nextPc, when)");
        int redirect = redirectStep == null ? -1 : s.indexOf(redirectStep);
        int halt = m.haltStep == null ? -1 : Math.max(s.indexOf(m.haltStep), redirect);
        for (var step : m.steps) {
            int st = s.indexOf(step.name());
            if (!step.sideEffects() || st < 0) continue;
            if (st < redirect)
                errs.add("side-effect step '" + step.name() + "' is in " + s.stageOf(step.name())
                        + ", before redirects resolve in " + s.stageOf(redirectStep)
                        + "; a mispredicted item could commit");
            else if (st < halt)
                errs.add("side-effect step '" + step.name() + "' is in " + s.stageOf(step.name())
                        + ", before a halt takes effect in " + s.stageNames().get(halt)
                        + "; an item after the halt could commit");
        }
        Repairs.validate(m, errs);
        if (!errs.isEmpty()) throw TesseraException.here("invalid schedule " + s + ":\n  " + String.join("\n  ", errs));
    }

    /** Builds a pipeline in the current module. */
    public static InOrderPipeline build(String name, InOrderModel model, Schedule schedule) {
        return new InOrderPipeline(name, model, schedule, new Builder());
    }

    /** True once a halting item has retired. */
    public Bool halted() { return halted; }

    /** The inferred hazard logic, in words. */
    public String report() { return String.join("\n", report); }

    /** The schedule's stage names, first stage first (the signals call the first stage "s0"). */
    public List<String> stageNames() { return stageNames; }

    public int depth() { return p.depth(); }

    /** Creates the pipeline's own state registers in the current module. */
    private static final class Builder {
        UInt reg(String name, int width, long reset) {
            var s = Internal.newReg(name, width, false, java.math.BigInteger.valueOf(reset));
            return Internal.wrap(UInt.w(width), new org.eninehq.tessera.ir.Expr.Ref(s), s, -1, null);
        }

        Bool flag(String name) {
            var s = Internal.newReg(name, 1, false, java.math.BigInteger.ZERO);
            return Internal.wrap(Bool.TYPE, new org.eninehq.tessera.ir.Expr.Ref(s), s, -1, null);
        }
    }
}
