package org.eninehq.tessera.pipeline;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Supplier;
import org.eninehq.tessera.core.Bool;
import org.eninehq.tessera.core.Data;
import org.eninehq.tessera.core.HwType;
import org.eninehq.tessera.core.Internal;
import org.eninehq.tessera.core.Module;
import org.eninehq.tessera.core.StageHook;
import org.eninehq.tessera.core.TesseraException;
import org.eninehq.tessera.ir.Expr;
import org.eninehq.tessera.ir.ModuleDef;
import org.eninehq.tessera.ir.Ops;
import org.eninehq.tessera.ir.Signal;

/**
 * A pipeline written as straight-line code with stage boundaries, like Spade's {@code reg;}.
 *
 * <pre>{@code
 * Pipeline p = Pipeline.begin("mac");
 * UInt prod = a.mul(b);          // stage 0
 * p.stage();
 * UInt sum = prod.add(c);        // stage 1: prod is delayed automatically; c too
 * p.stage();
 * out.set(sum);                  // stage 2
 * p.end();
 * }</pre>
 *
 * <p>Every value created between {@link #begin} and {@link #end} is tagged with its stage.
 * Using a value in a later stage inserts the pipeline registers that carry it there; using
 * it in an earlier stage is an error (use {@link #late} for intentional backward paths).
 * Each stage has a valid bit; {@link #stall} holds a stage and everything before it while
 * the next stage receives a bubble, and {@link #flush} cancels the younger stages.
 */
public final class Pipeline implements StageHook {

    private static final Map<ModuleDef, List<Pipeline>> REGISTRY = new WeakHashMap<>();

    private final String name;
    private final ModuleDef module;
    private final Expr validIn;
    private final StageHook previous;
    private final List<String> stageNames = new ArrayList<>();
    private final List<Signal> hold = new ArrayList<>();
    private final List<Signal> valid = new ArrayList<>();
    private final List<List<Expr>> stalls = new ArrayList<>();
    private final List<List<Expr>> flushes = new ArrayList<>();
    private final Map<Object, List<Expr>> delays = new IdentityHashMap<>();
    private final Map<Signal, Integer> subOutputs = new IdentityHashMap<>();
    private final List<Late<?>> lates = new ArrayList<>();
    private int cur;
    private boolean ended;
    private boolean hasSubPipelines;
    private boolean stallable;
    private Signal holdIn;
    /** Stallable sub-pipelines: their hold input is driven from this pipeline's hold wires at end(). */
    private record SubInst(org.eninehq.tessera.ir.Instance inst, Pipeline child, int start, int latency) {}
    private final List<SubInst> subs = new ArrayList<>();
    private boolean fixedSubs;

    private Pipeline(String name, Bool validIn) {
        this.name = name;
        this.module = Internal.currentModule();
        this.validIn = validIn == null ? Ops.TRUE : Internal.resolveRaw(validIn);
        this.previous = Internal.setHook(this);
        stageNames.add("s0");
        hold.add(Internal.newWire(name + "_hold_s0", 1, false));
        valid.add(null);
        stalls.add(new ArrayList<>());
        flushes.add(new ArrayList<>());
        synchronized (REGISTRY) {
            REGISTRY.computeIfAbsent(module, k -> new ArrayList<>()).add(this);
        }
    }

    /** Start a pipeline in the current module; stage 0 is always valid. */
    public static Pipeline begin(String name) {
        return new Pipeline(name, null);
    }

    /** Start a pipeline whose stage 0 is valid when {@code validIn} is high. */
    public static Pipeline begin(String name, Bool validIn) {
        return new Pipeline(name, validIn);
    }

    /**
     * Start a pipeline that a parent pipeline can stall when it is used as a sub-pipeline
     * ({@link #inst}): it gets an input port {@code <name>_hold_in} with one hold bit per stage.
     */
    public static Pipeline beginStallable(String name) {
        var p = new Pipeline(name, null);
        p.stallable = true;
        return p;
    }

    public static Pipeline beginStallable(String name, Bool validIn) {
        var p = new Pipeline(name, validIn);
        p.stallable = true;
        return p;
    }

    public String name() { return name; }

    /** The current stage index (0-based). */
    public int current() { return cur; }

    /** Number of stage boundaries so far: the latency from stage 0 to the current stage. */
    public int depth() { return cur; }

    private void checkOpen() {
        if (ended) throw TesseraException.here("pipeline '" + name + "' has already ended");
        if (Internal.hook() != this) throw TesseraException.here("pipeline '" + name + "' is not the active pipeline");
    }

    /** Advance to the next stage. */
    public void stage() {
        stage("s" + (cur + 1));
    }

    /** Advance to the next stage and name it (names appear in register names). */
    public void stage(String stageName) {
        checkOpen();
        if (Internal.inWhen()) throw TesseraException.here("stage() cannot be called inside a when block");
        cur++;
        stageNames.add(stageName);
        hold.add(Internal.newWire(name + "_hold_" + stageName, 1, false));
        valid.add(Internal.newReg(name + "_valid_" + stageName, 1, false, BigInteger.ZERO));
        stalls.add(new ArrayList<>());
        flushes.add(new ArrayList<>());
    }

    /** Index of a named stage. */
    public int stageIndex(String stageName) {
        int i = stageNames.indexOf(stageName);
        if (i < 0) throw TesseraException.here("pipeline '" + name + "' has no stage '" + stageName + "'");
        return i;
    }

    private Expr validExpr(int stage) {
        return stage == 0 ? validIn : new Expr.Ref(valid.get(stage));
    }

    /**
     * Treat {@code x} as a value of the current stage. Values created outside the pipeline
     * (module inputs, registers) are otherwise read live in whatever stage uses them; use this
     * for inputs that enter the pipeline here and must travel with their item.
     */
    @SuppressWarnings("unchecked")
    public <T extends Data> T input(T x) {
        checkOpen();
        if (x.stage() >= 0) throw TesseraException.here("value already belongs to stage " + x.stage());
        return (T) x.withStage(cur, this);
    }

    /**
     * {@code x} is already registered by the hardware that produced it (a synchronous memory
     * read enabled while the current stage advances): treat it as a value of the next stage.
     * Using it in the current stage is then an error, as it should be.
     */
    @SuppressWarnings("unchecked")
    public <T extends Data> T registered(T x) {
        checkOpen();
        if (x.stage() >= 0 && (x.stage() != cur || x.stageDomain() != this))
            throw TesseraException.here("registered(...) needs a value of the current stage, not of stage " + x.stage());
        return (T) x.withStage(cur + 1, this);
    }

    /** True when the current stage holds a real (non-bubble) item. */
    public Bool valid() {
        checkOpen();
        Signal s = cur == 0 ? null : valid.get(cur);
        return Internal.wrap(Bool.TYPE, validExpr(cur), s, cur, this);
    }

    /** True when {@code stage} is held this cycle (it or a later stage stalled). Usable anywhere. */
    public Bool held(int stage) {
        return Internal.wrap(Bool.TYPE, new Expr.Ref(hold.get(stage)), hold.get(stage), -1, null);
    }

    /** True when the current stage advances this cycle. */
    public Bool advancing() {
        return held(cur).not();
    }

    private Expr here(Data d) {
        return adapt(d, Internal.resolveRaw(d));
    }

    /** The current stage cannot advance this cycle: hold it and all earlier stages; the next stage gets a bubble. */
    public void stall(Bool cond) {
        checkOpen();
        stalls.get(cur).add(here(cond));
    }

    /** Cancel every item younger than the current stage (all earlier stages) when {@code cond}. */
    public void flush(Bool cond) {
        checkOpen();
        flushes.get(cur).add(here(cond));
    }

    /** A placeholder for a value produced later in the code; see {@link Late}. */
    public <T extends Data> Late<T> late(HwType<T> type) {
        checkOpen();
        Signal w = Internal.newWire(name + "_late" + lates.size(), type.width(), type.signed());
        var l = new Late<>(Internal.wrap(type, new Expr.Ref(w), w, cur, this), w, this);
        lates.add(l);
        return l;
    }

    /**
     * Instantiate a fixed-latency sub-pipeline. Its inputs belong to the current stage and its
     * outputs to stage {@code current() + latency}; the latency must match the child's real depth.
     */
    public <M extends Module> M inst(int latency, Supplier<M> ctor) {
        checkOpen();
        var loc = Internal.here();
        M child = Internal.instantiate(null, ctor);
        var ann = child.getClass().getAnnotation(Latency.class);
        List<Pipeline> ps;
        synchronized (REGISTRY) {
            ps = REGISTRY.getOrDefault(child.def(), List.of());
        }
        int real = ann != null ? ann.value() : (ps.size() == 1 ? ps.getFirst().depth() : -1);
        if (ps.size() == 1 && ann != null && ann.value() != ps.getFirst().depth())
            throw new TesseraException(loc, child.def().name() + " is declared @Latency(" + ann.value()
                    + ") but its pipeline has depth " + ps.getFirst().depth());
        if (real < 0)
            throw new TesseraException(loc, child.def().name() + " has no single pipeline and no @Latency; cannot check its latency");
        if (real != latency)
            throw new TesseraException(loc, child.def().name() + " has latency " + real + ", not " + latency);
        for (var port : child.def().ports())
            if (port.kind() == Signal.Kind.OUTPUT) subOutputs.put(port, cur + latency);
        hasSubPipelines = true;
        if (ps.size() == 1 && ps.getFirst().stallable) subs.add(new SubInst(child.def().instantiatedAs(), ps.getFirst(), cur, latency));
        else fixedSubs = true;
        return child;
    }

    // ------------------------------------------------------------ StageHook

    @Override public int currentStage() { return cur; }

    @Override public Object domain() { return this; }

    @Override
    public Expr adapt(Data d, Expr e) {
        Integer sub = d.signal() != null ? subOutputs.get(d.signal()) : null;
        int s;
        if (sub != null) s = sub;
        else if (d.stage() < 0) return e;
        else if (d.stageDomain() != this)
            throw TesseraException.here("value from another pipeline used in pipeline '" + name + "'");
        else s = d.stage();
        if (s == cur) return e;
        if (s > cur)
            throw TesseraException.here((sub != null ? "result of a sub-pipeline, available in stage " : "value from stage ")
                    + stageName(s) + ", used in earlier stage " + stageName(cur)
                    + (sub != null ? "; wait for it with stage()" : "; use late(...) for values from later stages"));
        Object key = d.signal() != null ? d.signal() : e;
        String base = d.signal() != null ? d.signal().name() : "p";
        return delayed(key, base, e, s, cur);
    }

    /**
     * The register that already carries {@code d} into {@code stage}, or null when there is none (or when
     * {@code d} is of that stage, its own signal). Only looks: unlike using the value, it never adds a register.
     */
    public Signal existingRegister(Data d, int stage) {
        if (d.stage() == stage) return d.signal();
        Object key = d.signal() != null ? d.signal() : Internal.resolveRaw(d);
        var chain = delays.get(key);
        if (chain == null || stage >= chain.size() || chain.get(stage) == null) return null;
        return chain.get(stage) instanceof Expr.Ref r ? r.signal() : null;
    }

    private String stageName(int s) {
        return s < stageNames.size() ? stageNames.get(s) + " (" + s + ")" : "s" + s;
    }

    /** {@code e} (a stage-{@code from} value) as seen in stage {@code to}, through pipeline registers. */
    private Expr delayed(Object key, String base, Expr e, int from, int to) {
        var chain = delays.computeIfAbsent(key, k -> new ArrayList<>());
        while (chain.size() <= to) chain.add(null);
        chain.set(from, e);
        for (int k = from + 1; k <= to; k++) {
            if (chain.get(k) != null) continue;
            Signal r = Internal.newReg(base + "_" + stageNames.get(k), e.width(), e.signed(), null);
            Expr h = new Expr.Ref(hold.get(k));
            Internal.assignRaw(r, Ops.mux(h, new Expr.Ref(r), chain.get(k - 1)));
            chain.set(k, new Expr.Ref(r));
        }
        return chain.get(to);
    }

    // ------------------------------------------------------------ end

    /** Finish the pipeline: generate stall, bubble and flush logic and leave pipeline mode. */
    public void end() {
        checkOpen();
        for (var l : lates)
            if (!l.assigned()) throw new TesseraException(l.created, "late value '" + l.wire().name() + "' created here was never set");
        int depth = cur;
        boolean anyStall = stallable || stalls.stream().anyMatch(x -> !x.isEmpty());
        if (anyStall && fixedSubs)
            throw TesseraException.here("pipeline '" + name + "' stalls but contains fixed-latency sub-pipelines that "
                    + "cannot be stalled; write them with Pipeline.beginStallable(...)");
        if (stallable) holdIn = Internal.newInput(name + "_hold_in", depth + 1);
        // hold_k = OR of stalls in stages >= k (and of the parent's hold for this stage)
        Expr acc = Ops.FALSE;
        Expr[] holdE = new Expr[depth + 1];
        for (int k = depth; k >= 0; k--) {
            for (var c : stalls.get(k)) acc = Ops.or(acc, c);
            holdE[k] = holdIn == null ? acc
                    : Ops.or(acc, new Expr.Op(org.eninehq.tessera.ir.OpKind.SLICE, List.of(new Expr.Ref(holdIn)), 1, false, k, k));
        }
        for (int k = 0; k <= depth; k++) Internal.assignRaw(hold.get(k), holdE[k]);
        for (var sub : subs) {
            if (sub.start() + sub.latency() > depth)
                throw TesseraException.here("pipeline '" + name + "' ends before the results of sub-pipeline "
                        + sub.inst().name() + " are available");
            var bits = new ArrayList<Expr>();
            for (int k = sub.latency(); k >= 0; k--) bits.add(new Expr.Ref(hold.get(sub.start() + k)));
            Expr v = bits.size() == 1 ? bits.getFirst() : new Expr.Op(org.eninehq.tessera.ir.OpKind.CAT, bits, bits.size(), false);
            Internal.connectChildInput(sub.inst(), sub.child().holdIn, v);
        }
        // valid bits
        for (int j = 1; j <= depth; j++) {
            Expr killIn = Ops.FALSE, killHeld = Ops.FALSE;
            for (int s = j; s <= depth; s++)
                for (var f : flushes.get(s)) {
                    killIn = Ops.or(killIn, f);
                    if (s > j) killHeld = Ops.or(killHeld, f);
                }
            Expr hj = new Expr.Ref(hold.get(j));
            Expr hPrev = new Expr.Ref(hold.get(j - 1));
            Expr self = new Expr.Ref(valid.get(j));
            Expr keep = Ops.and(self, Ops.not(killHeld));
            Expr take = Ops.and(Ops.and(Ops.not(hPrev), validExpr(j - 1)), Ops.not(killIn));
            Internal.assignRaw(valid.get(j), Ops.mux(hj, keep, take));
        }
        ended = true;
        Internal.setHook(previous);
    }
}
