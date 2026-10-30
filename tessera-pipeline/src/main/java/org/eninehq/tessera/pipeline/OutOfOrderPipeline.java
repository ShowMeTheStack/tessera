package org.eninehq.tessera.pipeline;

import static org.eninehq.tessera.core.Hw.mux;
import static org.eninehq.tessera.core.Hw.when;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.eninehq.tessera.core.Bool;
import org.eninehq.tessera.core.Data;
import org.eninehq.tessera.core.Hw;
import org.eninehq.tessera.core.HwType;
import org.eninehq.tessera.core.Internal;
import org.eninehq.tessera.core.TesseraException;
import org.eninehq.tessera.core.UInt;
import org.eninehq.tessera.ir.Expr;
import org.eninehq.tessera.ir.Signal;

/**
 * Level-2 out-of-order cores: generate a Tomasulo machine with a reorder buffer from an {@link InOrderModel} and an
 * {@link OooSchedule}, with the organisation of the hand-written out-of-order core (C7): in-order fetch and dispatch,
 * renaming through a register alias table, a shared reservation station with oldest-ready-first issue per unit,
 * result buses with wake-up, and in-order commit at the head of the reorder buffer, where every side effect happens
 * and every resolve point is acted on (a wrong guess flushes everything younger and refetches).
 *
 * <p>The model's keys become state as follows:
 * <ul>
 *   <li>what the fetch step produces is carried with the item: in the dispatch register, and in the reservation
 *       station and reorder buffer entries <em>where a later step reads it</em> (the per-entry registers are created on
 *       first use);</li>
 *   <li>what the decode steps produce is recomputed wherever it is needed (they are combinational);</li>
 *   <li>register-read values become renamed operands: ready from the register file, a finished entry or a result bus,
 *       or woken up later by a bus;</li>
 *   <li>a unit's result (its last result point) goes on its bus and into the item's reorder-buffer entry; whether it
 *       is final is the result point's ready key (if not, the value is known only at commit: loads, CSR reads, and it
 *       is broadcast on the commit bus);</li>
 *   <li>a registered unit latches what its issue steps produce, on first use by its unit steps;</li>
 *   <li>at commit, the recomputed steps run again on the carried values and operands, and the unit results that are
 *       not recomputed are the entry's value.</li>
 * </ul>
 * A redirect at commit fetches the new path in the same cycle, as C7 does; if the fetch step captures state
 * ({@link StepContext#capture}), from the next cycle instead, as in the in-order generator, so that the captured value
 * is the one after the redirecting item's commit. A decode-time resolve redirects fetch in the dispatch cycle.
 */
public final class OutOfOrderPipeline {
    private final InOrderModel m;
    private final OooSchedule s;
    private final String name;
    private final Bool halted;
    private final List<String> report = new ArrayList<>();
    private final int robN, rsN, tagW;
    private final Schedule messages;
    private final Set<Object> registeredValues = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<String> regNames = new HashSet<>();

    private OutOfOrderPipeline(String name, InOrderModel m, OooSchedule s) {
        this.name = name;
        this.m = m;
        this.s = s;
        this.robN = s.rob;
        this.rsN = s.rs;
        this.tagW = Math.max(1, Integer.numberOfTrailingZeros(s.rob));
        this.messages = validate(m, s);
        int pcW = m.pcWidth, valW = m.regFile.width(), nUnits = s.units.size();
        int unitW = Math.max(1, 32 - Integer.numberOfLeadingZeros(nUnits - 1));

        // ------------------------------------------------------------ state and wires
        UInt pc = reg("pc", pcW, m.pcReset);
        Bool fv = flag("fetch_valid");
        Bool stopFetch = flag("stop_fetch");
        halted = flag("halted");
        Bool flush = wireB("flush");
        UInt flushTarget = wire("flush_target", pcW);
        Bool dispatch = wireB("dispatch");
        Bool fetchEnable = wireB("fetch_en");
        UInt fetchAddr = wire("fetch_addr", pcW);
        UInt frontTarget = wire("front_target", pcW);
        Bool frontRedirect = wireB("front_redirect");
        // recovery at execute: the resolving unit's redirect, its target and the resolving item's tag
        boolean early = s.recoverAtExecute;
        Bool exR = early ? wireB("ex_redirect") : Hw.FALSE;
        UInt exTarget = early ? wire("ex_target", pcW) : UInt.lit(0, pcW);
        UInt exTag = early ? wire("ex_tag", tagW) : UInt.lit(0, tagW);

        // ------------------------------------------------------------ fetch
        var fplace = new SimplePlace("fetch", stopFetch.not().and(halted.not()), fetchEnable, true);
        var fctx = ctx(fplace);
        fctx.step = "(pc)";
        fctx.put(m.pcKey, fetchAddr);
        run(fctx, s.fetchStep);
        UInt guess0 = m.predictKey != null ? fctx.get(m.predictKey) : fetchAddr.addWrap(m.pcIncrement);
        // what fetch produced, as seen in the dispatch stage
        Map<Key<?>, Object> fetched = new LinkedHashMap<>();
        for (var k : fctx.keys()) {
            Object v = fctx.get(k);
            if (!(v instanceof Data) && !(v instanceof Record))
                throw TesseraException.here("fetch step '" + s.fetchStep + "' produces " + k + ", which is not a hardware value");
            fetched.put(k, registeredValues.contains(v) ? v : latch("d_" + k.name(), v, fetchEnable));
        }
        UInt dGuess = (UInt) latch("d_guess", guess0, fetchEnable);
        // a commit-time redirect fetches the new path in the same cycle, unless fetch captures state (capture(...)):
        // the commit that redirects may change it, and the captured value must be the one after it, as in order
        boolean refetchNow = fplace.captures == 0;
        UInt sequential = mux(dispatch.and(frontRedirect), frontTarget, pc);
        Bool redirectAny = flush.or(exR);
        UInt redirectTarget = mux(flush, flushTarget, exTarget);
        fetchAddr.set(refetchNow ? mux(redirectAny, redirectTarget, sequential) : sequential);
        Bool want = fv.not().or(dispatch);
        fetchEnable.set((refetchNow ? want.or(redirectAny) : want.and(redirectAny.not())).and(stopFetch.not()).and(halted.not()));
        when(fetchEnable, () -> {
            fv.set(true);
            pc.set(guess0);
        });
        if (!refetchNow)
            when(redirectAny, () -> {
                fv.set(false);
                pc.set(redirectTarget);
            });
        report.add("fetch: " + s.fetchStep + " at the fetch address; " + (m.predictKey != null ? m.predictKey : "pc+" + m.pcIncrement)
                + " is the guess; after a commit-time redirect the new path is fetched "
                + (refetchNow ? "in the same cycle" : "from the next cycle (fetch captures state)") + "; " + fetched.size() + " key(s) carried: " + String.join(", ", fetched.keySet().stream().map(Key::name).toList()));

        // ------------------------------------------------------------ decode, front-end resolve, rename
        var dctx = ctx(new SimplePlace("dispatch", fv, dispatch, false));
        dctx.step = "(fetch)";
        fetched.forEach((k, v) -> putRaw(dctx, k, v));
        for (var st : s.decodeSteps) run(dctx, st);
        UInt believed = dGuess;
        Bool always = Hw.FALSE;
        int frontResolves = 0;
        for (var r : orderedResolves()) {
            if (!s.decodeSteps.contains(r.step())) continue;
            UInt next = dctx.get(r.nextPc());
            Bool known = r.when() == null ? Hw.TRUE : dctx.get(r.when());
            believed = mux(known, next, believed);
            if (r.always() != null) always = always.or(dctx.get(r.always()));
            frontResolves++;
        }
        frontRedirect.set(believed.ne(dGuess).or(always));
        frontTarget.set(believed);
        UInt dBelieved = believed;
        if (frontResolves > 0) report.add("decode: " + frontResolves + " resolve point(s); a wrong guess redirects fetch when the item dispatches");

        // reorder buffer: fixed fields
        UInt head = reg("rob_head", tagW, 0), tail = reg("rob_tail", tagW, 0);
        UInt count = reg("rob_count", tagW + 1, 0);
        Bool[] rValid = new Bool[robN], rDone = new Bool[robN], rValRdy = new Bool[robN];
        UInt[] rVal = new UInt[robN];
        int ports = m.readPorts.size();
        UInt[][] rOp = new UInt[ports][robN];
        for (int e = 0; e < robN; e++) {
            rValid[e] = flag("rob" + e + "_valid");
            rDone[e] = flag("rob" + e + "_done");
            rValRdy[e] = flag("rob" + e + "_value_ready");
            rVal[e] = reg("rob" + e + "_value", valW, 0);
            for (int p = 0; p < ports; p++) rOp[p][e] = reg("rob" + e + "_op" + p, valW, 0);
        }
        Field robGuess = new Field("rob_next_guess", dBelieved, robN, (e, v) -> when(dispatch.and(tail.eq(e)), () -> Hw.assign(v, dBelieved)));
        Map<Key<?>, Field> robFields = new LinkedHashMap<>();

        // result buses: one per unit, then the commit bus
        int buses = nUnits + 1;
        Bool[] busOn = new Bool[buses];
        UInt[] busTag = new UInt[buses], busVal = new UInt[buses];
        for (int b = 0; b < buses; b++) {
            String bn = b < nUnits ? s.units.get(b).name() : "commit";
            busOn[b] = wireB("bus_" + bn + "_on");
            busTag[b] = wire("bus_" + bn + "_tag", tagW);
            busVal[b] = wire("bus_" + bn + "_value", valW);
        }

        // register alias table
        int nRegs = m.regFile.depth();
        Bool[] ratBusy = new Bool[nRegs];
        UInt[] ratTag = new UInt[nRegs];
        for (int r = 0; r < nRegs; r++) {
            if (r == 0 && m.zeroRegister) {
                ratBusy[0] = Hw.FALSE;
                ratTag[0] = UInt.lit(0, tagW);
                continue;
            }
            ratBusy[r] = flag("rat" + r + "_busy");
            ratTag[r] = reg("rat" + r + "_tag", tagW, 0);
        }

        // operands
        Bool[] opReady = new Bool[ports];
        UInt[] opValue = new UInt[ports], opTag = new UInt[ports];
        for (int p = 0; p < ports; p++) {
            var port = m.readPorts.get(p);
            UInt idx = dctx.get(port.index());
            Bool uses = dctx.get(port.uses());
            Bool busy = pickB(idx, ratBusy).and(uses);
            if (m.zeroRegister) busy = busy.and(idx.ne(0));
            UInt tag = pick(idx, ratTag);
            Bool inRob = pickB(tag, rDone).and(pickB(tag, rValRdy));
            UInt value = mux(inRob, pick(tag, rVal), UInt.lit(0, valW));
            Bool onBus = Hw.FALSE;
            for (int b = buses - 1; b >= 0; b--) {
                Bool hit = busOn[b].and(busTag[b].eq(tag));
                value = mux(hit, busVal[b], value);
                onBus = onBus.or(hit);
            }
            UInt file = m.regFile.read(idx);
            if (m.zeroRegister) file = mux(idx.eq(0), UInt.lit(0, valW), file);
            opReady[p] = busy.not().or(inRob).or(onBus);
            opValue[p] = mux(busy, value, file);
            opTag[p] = tag;
        }
        report.add("rename: " + ports + " operand(s) from the register file, a finished entry or a result bus ("
                + buses + " buses); " + (s.renames != null ? s.renames : m.mayWriteKey) + " renames " + m.rdKey);

        // which unit takes the item
        UInt unitSel = UInt.lit(defaultUnit(), unitW);
        for (int u = nUnits - 1; u >= 0; u--) {
            var unit = s.units.get(u);
            if (unit.takes() != null) unitSel = mux(dctx.get(unit.takes()), UInt.lit(u, unitW), unitSel);
        }

        // reservation station
        Bool[] sValid = new Bool[rsN];
        UInt[] sUnit = new UInt[rsN], sTag = new UInt[rsN];
        Bool[][] sRdy = new Bool[ports][rsN];
        UInt[][] sVal = new UInt[ports][rsN], sOpTag = new UInt[ports][rsN];
        for (int j = 0; j < rsN; j++) {
            sValid[j] = flag("rs" + j + "_valid");
            sUnit[j] = reg("rs" + j + "_unit", unitW, 0);
            sTag[j] = reg("rs" + j + "_tag", tagW, 0);
            for (int p = 0; p < ports; p++) {
                sRdy[p][j] = flag("rs" + j + "_op" + p + "_ready");
                sVal[p][j] = reg("rs" + j + "_op" + p, valW, 0);
                sOpTag[p][j] = reg("rs" + j + "_op" + p + "_tag", tagW, 0);
            }
        }
        Bool[] sFree = new Bool[rsN];
        for (int j = 0; j < rsN; j++) sFree[j] = sValid[j].not();
        Bool[] slot = first(sFree);
        dispatch.set(fv.and(flush.not()).and(exR.not()).and(halted.not()).and(stopFetch.not()).and(count.ne(robN)).and(any(slot)));
        Map<Key<?>, Field> rsFields = new LinkedHashMap<>();
        UInt dRd = dctx.get(m.rdKey);
        Bool dWrites = dctx.get(s.renames != null ? s.renames : m.mayWriteKey);
        if (m.zeroRegister) dWrites = dWrites.and(dRd.ne(0));
        final Bool dW = dWrites;
        // recovery at execute: each RS entry's next-pc guess; each ROB entry's destination, to rebuild the RAT
        Field rsGuess = early ? new Field("rs_next_guess", dBelieved, rsN, (j, v) -> when(dispatch.and(slot[j]), () -> Hw.assign(v, dBelieved))) : null;
        UInt[] rRd = new UInt[robN];
        Bool[] rW = new Bool[robN];
        if (early)
            for (int e = 0; e < robN; e++) {
                final int x = e;
                rRd[e] = reg("rob" + e + "_rd", dRd.width(), 0);
                rW[e] = flag("rob" + e + "_writes");
                when(dispatch.and(tail.eq(x)), () -> {
                    rRd[x].set(dRd);
                    rW[x].set(dW);
                });
            }
        // loads out of order: each ROB entry's store flag (at dispatch) and, once the store has executed, its word
        // index, byte mask and data in lane positions
        var lsq = s.lsq;
        Bool[] rIsStore = new Bool[robN], rKnown = new Bool[robN];
        UInt[] rIdx = new UInt[robN], rSMask = new UInt[robN], rSData = new UInt[robN];
        if (lsq != null) {
            Bool dStore = dctx.get(lsq.isStore());
            for (int e = 0; e < robN; e++) {
                final int x = e;
                rIsStore[e] = flag("rob" + e + "_store");
                rKnown[e] = flag("rob" + e + "_store_known");
                when(dispatch.and(tail.eq(x)), () -> {
                    rIsStore[x].set(dStore);
                    rKnown[x].set(false);
                });
            }
        }
        record Busy(Bool valid, Bool leaving, UInt tag, Bool zombie, Bool issue, UInt issueTag) {}
        List<Busy> registeredUnits = new ArrayList<>();
        record UnitOut(StepContext ctx, Bool done, UInt tag) {}
        List<UnitOut> unitOuts = new ArrayList<>();
        int resolvingUnits = 0;
        for (int j = 0; j < rsN; j++) {
            final int k = j;
            final UInt sel = unitSel;
            when(dispatch.and(slot[k]), () -> {
                sValid[k].set(true);
                sUnit[k].set(sel);
                sTag[k].set(tail);
                for (int p = 0; p < ports; p++) {
                    sRdy[p][k].set(opReady[p]);
                    sVal[p][k].set(opValue[p]);
                    sOpTag[p][k].set(opTag[p]);
                }
            });
        }
        for (int e = 0; e < robN; e++) {
            final int x = e;
            when(dispatch.and(tail.eq(x)), () -> {
                rValid[x].set(true);
                rDone[x].set(false);
                rValRdy[x].set(false);
            });
        }

        // ------------------------------------------------------------ execution units
        UInt headNow = head;
        for (int u = 0; u < nUnits; u++) {
            var unit = s.units.get(u);
            final int ui = u;
            Bool[] cand = new Bool[rsN];
            Bool free = unit.registered() ? wireB("unit_" + unit.name() + "_free") : Hw.TRUE;
            for (int j = 0; j < rsN; j++) {
                Bool c = sValid[j].and(sUnit[j].eq(ui)).and(free);
                for (int p = 0; p < ports; p++) c = c.and(sRdy[p][j]);
                cand[j] = c;
            }
            Bool[] pickSel = oldest(cand, sTag, headNow);
            boolean loadUnit = lsq != null && lsq.unit().equals(unit.name());
            // the load unit issues its oldest ready load only when memory ordering allows it (set below)
            Bool gate = loadUnit ? wireB("unit_" + unit.name() + "_order_ok") : Hw.TRUE;
            Bool picked = any(pickSel).and(flush.not());
            Bool issue = (loadUnit ? picked.and(gate) : picked).named(name + "_" + unit.name() + "_issue");
            UInt issueTag = pickOneHot(pickSel, sTag);
            for (int j = 0; j < rsN; j++) {
                final int k = j;
                Bool taken = pickSel[k].and(flush.not());
                when(loadUnit ? taken.and(gate) : taken, () -> sValid[k].set(false));
            }
            // the issue cycle: the picked item's carried values and operands (the load unit reads memory here)
            var ictx = ctx(loadUnit ? new SimplePlace(unit.name() + " issue", issue, issue, true)
                    : new SimplePlace(unit.name() + " issue", issue, Hw.TRUE, false));
            ictx.carried = key -> {
                if (!fetched.containsKey(key)) return null;
                Field f = rsFields.computeIfAbsent(key, kk -> new Field("rs_" + kk.name(), fetched.get(kk), rsN,
                        (j, v) -> when(dispatch.and(slot[j]), () -> Hw.assign(v, dctx.get(kk)))));
                return f.oneHot(pickSel);
            };
            ictx.step = "(operands)";
            UInt[] issueOps = new UInt[ports];
            for (int p = 0; p < ports; p++) {
                issueOps[p] = pickOneHot(pickSel, sVal[p]);
                ictx.put(m.readPorts.get(p).value(), issueOps[p]);
            }
            for (var st : s.decodeSteps) run(ictx, st);
            for (int e = 0; e < robN; e++) {
                final int x = e;
                when(issue.and(issueTag.eq(x)), () -> {
                    for (int p = 0; p < ports; p++) rOp[p][x].set(issueOps[p]);
                });
            }
            InOrderModel.ResultPoint rp = resultOf(unit);
            StepContext unitCtx = null;
            UInt resVal;
            Bool resReady, leaving, zombie;
            UInt resTag;
            if (!unit.registered()) {
                for (var st : unit.steps()) run(ictx, st);
                resVal = ictx.get(rp.value());
                resReady = rp.ready() == null ? Hw.TRUE : ictx.get(rp.ready());
                leaving = issue;
                zombie = Hw.FALSE;
                resTag = issueTag;
                report.add("unit " + unit.name() + ": " + String.join(", ", unit.steps()) + " in the issue cycle; result "
                        + rp.value() + (rp.ready() == null ? "" : " when " + rp.ready() + ", else at commit"));
                if (lsq != null && ictx.has(lsq.storeData())) {
                    Bool st = issue.and(ictx.get(lsq.isStore()));
                    UInt ix = ictx.get(lsq.index()), mk = ictx.get(lsq.storeMask()), dt = ictx.get(lsq.storeData());
                    for (int e = 0; e < robN; e++) {
                        final int x = e;
                        if (rIdx[x] == null) {
                            rIdx[x] = reg("rob" + x + "_store_index", ix.width(), 0);
                            rSMask[x] = reg("rob" + x + "_store_mask", mk.width(), 0);
                            rSData[x] = reg("rob" + x + "_store_data", dt.width(), 0);
                        }
                        when(st.and(issueTag.eq(x)), () -> {
                            rKnown[x].set(true);
                            rIdx[x].set(ix);
                            rSMask[x].set(mk);
                            rSData[x].set(dt);
                        });
                    }
                    report.add("unit " + unit.name() + ": executes stores; their index, mask and data go to the ROB for the loads");
                }
                if (early) {
                    UInt g = (UInt) rsGuess.oneHot(pickSel), b = g;
                    int n = 0;
                    for (var r : orderedResolves()) {
                        if (!unit.steps().contains(r.step())) continue;
                        Bool known = r.when() == null ? Hw.TRUE : ictx.get(r.when());
                        b = mux(known, ictx.get(r.nextPc()), b);
                        n++;
                    }
                    if (n > 0) {
                        if (++resolvingUnits > 1) throw TesseraException.here("recovery at execute: more than one unit has resolve points");
                        exR.set(issue.and(b.ne(g)).named(name + "_ex_redirect_now"));
                        exTarget.set(b);
                        exTag.set(issueTag);
                        final UInt nb = b;
                        for (int e = 0; e < robN; e++) {
                            final Object entry = robGuess.entries.get(e);
                            when(exR.and(exTag.eq(e)), () -> Hw.assign(entry, nb));
                        }
                        report.add("unit " + unit.name() + ": " + n + " resolve point(s) act at execute: a wrong guess redirects fetch, "
                                + "squashes the younger items and rebuilds the RAT from the surviving entries");
                    }
                }
            } else {
                for (var st : unit.issueSteps()) run(ictx, st);
                Bool uValid = flag("unit_" + unit.name() + "_valid");
                zombie = flag("unit_" + unit.name() + "_cancelled");
                UInt uTag = reg("unit_" + unit.name() + "_tag", tagW, 0);
                Bool adv = wireB("unit_" + unit.name() + "_advancing");
                // a cancelled item finishes as a bubble: its steps see valid() false, as a flushed stage in order
                var place = new SimplePlace(unit.name(), uValid.and(zombie.not()), adv, false, true);
                var xctx = ctx(place);
                unitCtx = xctx;
                Map<Key<?>, Object> latched = new LinkedHashMap<>();
                Bool fwdL = Hw.FALSE;
                UInt fwdDataL = null;
                if (loadUnit) {
                    if (rIdx[0] == null) throw TesseraException.here("loads out of order: no issue-cycle unit produces " + lsq.storeData());
                    // every older store executed; the youngest older store to the same word covers the load, or none
                    UInt loadAge = issueTag.subWrap(headNow), lIdx = ictx.get(lsq.index()), lMask = ictx.get(lsq.loadMask());
                    Bool unknown = Hw.FALSE, hit = Hw.FALSE;
                    UInt hMask = UInt.lit(0, rSMask[0].width()), hData = UInt.lit(0, rSData[0].width());
                    for (int a = 0; a < robN - 1; a++) {
                        UInt ea = headNow.addWrap(a);
                        Bool older = a == 0 ? loadAge.ne(0) : UInt.lit(a, tagW).lt(loadAge);
                        Bool st = pickB(ea, rValid).and(pickB(ea, rIsStore)).and(older);
                        Bool known = pickB(ea, rKnown);
                        unknown = unknown.or(st.and(known.not()));
                        Bool h = st.and(known).and(pick(ea, rIdx).eq(lIdx));
                        hit = hit.or(h);
                        hMask = mux(h, pick(ea, rSMask), hMask);
                        hData = mux(h, pick(ea, rSData), hData);
                    }
                    Bool covers = hMask.and(lMask).eq(lMask);
                    // named for measurements: a load picked (operands ready), and whether it forwards from a store
                    any(pickSel).named(name + "_" + unit.name() + "_load_ready");
                    hit.named(name + "_" + unit.name() + "_forward_now");
                    gate.set(unknown.not().and(hit.not().or(covers)).named(name + "_" + unit.name() + "_order_ok_now"));
                    fwdL = (Bool) latch("unit_" + unit.name() + "_forward", hit, issue);
                    fwdDataL = (UInt) latch("unit_" + unit.name() + "_forward_data", hData, issue);
                    report.add("unit " + unit.name() + ": loads issue when every older store has executed; the youngest older store to "
                            + "the same word forwards its data when it covers the load, else the load waits for it to commit");
                }
                final Bool fwd = fwdL;
                final UInt fwdData = fwdDataL;
                xctx.carried = key -> latched.computeIfAbsent(key, kk -> {
                    Object v = ictx.get(kk);
                    // a synchronous read issued in the issue cycle is already the unit's value (used in its first cycle)
                    Object here = registeredValues.contains(v) ? v : latch("unit_" + unit.name() + "_" + kk.name(), v, issue);
                    return loadUnit && kk == lsq.readWord() ? mux(fwd, fwdData, (UInt) here) : here;
                });
                for (var st : s.decodeSteps) run(xctx, st);
                for (var st : unit.steps()) run(xctx, st);
                Bool stalled = Hw.FALSE;
                for (var c : place.stalls) stalled = stalled.or(c);
                adv.set(stalled.not());
                leaving = uValid.and(stalled.not()).named(name + "_" + unit.name() + "_leaving");
                free.set(uValid.not().or(leaving));
                resVal = xctx.get(rp.value());
                resReady = rp.ready() == null ? Hw.TRUE : xctx.get(rp.ready());
                resTag = uTag;
                final Bool lv = leaving;
                when(lv, () -> uValid.set(false));
                when(issue, () -> {
                    uValid.set(true);
                    zombie.set(false);
                    uTag.set(issueTag);
                });
                when(lv, () -> zombie.set(false));
                // a flushed item stays one more cycle, as a bubble (its steps see valid() false), and its result is dropped
                when(flush.and(uValid).and(lv.not()), () -> zombie.set(true));
                registeredUnits.add(new Busy(uValid, lv, uTag, zombie, issue, issueTag));
                report.add("unit " + unit.name() + ": " + (unit.issueSteps().isEmpty() ? "" : String.join(", ", unit.issueSteps())
                        + " in the issue cycle, then ") + String.join(", ", unit.steps()) + " in the unit (held while a step stalls; "
                        + latched.size() + " latched key(s): " + String.join(", ", latched.keySet().stream().map(Key::name).toList())
                        + "); result " + rp.value() + "; a flushed item leaves as a bubble and its result is dropped");
            }
            Bool done = leaving.and(zombie.not());
            unitOuts.add(new UnitOut(unit.registered() ? unitCtx : ictx, done, resTag));
            final UInt rv = resVal, rt = resTag;
            final Bool rr = resReady;
            for (int e = 0; e < robN; e++) {
                final int x = e;
                when(done.and(rt.eq(x)), () -> {
                    rDone[x].set(true);
                    rValRdy[x].set(rr);
                    rVal[x].set(rv);
                });
            }
            busOn[u].set(done.and(resReady));
            busTag[u].set(resTag);
            busVal[u].set(resVal);
        }

        // ------------------------------------------------------------ commit, at the head
        Bool hValid = pickB(head, rValid), hDone = pickB(head, rDone);
        Bool ready = hValid.and(hDone).and(halted.not()).named(name + "_head_ready");
        Set<Key<?>> unitResults = new HashSet<>();
        for (var unit : s.units) {
            var rp = resultOf(unit);
            if (!s.recompute.contains(rp.step())) unitResults.add(rp.value());
        }
        Bool advC = wireB("commit_advancing");
        Bool phase = flag("head_phase");
        Bool proValid = wireB("head_prologue");
        Bool cValid = wireB("commit_valid");
        var commitPlace = new SimplePlace("commit", cValid, advC, false, true);
        var cctx = ctx(commitPlace);
        UInt robValue = pick(head, rVal);
        cctx.carried = key -> {
            if (fetched.containsKey(key)) {
                Field f = robFields.computeIfAbsent(key, kk -> new Field("rob_" + kk.name(), fetched.get(kk), robN,
                        (e, v) -> when(dispatch.and(tail.eq(e)), () -> Hw.assign(v, dctx.get(kk)))));
                return f.at(head);
            }
            for (int p = 0; p < ports; p++) if (m.readPorts.get(p).value() == key) return pick(head, rOp[p]);
            if (unitResults.contains(key)) return robValue;
            // another key a unit produced: carried in the ROB, written when that unit finishes the item
            Object template = null;
            for (var uo : unitOuts) if (uo.ctx().has(key)) template = uo.ctx().get(key);
            if (template == null) return null;
            final Object tpl = template;
            Field f = robFields.computeIfAbsent(key, kk -> new Field("rob_" + kk.name(), tpl, robN, (e, v) -> {
                for (var uo : unitOuts)
                    if (uo.ctx().has(kk)) when(uo.done().and(uo.tag().eq(e)), () -> Hw.assign(v, uo.ctx().get(kk)));
            }));
            return f.at(head);
        };
        for (var st : s.decodeSteps) run(cctx, st);
        for (var st : s.recompute) run(cctx, st);
        Bool needsPro = s.prologue.isEmpty() ? Hw.FALSE : cctx.get(s.prologueWhen);
        proValid.set(ready.and(needsPro).and(phase.not()));
        if (!s.prologue.isEmpty()) {
            cctx.place = new SimplePlace("head", proValid, proValid, true);
            for (var st : s.prologue) run(cctx, st);
            cctx.place = commitPlace;
        }
        cValid.set(ready.and(needsPro.not().or(phase)));
        for (var st : s.commit) {
            if (st.equals(InOrderModel.WRITE_REGS)) continue;
            run(cctx, st);
        }
        Bool cStall = Hw.FALSE;
        for (var c : commitPlace.stalls) cStall = cStall.or(c);
        advC.set(cStall.not());
        Bool fire = cValid.and(cStall.not()).named(name + "_commit");
        UInt cRd = cctx.get(m.rdKey);
        Bool cWrites = cctx.get(m.writesKey);
        UInt cVal = cctx.get(m.rdValueKey);
        when(fire.and(cWrites), () -> m.regFile.write(cRd, cVal));
        busOn[nUnits].set(fire.and(cWrites));
        busTag[nUnits].set(head);
        busVal[nUnits].set(cVal);
        when(proValid, () -> phase.set(true));
        when(fire.or(flush), () -> phase.set(false));
        // resolve points and redirects: the guess the younger items were fetched with, against the item's real next pc
        UInt guess = (UInt) robGuess.at(head);
        UInt next = guess;
        Bool forced = Hw.FALSE;
        int commitResolves = 0;
        for (var r : orderedResolves()) {
            if (s.decodeSteps.contains(r.step())) continue;
            UInt n = cctx.get(r.nextPc());
            Bool known = r.when() == null ? Hw.TRUE : cctx.get(r.when());
            next = mux(known, n, next);
            if (r.always() != null) forced = forced.or(cctx.get(r.always()));
            commitResolves++;
        }
        if (m.redirectKey != null) {
            Bool rd = cctx.get(m.redirectKey);
            next = mux(rd, cctx.get(m.targetKey), next);
            forced = forced.or(rd);
            commitResolves++;
        }
        Bool redirect = fire.and(next.ne(guess).or(forced)).named(name + "_redirect");
        Bool haltNow = m.haltKey == null ? Hw.FALSE : fire.and(cctx.get(m.haltKey));
        flush.set(redirect.or(haltNow));
        flushTarget.set(next);
        when(haltNow, () -> {
            halted.set(true);
            stopFetch.set(true);
        });
        report.add("commit: " + (s.recompute.isEmpty() ? "" : "recomputes " + String.join(", ", s.recompute) + "; ")
                + (s.prologue.isEmpty() ? "" : String.join(", ", s.prologue) + " a cycle earlier when " + s.prologueWhen + "; ")
                + String.join(", ", s.commit) + "; " + commitResolves + " resolve point(s) checked against the guess; a wrong"
                + " guess, a forced redirect or a halt flushes everything younger");
        report.add("carried in the ROB: " + String.join(", ", robFields.keySet().stream().map(Key::name).toList())
                + ", the operands, the value and the next-pc guess; in the RS: "
                + String.join(", ", rsFields.keySet().stream().map(Key::name).toList()) + " and the operands");

        // ------------------------------------------------------------ bookkeeping
        for (int e = 0; e < robN; e++) {
            final int x = e;
            when(fire.and(head.eq(x)), () -> rValid[x].set(false));
        }
        for (int j = 0; j < rsN; j++) {
            final int k = j;
            for (int b = 0; b < buses; b++) {
                final int bb = b;
                for (int p = 0; p < ports; p++) {
                    final int pp = p;
                    when(sValid[k].and(sRdy[pp][k].not()).and(busOn[bb]).and(sOpTag[pp][k].eq(busTag[bb])), () -> {
                        sRdy[pp][k].set(true);
                        sVal[pp][k].set(busVal[bb]);
                    });
                }
            }
        }
        final Bool ren = dispatch.and(dW);
        for (int r = 0; r < nRegs; r++) {
            if (r == 0 && m.zeroRegister) continue;
            final int x = r;
            when(fire.and(cWrites).and(cRd.eq(x)).and(ratTag[x].eq(head)), () -> ratBusy[x].set(false));
            when(ren.and(dRd.eq(x)), () -> {
                ratBusy[x].set(true);
                ratTag[x].set(tail);
            });
        }
        when(dispatch, () -> tail.set(tail.addWrap(1)));
        when(fire, () -> head.set(head.addWrap(1)));
        UInt inc = mux(dispatch, UInt.lit(1, tagW + 1), UInt.lit(0, tagW + 1));
        UInt dec = mux(fire, UInt.lit(1, tagW + 1), UInt.lit(0, tagW + 1));
        count.set(count.addWrap(inc).subWrap(dec));
        if (early) {
            if (resolvingUnits == 0) throw TesseraException.here("recovery at execute: no issue-cycle unit has a resolve point among its steps");
            // squash everything younger than the resolving item; rebuild the RAT from the entries that survive
            UInt bAge = exTag.subWrap(head);
            java.util.function.Function<UInt, Bool> younger = t -> t.subWrap(head).gt(bAge);
            for (int e = 0; e < robN; e++) {
                final int x = e;
                when(exR.and(younger.apply(UInt.lit(e, tagW))), () -> rValid[x].set(false));
            }
            for (int j = 0; j < rsN; j++) {
                final int k = j;
                when(exR.and(younger.apply(sTag[k])), () -> sValid[k].set(false));
            }
            for (var bu : registeredUnits) {
                when(exR.and(bu.valid()).and(bu.leaving().not()).and(younger.apply(bu.tag())), () -> bu.zombie().set(true));
                when(exR.and(bu.issue()).and(younger.apply(bu.issueTag())), () -> bu.zombie().set(true));
            }
            Bool[] vA = new Bool[robN], wA = new Bool[robN];
            UInt[] rdA = new UInt[robN], idxA = new UInt[robN];
            for (int a = 0; a < robN; a++) {
                idxA[a] = head.addWrap(a);
                // the head survives unless it commits now; the others if they are not younger than the resolving item
                Bool keep = a == 0 ? fire.not() : UInt.lit(a, tagW).le(bAge);
                vA[a] = pickB(idxA[a], rValid).and(keep);
                wA[a] = pickB(idxA[a], rW);
                rdA[a] = pick(idxA[a], rRd);
            }
            for (int r = 0; r < nRegs; r++) {
                if (r == 0 && m.zeroRegister) continue;
                Bool busy = Hw.FALSE;
                UInt tag = UInt.lit(0, tagW);
                for (int a = 0; a < robN; a++) {
                    Bool hit = vA[a].and(wA[a]).and(rdA[a].eq(r));
                    busy = busy.or(hit);
                    tag = mux(hit, idxA[a], tag);
                }
                final int x = r;
                final Bool fb = busy;
                final UInt ft = tag;
                when(exR, () -> {
                    ratBusy[x].set(fb);
                    ratTag[x].set(ft);
                });
            }
            UInt one = UInt.lit(1, tagW + 1);
            when(exR, () -> {
                tail.set(exTag.addWrap(1));
                count.set(bAge.zext(tagW + 1).addWrap(one).subWrap(dec));
            });
        }
        when(flush, () -> {
            for (var v : rValid) v.set(false);
            for (var v : sValid) v.set(false);
            for (int r = 0; r < nRegs; r++) if (!(r == 0 && m.zeroRegister)) ratBusy[r].set(false);
            head.set(0);
            tail.set(0);
            count.set(0);
        });
        report.add("window: ROB " + robN + ", RS " + rsN + ", " + nUnits + " unit(s)");
    }

    /** Builds an out-of-order core in the current module. */
    public static OutOfOrderPipeline build(String name, InOrderModel model, OooSchedule schedule) {
        return new OutOfOrderPipeline(name, model, schedule);
    }

    /** True once a halting item has committed. */
    public Bool halted() { return halted; }

    /** What the generator built, in words. */
    public String report() { return String.join("\n", report); }

    // ---------------------------------------------------------------- places

    /** A place a step body runs in. */
    private final class SimplePlace implements StepContext.Place {
        final String where;
        final Bool valid, advancing;
        final boolean registers, canStall;
        final List<Bool> stalls = new ArrayList<>();
        int captures;

        SimplePlace(String where, Bool valid, Bool advancing, boolean registers) {
            this(where, valid, advancing, registers, false);
        }

        SimplePlace(String where, Bool valid, Bool advancing, boolean registers, boolean canStall) {
            this.where = where;
            this.valid = valid;
            this.advancing = advancing;
            this.registers = registers;
            this.canStall = canStall;
        }

        @Override public Bool valid() { return valid; }

        @Override public Bool advancing() { return advancing; }

        @Override public void stall(Bool cond) {
            if (!canStall)
                throw TesseraException.here("a step stalls in " + where + "; out of order, only registered units and commit may stall");
            stalls.add(cond);
        }

        @Override public <T extends Data> T capture(T x) {
            captures++;
            return x;
        }

        @Override public <T extends Data> T registered(T x) {
            if (!registers) throw TesseraException.here("registered(...) in " + where + ": out of order, a synchronous read is only"
                    + " supported in the fetch step and the commit prologue");
            registeredValues.add(x);
            return x;
        }

        @Override public String name() { return where; }
    }

    private StepContext ctx(StepContext.Place place) {
        var c = new StepContext(m, messages);
        c.place = place;
        c.halted = halted;
        return c;
    }

    private void putRaw(StepContext c, Key<?> k, Object v) {
        @SuppressWarnings("unchecked") Key<Object> kk = (Key<Object>) k;
        c.put(kk, v);
    }

    private void run(StepContext c, String step) {
        if (step.equals(InOrderModel.READ_REGS) || step.equals(InOrderModel.WRITE_REGS)) return;
        c.step = step;
        m.steps.stream().filter(x -> x.name().equals(step)).findFirst().orElseThrow().body().accept(c);
    }

    private List<InOrderModel.Resolve> orderedResolves() {
        var names = m.stepNames();
        var out = new ArrayList<>(m.resolves);
        out.sort(java.util.Comparator.comparingInt(r -> names.indexOf(r.step())));
        return out;
    }

    private int defaultUnit() {
        for (int u = 0; u < s.units.size(); u++) if (s.units.get(u).takes() == null) return u;
        throw TesseraException.here("no unit takes the items no other unit takes (a unit with a null key)");
    }

    /** A unit's result: the last result point among its steps, in model order. */
    private InOrderModel.ResultPoint resultOf(OooSchedule.Unit unit) {
        InOrderModel.ResultPoint out = null;
        var names = m.stepNames();
        for (var rp : m.results) {
            if (!unit.steps().contains(rp.step()) && !unit.issueSteps().contains(rp.step())) continue;
            if (out == null || names.indexOf(rp.step()) >= names.indexOf(out.step())) out = rp;
        }
        if (out == null) throw TesseraException.here("unit '" + unit.name() + "' has no result point (resultAfter) among its steps");
        return out;
    }

    // ---------------------------------------------------------------- per-entry state

    /** One copy of a value's registers per entry, written by {@code writer} (entry, registers). */
    private final class Field {
        final List<Object> entries = new ArrayList<>();

        Field(String base, Object template, int n, java.util.function.BiConsumer<Integer, Object> writer) {
            for (int e = 0; e < n; e++) {
                Object regs = regsLike(base + "_" + e, template);
                entries.add(regs);
                writer.accept(e, regs);
            }
        }

        Object at(UInt idx) {
            Object out = entries.getFirst();
            for (int e = 1; e < entries.size(); e++) out = mux(idx.eq(e), entries.get(e), out);
            return out;
        }

        Object oneHot(Bool[] sel) {
            Object out = entries.getFirst();
            for (int e = 1; e < entries.size(); e++) out = mux(sel[e], entries.get(e), out);
            return out;
        }
    }

    /** {@code value} held in registers loaded when {@code en}. */
    private Object latch(String base, Object value, Bool en) {
        Object regs = regsLike(base, value);
        when(en, () -> Hw.assign(regs, value));
        return regs;
    }

    /** Fresh registers shaped like {@code template}: a value, or a record of values. */
    private Object regsLike(String base, Object template) {
        var leaves = new ArrayList<Data>();
        collect(template, leaves);
        var regs = new ArrayList<Data>();
        for (int i = 0; i < leaves.size(); i++) {
            Data leaf = leaves.get(i);
            Signal sg = Internal.newReg(unique(base + (leaves.size() == 1 ? "" : "_" + i)), leaf.width(), leaf.signed(), BigInteger.ZERO);
            HwType<Data> t = Internal.typeOf(leaf);
            regs.add(Internal.wrap(t, new Expr.Ref(sg), sg, -1, null));
        }
        return rebuild(template, regs.iterator());
    }

    private static void collect(Object v, List<Data> out) {
        switch (v) {
            case Data d -> out.add(d);
            case Record r -> {
                for (var rc : r.getClass().getRecordComponents()) {
                    Object c = component(r, rc);
                    if (c instanceof Data || c instanceof Record) collect(c, out);
                }
            }
            default -> throw TesseraException.here(v.getClass().getSimpleName() + " cannot be carried out of order (not a hardware value)");
        }
    }

    private static Object rebuild(Object t, Iterator<Data> it) {
        if (t instanceof Data) return it.next();
        var r = (Record) t;
        var rcs = r.getClass().getRecordComponents();
        Object[] args = new Object[rcs.length];
        Class<?>[] types = new Class<?>[rcs.length];
        for (int i = 0; i < rcs.length; i++) {
            Object c = component(r, rcs[i]);
            args[i] = c instanceof Data || c instanceof Record ? rebuild(c, it) : c;
            types[i] = rcs[i].getType();
        }
        try {
            var ctor = r.getClass().getDeclaredConstructor(types);
            ctor.setAccessible(true);
            return ctor.newInstance(args);
        } catch (ReflectiveOperationException e) {
            throw TesseraException.here("cannot rebuild record " + r.getClass().getName() + ": " + e);
        }
    }

    private static Object component(Record r, java.lang.reflect.RecordComponent rc) {
        try {
            var acc = rc.getAccessor();
            acc.setAccessible(true);
            return acc.invoke(r);
        } catch (ReflectiveOperationException e) {
            throw TesseraException.here("cannot read " + rc.getName() + " of " + r.getClass().getName() + ": " + e);
        }
    }

    // ---------------------------------------------------------------- signals

    private String unique(String n) {
        String base = name + "_" + n.replaceAll("[^A-Za-z0-9_]", "_");
        String out = base;
        for (int i = 1; !regNames.add(out); i++) out = base + "_" + i;
        return out;
    }

    private UInt reg(String n, int w, long reset) {
        Signal sg = Internal.newReg(unique(n), w, false, BigInteger.valueOf(reset));
        return Internal.wrap(UInt.w(w), new Expr.Ref(sg), sg, -1, null);
    }

    private Bool flag(String n) {
        Signal sg = Internal.newReg(unique(n), 1, false, BigInteger.ZERO);
        return Internal.wrap(Bool.TYPE, new Expr.Ref(sg), sg, -1, null);
    }

    private UInt wire(String n, int w) {
        Signal sg = Internal.newWire(unique(n), w, false);
        return Internal.wrap(UInt.w(w), new Expr.Ref(sg), sg, -1, null);
    }

    private Bool wireB(String n) {
        Signal sg = Internal.newWire(unique(n), 1, false);
        return Internal.wrap(Bool.TYPE, new Expr.Ref(sg), sg, -1, null);
    }

    // ---------------------------------------------------------------- selection

    /** One-hot choice of the oldest candidate (age: distance of its ROB tag from the head). */
    private static Bool[] oldest(Bool[] cand, UInt[] tag, UInt head) {
        int n = cand.length;
        UInt[] age = new UInt[n];
        for (int j = 0; j < n; j++) age[j] = tag[j].subWrap(head);
        Bool[] out = new Bool[n];
        for (int j = 0; j < n; j++) {
            Bool best = cand[j];
            for (int k = 0; k < n; k++) {
                if (k == j) continue;
                Bool older = k < j ? age[k].le(age[j]) : age[k].lt(age[j]);
                best = best.and(cand[k].and(older).not());
            }
            out[j] = best;
        }
        return out;
    }

    private static Bool[] first(Bool[] cand) {
        Bool[] out = new Bool[cand.length];
        Bool taken = Hw.FALSE;
        for (int j = 0; j < cand.length; j++) {
            out[j] = cand[j].and(taken.not());
            taken = taken.or(cand[j]);
        }
        return out;
    }

    private static Bool any(Bool[] a) {
        Bool out = Hw.FALSE;
        for (var x : a) out = out.or(x);
        return out;
    }

    private static UInt pickOneHot(Bool[] sel, UInt[] v) {
        UInt out = v[0];
        for (int j = 1; j < v.length; j++) out = mux(sel[j], v[j], out);
        return out;
    }

    private static UInt pick(UInt idx, UInt[] v) {
        UInt out = v[0];
        for (int j = 1; j < v.length; j++) out = mux(idx.eq(j), v[j], out);
        return out;
    }

    private static Bool pickB(UInt idx, Bool[] v) {
        Bool out = v[0];
        for (int j = 1; j < v.length; j++) out = mux(idx.eq(j), v[j], out);
        return out;
    }

    // ---------------------------------------------------------------- checks

    /** Checks the schedule against the model; returns an in-order schedule used only to word error messages. */
    static Schedule validate(InOrderModel m, OooSchedule s) {
        var errs = new ArrayList<String>();
        if (m.pcKey == null) errs.add("the model has no pc(...)");
        if (m.regFile == null) errs.add("the model has no registerFile(...)");
        if (m.rdKey == null) errs.add("the model has no writeRegs(...)");
        if (s.fetchStep == null) errs.add("the schedule has no fetch step");
        if (s.renames == null && m.mayWriteKey == null)
            errs.add("neither the model (mayWrite(...)) nor the schedule (renames(...)) says which decode-time key renames the destination");
        if (s.units.isEmpty()) errs.add("the schedule has no execution unit");
        var names = m.stepNames();
        var placed = new LinkedHashMap<String, String>();
        java.util.function.BiConsumer<String, String> place = (st, where) -> {
            if (!names.contains(st)) errs.add("the schedule places '" + st + "', which is not a step of the model");
            placed.putIfAbsent(st, where);
        };
        if (s.fetchStep != null) place.accept(s.fetchStep, "fetch");
        for (var st : s.decodeSteps) place.accept(st, "decode");
        placed.putIfAbsent(InOrderModel.READ_REGS, "decode");
        for (var u : s.units) {
            for (var st : u.issueSteps()) place.accept(st, "unit");
            for (var st : u.steps()) place.accept(st, "unit");
        }
        for (var st : s.recompute) place.accept(st, "commit");
        for (var st : s.prologue) place.accept(st, "commit");
        for (var st : s.commit) place.accept(st, "commit");
        for (var st : names) if (!placed.containsKey(st)) errs.add("step '" + st + "' is not placed");
        Set<String> atCommit = new HashSet<>(s.recompute);
        atCommit.addAll(s.prologue);
        atCommit.addAll(s.commit);
        for (var step : m.steps) {
            if (!step.sideEffects()) continue;
            if (!s.prologue.contains(step.name()) && !s.commit.contains(step.name()))
                errs.add("side-effect step '" + step.name() + "' must run at commit (out of order, every effect happens at the head)");
        }
        for (var st : s.recompute) {
            var step = m.steps.stream().filter(x -> x.name().equals(st)).findFirst();
            if (step.isPresent() && step.get().sideEffects()) errs.add("recomputed step '" + st + "' has side effects");
        }
        for (var st : s.decodeSteps) {
            var step = m.steps.stream().filter(x -> x.name().equals(st)).findFirst();
            if (step.isPresent() && step.get().sideEffects()) errs.add("decode step '" + st + "' has side effects");
            if (names.indexOf(st) > names.indexOf(InOrderModel.READ_REGS)) errs.add("decode step '" + st + "' comes after readRegs in the model");
        }
        if (!s.commit.contains(InOrderModel.WRITE_REGS)) errs.add("writeRegs must be a commit step");
        for (var r : m.resolves)
            if (!s.decodeSteps.contains(r.step()) && !atCommit.contains(r.step()))
                errs.add("resolve step '" + r.step() + "' runs only in a unit; with recovery at commit it must be a decode step, recomputed or a commit step");
        if (m.haltStep != null && !atCommit.contains(m.haltStep)) errs.add("halt step '" + m.haltStep + "' must be recomputed or a commit step");
        if (m.redirectStep != null && !atCommit.contains(m.redirectStep)) errs.add("redirect step '" + m.redirectStep + "' must be recomputed or a commit step");
        if (s.prologueWhen == null && !s.prologue.isEmpty()) errs.add("the commit prologue has no key");
        if (!errs.isEmpty()) throw TesseraException.here("invalid out-of-order schedule " + s + ":\n  " + String.join("\n  ", errs));
        var msg = Schedule.stages("fetch", "decode", "unit", "commit");
        placed.forEach((st, where) -> msg.place(where, st));
        return msg;
    }
}
