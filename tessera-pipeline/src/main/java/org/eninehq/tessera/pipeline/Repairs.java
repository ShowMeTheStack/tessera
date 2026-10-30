package org.eninehq.tessera.pipeline;

import static org.eninehq.tessera.core.Hw.mux;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.eninehq.tessera.core.Bool;
import org.eninehq.tessera.core.Data;
import org.eninehq.tessera.core.Hw;
import org.eninehq.tessera.core.Internal;
import org.eninehq.tessera.core.TesseraException;
import org.eninehq.tessera.core.UInt;
import org.eninehq.tessera.ir.Expr;
import org.eninehq.tessera.ir.Signal;

/**
 * The generator side of {@link InOrderModel#speculative} and {@link InOrderModel#onRedirect}, shared by both pipeline
 * generators: at every resolve point (and lane) it copies the redirect decision, the item's pc and resolved next pc and
 * the declared keys into plain wires; at the end it selects the winning redirect's values with the same priority as
 * fetch's pc mux and runs the model's repair. Nothing is generated for a model without a repair.
 */
final class Repairs {
    private record Point(int priority, Bool wrong, UInt pc, UInt next, UInt seqPc, Bool seq, Map<Key<?>, Data> values) {}

    private final InOrderModel m;
    private final Pipeline p;
    private final String name;
    private final List<Point> points = new ArrayList<>();

    private Repairs(InOrderModel m, Pipeline p, String name) {
        this.m = m;
        this.p = p;
        this.name = name;
    }

    /** Null when the model has no repair (then nothing at all is generated). */
    static Repairs of(InOrderModel m, Pipeline p, String name) {
        return m.repair == null ? null : new Repairs(m, p, name);
    }

    static void validate(InOrderModel m, List<String> errs) {
        if (!m.speculative.isEmpty() && m.repair == null) errs.add("the model declares speculative state but no onRedirect(...) repair");
        if (m.repair != null && m.resolves.isEmpty()) errs.add("onRedirect(...) needs resolve(...): the repair runs when a resolve point redirects");
    }

    /**
     * Resolve point {@code ri} (in stage order) of {@code lane} decided {@code wrong}, going to {@code next}. Higher
     * {@code priority} wins: callers number points as fetch's pc mux orders them.
     */
    void record(StepContext cx, int ri, int lane, int priority, Bool wrong, UInt next) {
        String tag = name + "_repair" + ri + "_l" + lane;
        var values = new LinkedHashMap<Key<?>, Data>();
        for (var s : m.speculative) {
            Data v;
            try {
                v = (Data) cx.get(s.key());
            } catch (TesseraException e) {
                throw TesseraException.here("speculative " + s.key() + " is not available where step '" + cx.step
                        + "' resolves: " + e.getMessage());
            }
            if (v.width() != s.width())
                throw TesseraException.here("speculative " + s.key() + " is declared " + s.width() + " bits wide but is " + v.width());
            values.put(s.key(), live(v, tag + "_" + s.key().name().replaceAll("\\W", "_")));
        }
        // computed here, per point, beside the redirect decision: not after the selection, on the repair's critical path
        UInt pc = cx.get(m.pcKey), seqPc = pc.addWrap(m.pcIncrement);
        points.add(new Point(priority, live(wrong, tag + "_taken"), live(pc, tag + "_pc"), live(next, tag + "_next"),
                live(seqPc, tag + "_seqpc"), live(next.eq(seqPc), tag + "_seq"), values));
    }

    /** Run the model's repair on the winning redirect of the cycle. */
    void finish() {
        if (points.isEmpty()) return;
        var order = new ArrayList<>(points);
        order.sort(Comparator.comparingInt(Point::priority));
        Bool taken = Hw.FALSE;
        Point first = order.getFirst();
        UInt pc = first.pc(), next = first.next(), seqPc = first.seqPc();
        Bool seq = first.seq();
        Map<Key<?>, Data> vals = new IdentityHashMap<>(first.values());
        for (var pt : order.subList(1, order.size())) {
            pc = mux(pt.wrong(), pt.pc(), pc);
            next = mux(pt.wrong(), pt.next(), next);
            seqPc = mux(pt.wrong(), pt.seqPc(), seqPc);
            seq = mux(pt.wrong(), pt.seq(), seq);
            for (var e : pt.values().entrySet()) vals.put(e.getKey(), mux(pt.wrong(), e.getValue(), vals.get(e.getKey())));
        }
        for (var pt : order) taken = taken.or(pt.wrong());
        m.repair.accept(new Redirect(taken, pc, next, seqPc, seq, vals));
    }

    /** {@code v} as seen in the current stage, as a wire that belongs to no stage. */
    private <T extends Data> T live(T v, String wireName) {
        Signal w = Internal.newWire(wireName, v.width(), v.signed());
        Internal.assignRaw(w, p.adapt(v, Internal.resolveRaw(v)));
        return Internal.wrap(Internal.typeOf(v), new Expr.Ref(w), w, -1, null);
    }
}
