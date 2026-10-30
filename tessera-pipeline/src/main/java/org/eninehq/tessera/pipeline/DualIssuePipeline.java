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
 * Level-2 pipelines with two issue lanes: the same {@link InOrderModel} as {@link InOrderPipeline}, with a
 * schedule of two lanes ({@link Schedule#withLanes}), generated as an in-order dual-issue pipeline.
 * <ul>
 *   <li>Fetch runs the first stage's steps twice, at pc and pc+4 (a fetch pair; slot 1 is dropped when slot 0 is
 *       guessed to jump).</li>
 *   <li>The register-read stage (the second stage) issues the pair, or only its first instruction when the second
 *       reads or writes the first's destination, may not go in lane 1 ({@link InOrderModel#lanes}), sits beside a
 *       serialising instruction or must wait for an operand. The instruction left over moves to lane 0 next cycle,
 *       beside the first of the next pair, and fetch is steered so that nothing is lost.</li>
 *   <li>Every later step runs once per lane ({@link StepContext#lane()}); forwarding is from both lanes of every
 *       later stage, youngest first (lane 1 is younger than lane 0); each lane resolves its own guess, and lane 0's
 *       redirect or halt cancels lane 1 beside it.</li>
 *   <li>The register file has two write ports; same-destination pairs are not issued together, so they never
 *       write the same register in one cycle.</li>
 * </ul>
 * The model must use {@link InOrderModel#resolve} (not {@code redirect}), and its first stage may only hold steps
 * whose keys are {@link UInt} or {@link Bool} values (they pass through the lane-shift registers).
 */
public final class DualIssuePipeline {

    /** What a later stage tells the read stage about one lane's in-flight item. */
    private record Producer(int stage, int lane, Late<Bool> hit, Late<UInt> rd, Late<UInt> value, Late<Bool> ready) {}

    /** A result point's value and readiness, forwardable from stage {@code from} on. */
    private record Avail(int from, Data value, Data ready) {}

    private final Pipeline p;
    private final Bool halted;
    private final List<String> report = new ArrayList<>();
    private final List<String> stageNames;
    /** For the Studio's pipeline diagram: each lane's pc from the read stage on, lane 1's validity at each stage's end. */
    private final Data[] lanePc = new Data[2];
    private final java.util.Map<Integer, Data> lane1End = new java.util.HashMap<>();
    private java.util.function.Supplier<List<LaneProbe>> probeSource = List::of;

    /**
     * Where a lane's item can be seen in a stage, as expressions over named signals (names, {@code !}, {@code &},
     * {@code |}, {@code c ? a : b}, {@code x + n}): whether it holds one, whether the stage holds, and its pc.
     */
    public record LaneProbe(int stage, int lane, String valid, String hold, String pc) {}

    private DualIssuePipeline(String name, InOrderModel m, Schedule s) {
        stageNames = s.stageNames();
        InOrderPipeline.validate(m, s);
        validateLanes(m, s);
        int stages = s.stageNames().size();
        int readStage = s.indexOf(InOrderModel.READ_REGS), writeStage = s.indexOf(InOrderModel.WRITE_REGS);
        var resolves = new ArrayList<>(m.resolves);
        resolves.sort(java.util.Comparator.comparingInt(r -> s.indexOf(r.step())));
        int redirectStage = s.indexOf(resolves.getLast().step());
        int haltStage = m.haltStep == null ? -1 : Math.max(s.indexOf(m.haltStep), redirectStage);

        // two write ports: no FPGA RAM has those, so the register file is built from registers
        m.regFile.severalWritePorts();
        UInt pcReg = reg(name + "_pc", m.pcWidth, m.pcReset);
        Bool stopFetch = flag(name + "_stop_fetch");
        halted = flag(name + "_halted");
        // an instruction left over in lane 1 of the read stage, shifted into lane 0 next cycle
        Bool shiftPrev = flag(name + "_shift");

        p = Pipeline.begin(name, stopFetch.not());
        Repairs repairs = Repairs.of(m, p, name);
        var believed = new ArrayList<Key<UInt>>();
        for (int i = 0; i <= resolves.size(); i++) believed.add(Key.of("(next pc guess " + i + ")"));
        Key<Bool> slotOk = Key.of("(slot valid)");

        // fetch's next pc: resolve redirects (the latest stage first; lane 0 before lane 1), else the pair's guess
        Late<UInt> fetchNext = p.late(UInt.w(m.pcWidth));
        Late<Bool> split = p.late(Bool.TYPE);
        var redirects = new ArrayList<List<Late<Bool>>>();
        var targets = new ArrayList<List<Late<UInt>>>();
        UInt next = mux(p.held(0), pcReg, fetchNext.get());
        for (int i = 0; i < resolves.size(); i++) {
            var r = List.of(p.late(Bool.TYPE), p.late(Bool.TYPE));
            var t = List.of(p.late(UInt.w(m.pcWidth)), p.late(UInt.w(m.pcWidth)));
            redirects.add(r);
            targets.add(t);
            next = mux(r.get(1).get(), t.get(1).get(), next);
            next = mux(r.get(0).get(), t.get(0).get(), next);
        }
        pcReg.set(next);
        report.add("issue: two lanes; fetch reads a pair at pc and pc+4; the read stage (" + s.stageNames().get(readStage)
                + ") issues both, or the first alone when the second depends on it, writes the same register, may not go in"
                + " lane 1, waits for an operand or sits beside a serialising instruction; the one left over moves to lane 0");
        report.add("prediction: fetch takes " + (m.predictKey != null ? m.predictKey + " as each slot's next pc" : "pc+" + m.pcIncrement)
                + "; checked in " + String.join(", ", resolves.stream().map(r -> s.stageOf(r.step())).toList())
                + "; a wrong guess redirects fetch and flushes younger stages; the oldest item's redirect wins,"
                + " and lane 0's redirect cancels lane 1 beside it");

        // ---- stage 0: the fetch pair
        StepContext[] f = {ctx(m, s, 0), ctx(m, s, 1)};
        UInt pc0 = p.input(pcReg), pc1 = pc0.addWrap(m.pcIncrement);
        f[0].step = f[1].step = "(pc)";
        f[0].put(m.pcKey, pc0);
        f[1].put(m.pcKey, pc1);
        // with speculative state, slot 1's fire() says whether it is kept (fetch's updates of that state are gated with it)
        Late<Bool> kept1 = repairs != null ? p.late(Bool.TYPE) : null;
        if (kept1 != null) f[1].laneValid = kept1.get();
        for (String stepName : s.stepsIn(0, m.stepNames()))
            for (var c : f) {
                c.step = stepName;
                body(m, stepName).accept(c);
            }
        UInt[] g = new UInt[2];
        for (int l = 0; l < 2; l++) {
            f[l].step = "(predict)";
            g[l] = m.predictKey != null ? f[l].get(m.predictKey) : f[l].get(m.pcKey).addWrap(m.pcIncrement);
            f[l].put(believed.getFirst(), g[l]);
        }
        Bool s1ok = g[0].eq(pc1);
        f[0].put(slotOk, Hw.TRUE);
        f[1].put(slotOk, s1ok);
        // slot 0 guessed to jump: go there (slot 1 is dropped); a split keeps slot 1 for later: fetch from it again
        fetchNext.set(mux(s1ok, mux(split.get(), pc1, g[1]), g[0]));
        // slot 1 is dropped behind a guessed jump in slot 0, and fetched again after a split
        if (kept1 != null) kept1.set(s1ok.and(split.get().not()));

        // ---- the stages from the read stage on, one context per lane
        StepContext[] c = {ctx(m, s, 0), ctx(m, s, 1)};
        List<Producer> producers = new ArrayList<>();
        List<List<Avail>> avail = List.of(new ArrayList<>(), new ArrayList<>());
        Bool[] stall = new Bool[2];
        Bool wrong0Read = Hw.FALSE, lv1Raw = null, lane1Here = null;
        Late<Bool> pairOk = null;
        // the flushes from stages after the read stage (they cancel a left-over instruction), one late value each,
        // made in the read stage where they are used
        List<Late<Bool>> deep = new ArrayList<>();
        int deepNext = 0;
        Bool deepFlush = null;
        List<Data[]> shifted = new ArrayList<>();
        for (int st = 1; st < stages; st++) {
            p.stage(s.stageNames().get(st));
            if (st == 1) {
                // the lane shift: lane 0 is the left-over instruction or slot 0, lane 1 is slot 0 or slot 1
                c[0].step = c[1].step = "(issue)";
                for (Key<?> k : f[0].keys()) {
                    Data in0 = (Data) f[0].get(k), in1 = (Data) f[1].get(k);
                    Data left = shadow(name + "_left_" + k.name().replaceAll("\\W", "_"), in0);
                    Data v0 = mux(shiftPrev, left, in0), v1 = mux(shiftPrev, in0, in1);
                    putRaw(c[0], k, v0);
                    if (k == m.pcKey) { lanePc[0] = v0; lanePc[1] = v1; }
                    putRaw(c[1], k, v1);
                    shifted.add(new Data[]{left, v1});
                }
                lv1Raw = c[1].get(slotOk);
                pairOk = p.late(Bool.TYPE);
                int n = (haltStage >= 2 ? 1 : 0);
                for (var r : resolves) if (s.indexOf(r.step()) >= 2) n += 2;
                deepFlush = Hw.FALSE;
                for (int i = 0; i < n; i++) {
                    deep.add(p.late(Bool.TYPE));
                    deepFlush = deepFlush.or(deep.getLast().get());
                }
                deepFlush = deepFlush.named(name + "_deep_flush");
                c[1].laneValid = lv1Raw.and(pairOk.get());
            }
            c[1].entryValid = c[1].laneValid;
            for (String stepName : s.stepsIn(st, m.stepNames())) {
                for (int l = 0; l < 2; l++) {
                    var cx = c[l];
                    cx.step = stepName;
                    switch (stepName) {
                        case InOrderModel.READ_REGS -> {
                            if (l == 0) producers = producers(m, readStage, writeStage);
                            stall[l] = readRegs(m, cx, producers, l);
                            if (l == 0) p.stall(stall[0].and(p.valid()));
                        }
                        case InOrderModel.WRITE_REGS -> {
                            UInt rd = cx.get(m.rdKey);
                            UInt v = cx.get(m.rdValueKey);
                            when(cx.fire().and(cx.get(m.writesKey)), () -> m.regFile.write(rd, v));
                        }
                        default -> body(m, stepName).accept(cx);
                    }
                    for (var rp : m.results)
                        if (rp.step().equals(stepName))
                            avail.get(l).add(new Avail(rp.forward() == Forward.NEXT_STAGE ? st + 1 : st, cx.get(rp.value()),
                                    rp.ready() == null ? Hw.TRUE : cx.get(rp.ready())));
                    int ri = -1;
                    for (int i = 0; i < resolves.size(); i++) if (resolves.get(i).step().equals(stepName)) ri = i;
                    if (ri >= 0 && l == 0) lane1Here = c[1].laneValid;
                    if (ri >= 0) {
                        var r = resolves.get(ri);
                        UInt nx = cx.get(r.nextPc()), was = cx.get(believed.get(ri));
                        Bool known = r.when() == null ? Hw.TRUE : cx.get(r.when());
                        // as in InOrderPipeline: only when the stage advances (a held item's values may be stale), and
                        // always refetch after a trap, interrupt or return (`always`), even if the guess was right
                        Bool always = r.always() == null ? Hw.FALSE : cx.get(r.always());
                        // the guess is compared with the next pc when nothing forces a refetch (plainNext), so the
                        // comparison runs beside the logic that decides `always`
                        UInt cmp = r.plainNext() == null ? nx : cx.get(r.plainNext());
                        // lane 1 is valid here before lane 0's redirect in this stage cancels it: when both redirect,
                        // the pc mux takes lane 0's target, so lane 1's decision need not wait for lane 0's
                        Bool valid = l == 1 ? p.valid().and(lane1Here) : cx.valid();
                        Bool wrong = known.and(valid).and(p.advancing()).and(cmp.ne(was).or(always));
                        redirects.get(ri).get(l).set(wrong);
                        targets.get(ri).get(l).set(nx);
                        p.flush(wrong);
                        // the same priority as the pc mux: the latest resolve point, lane 0 before lane 1
                        if (repairs != null) repairs.record(cx, ri, l, 2 * ri + (l == 0 ? 1 : 0), wrong, nx);
                        if (st >= 2) deep.get(deepNext++).set(wrong);
                        cx.step = "(resolve)";
                        cx.put(believed.get(ri + 1), mux(known, nx, was));
                        cx.step = stepName;
                        if (l == 0) {
                            // lane 1 is younger: a redirect in lane 0 cancels it
                            c[1].laneValid = c[1].laneValid.and(wrong.not());
                            if (st == readStage) wrong0Read = wrong0Read.or(wrong);
                        }
                    }
                }
            }
            if (st == readStage) {
                // pairing: lane 1 issues beside lane 0 unless one of these holds
                Bool raw = Hw.FALSE;
                for (var port : m.readPorts) {
                    UInt idx = c[1].get(port.index());
                    Bool hazard = c[1].<Bool>get(port.uses()).and(idx.eq(c[0].get(m.rdKey))).and(c[0].get(m.issueWritesKey));
                    raw = raw.or(m.zeroRegister ? hazard.and(idx.ne(0)) : hazard);
                }
                Bool waw = c[0].<Bool>get(m.issueWritesKey).and(c[1].get(m.issueWritesKey)).and(c[0].<UInt>get(m.rdKey).eq(c[1].get(m.rdKey)));
                Bool ok = lv1Raw.and(c[1].get(m.lane1OkKey)).and(c[0].<Bool>get(m.serializesKey).not())
                        .and(raw.not()).and(waw.not()).and(stall[1].not()).and(wrong0Read.not());
                // the model's own rules (a unit the lanes share): lane 0's key and lane 1's key split the pair
                for (var pc : m.pairConflicts)
                    ok = ok.and(c[0].<Bool>get(pc.older()).and(c[1].get(pc.younger())).not());
                ok = ok.named(name + "_pair");
                pairOk.set(ok);
                Bool splitNow = p.valid().and(lv1Raw).and(ok.not()).and(wrong0Read.not()).named(name + "_split");
                split.set(splitNow);
                Bool keep = p.advancing().and(splitNow);
                for (var sh : shifted) {
                    Data left = sh[0], v = sh[1];
                    when(keep, () -> {
                        if (left instanceof Bool lb) lb.set((Bool) v);
                        else ((UInt) left).set((UInt) v);
                    });
                }
                shiftPrev.set(mux(p.advancing(), splitNow, shiftPrev).and(deepFlush.not()));
                report.add("pairing in " + s.stageNames().get(st) + ": lane 1 waits when it reads or writes lane 0's destination, when "
                        + m.lane1OkKey + " is false, when " + m.serializesKey + " holds in lane 0, or when its operand is not ready"
                        + String.join("", m.pairConflicts.stream().map(x -> "; also when lane 0 has " + x.older() + " and lane 1 has " + x.younger()).toList()));
            }
            if (st == haltStage) {
                Bool halt0 = c[0].<Bool>get(m.haltKey).and(c[0].valid());
                Bool halt1 = c[1].<Bool>get(m.haltKey).and(c[1].valid()).and(halt0.not());
                Bool haltNow = halt0.or(halt1);
                p.flush(haltNow);
                if (st >= 2) deep.get(deepNext++).set(haltNow);
                when(haltNow, () -> stopFetch.set(true));
                c[1].laneValid = c[1].laneValid.and(halt0.not());
                report.add("halt: fetch stops when a halting item reaches " + s.stageNames().get(st) + "; lane 1 beside it is cancelled");
            }
            lane1End.put(st, c[1].laneValid);
            for (var prod : producers) {
                if (prod.stage() != st) continue;
                var cx = c[prod.lane()];
                Avail latest = null;
                for (var a : avail.get(prod.lane())) if (a.from() <= st) latest = a;
                prod.hit().set(cx.<Bool>get(m.writesKey).and(cx.valid()));
                prod.rd().set(cx.get(m.rdKey));
                if (latest != null) {
                    prod.value().set((UInt) latest.value());
                    prod.ready().set((Bool) latest.ready());
                } else {
                    prod.value().set(UInt.lit(0, m.regFile.width()));
                    prod.ready().set(Hw.FALSE);
                }
                if (prod.lane() == 0)
                    report.add("forward from " + s.stageNames().get(st) + " (both lanes, lane 1 first): "
                            + (latest == null ? "no result yet -> stall" : "latest result point; stall if not ready"));
            }
        }
        Bool done = c[0].<Bool>get(m.haltKey).and(c[0].valid()).or(c[1].<Bool>get(m.haltKey).and(c[1].valid()));
        when(done, () -> halted.set(true));
        if (repairs != null) repairs.finish();
        p.end();
        probeSource = laneProbes(name, s, stages);
    }

    private StepContext ctx(InOrderModel m, Schedule s, int lane) {
        var c = new StepContext(m, s);
        c.lane = lane;
        c.pipeline = p;
        c.halted = halted;
        return c;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void putRaw(StepContext c, Key k, Data v) {
        c.put(k, v);
    }

    private static java.util.function.Consumer<StepContext> body(InOrderModel m, String stepName) {
        return m.steps.stream().filter(x -> x.name().equals(stepName)).findFirst().orElseThrow().body();
    }

    /** Producers youngest first: the stage after the read stage, lane 1 then lane 0, then the next stage, ... */
    private List<Producer> producers(InOrderModel m, int readStage, int writeStage) {
        var out = new ArrayList<Producer>();
        for (int j = readStage + 1; j <= writeStage; j++)
            for (int l = 1; l >= 0; l--)
                out.add(new Producer(j, l, p.late(Bool.TYPE), p.late(UInt.w(m.regFile.addrWidth())),
                        p.late(UInt.w(m.regFile.width())), p.late(Bool.TYPE)));
        return out;
    }

    /** The register reads of one lane, with forwarding; returns whether that lane must wait for an operand. */
    private static Bool readRegs(InOrderModel m, StepContext ctx, List<Producer> producers, int lane) {
        Bool stall = Hw.FALSE;
        for (var port : m.readPorts) {
            UInt idx = ctx.get(port.index());
            UInt v = m.regFile.read(idx);
            if (m.zeroRegister) v = mux(idx.eq(0), UInt.lit(0, m.regFile.width()), v);
            Bool hazardFree = m.zeroRegister ? idx.ne(0) : Hw.TRUE;
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
        return stall;
    }

    private static void validateLanes(InOrderModel m, Schedule s) {
        var errs = new ArrayList<String>();
        if (s.lanes() != 2) errs.add("a dual-issue pipeline needs a schedule with two lanes (Schedule.withLanes(2))");
        if (m.resolves.isEmpty()) errs.add("a dual-issue model says where guesses are checked with resolve(...), not redirect(...)");
        if (m.lane1OkKey == null) errs.add("a dual-issue model says what may go in lane 1 with lanes(lane1Ok, serializes)");
        if (s.indexOf(InOrderModel.READ_REGS) != 1)
            errs.add("the register reads must be in the second stage, where the pair is issued (they are in "
                    + s.stageOf(InOrderModel.READ_REGS) + ")");
        if (m.haltStep == null) errs.add("a dual-issue model needs halt(...)");
        for (var step : s.stepsIn(0, m.stepNames()))
            if (step.equals(InOrderModel.READ_REGS) || step.equals(InOrderModel.WRITE_REGS))
                errs.add("the first stage holds only fetch steps, not " + step);
        if (!errs.isEmpty()) throw TesseraException.here("invalid dual-issue schedule " + s + ":\n  " + String.join("\n  ", errs));
    }

    /** A register like {@code x} (a {@link UInt} or {@link Bool}), outside the pipeline. */
    private static Data shadow(String name, Data x) {
        var r = Internal.newReg(name, x.width(), false, null);
        var ref = new org.eninehq.tessera.ir.Expr.Ref(r);
        if (x instanceof Bool) return Internal.wrap(Bool.TYPE, ref, r, -1, null);
        if (x instanceof UInt) return Internal.wrap(UInt.w(x.width()), ref, r, -1, null);
        throw TesseraException.here("a dual-issue pipeline's first stage may only produce UInt and Bool values, not " + x.getClass().getSimpleName());
    }

    private static UInt reg(String name, int width, long reset) {
        var s = Internal.newReg(name, width, false, java.math.BigInteger.valueOf(reset));
        return Internal.wrap(UInt.w(width), new org.eninehq.tessera.ir.Expr.Ref(s), s, -1, null);
    }

    private static Bool flag(String name) {
        var s = Internal.newReg(name, 1, false, java.math.BigInteger.ZERO);
        return Internal.wrap(Bool.TYPE, new org.eninehq.tessera.ir.Expr.Ref(s), s, -1, null);
    }

    /** Builds a dual-issue pipeline in the current module. */
    public static DualIssuePipeline build(String name, InOrderModel model, Schedule schedule) {
        return new DualIssuePipeline(name, model, schedule);
    }

    /** True once a halting item has retired. */
    public Bool halted() { return halted; }

    /** The inferred issue, hazard and redirect logic, in words. */
    public String report() { return String.join("\n", report); }

    /** The diagram's probes: lane 1 travels in registers the generator named only by number, so they are listed here. */
    public List<LaneProbe> laneProbes() { return probeSource.get(); }

    /**
     * Each lane's probes. The fetch stage shows the pair as one item (lane 0). In the read stage lane 0 is the
     * left-over instruction or slot 0, and lane 1 is slot 0 or slot 1 ({@code _shift}), there when it issues or waits
     * ({@code _pair}, {@code _split}). From then on each lane's pc and lane 1's validity are pipeline registers that
     * exist already: they are looked up, never added, so the hardware is the same with or without the diagram.
     * Made when asked, after elaboration: only then do the registers have their final, unique names.
     */
    private java.util.function.Supplier<List<LaneProbe>> laneProbes(String name, Schedule s, int stages) {
        // the registers are found now, while elaborating; their names are read when the probes are asked for
        var regs = new ArrayList<org.eninehq.tessera.ir.Signal[]>();
        for (int k = 2; k < stages; k++)
            regs.add(new org.eninehq.tessera.ir.Signal[]{
                    lanePc[0] == null ? null : p.existingRegister(lanePc[0], k),
                    lanePc[1] == null ? null : p.existingRegister(lanePc[1], k),
                    lane1End.get(k - 1) == null ? null : p.existingRegister(lane1End.get(k - 1), k)});
        return () -> {
            var out = new ArrayList<LaneProbe>();
            java.util.function.IntFunction<String> hold = k -> name + "_hold_" + (k == 0 ? "s0" : s.stageNames().get(k));
            java.util.function.IntFunction<String> valid = k -> name + "_valid_" + s.stageNames().get(k);
            // the fetch pair: slot 1 is fetched too, and dropped when slot 0 is guessed to jump or a split refetches it
            out.add(new LaneProbe(0, 0, "!" + name + "_stop_fetch", hold.apply(0), name + "_pc"));
            out.add(new LaneProbe(0, 1, "!" + name + "_stop_fetch", hold.apply(0), name + "_pc + 4"));
            String sh = name + "_shift", pcId = name + "_pc_" + s.stageNames().get(1);
            out.add(new LaneProbe(1, 0, valid.apply(1), hold.apply(1), sh + " ? " + name + "_left_pc : " + pcId));
            out.add(new LaneProbe(1, 1, valid.apply(1) + " & (" + name + "_pair | " + name + "_split)", hold.apply(1),
                    sh + " ? " + pcId + " : " + pcId + " + 4"));
            for (int k = 2; k < stages; k++) {
                var r = regs.get(k - 2);
                out.add(new LaneProbe(k, 0, valid.apply(k), hold.apply(k), r[0] == null ? null : r[0].name()));
                if (r[2] != null) out.add(new LaneProbe(k, 1, valid.apply(k) + " & " + r[2].name(), hold.apply(k), r[1] == null ? null : r[1].name()));
            }
            return out;
        };
    }

    /** The schedule's stage names, first stage first (the signals call the first stage "s0"). */
    public List<String> stageNames() { return stageNames; }

    public int depth() { return p.depth(); }
}
