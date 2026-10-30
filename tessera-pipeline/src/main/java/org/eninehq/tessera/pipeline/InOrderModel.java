package org.eninehq.tessera.pipeline;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.eninehq.tessera.core.Bool;
import org.eninehq.tessera.core.Mem;
import org.eninehq.tessera.core.TesseraException;
import org.eninehq.tessera.core.UInt;

/**
 * The algorithm half of a Level-2 pipeline: an ordered list of steps over named {@link Key}s,
 * with no stages. A {@link Schedule} places the steps into stages; {@link InOrderPipeline}
 * then generates the pipeline, including forwarding, stalls and flushes.
 *
 * <p>Besides user steps there are two built-in steps: {@value #READ_REGS} reads the register
 * file (with forwarding) and {@value #WRITE_REGS} writes it back.
 */
public final class InOrderModel {
    public static final String READ_REGS = "readRegs";
    public static final String WRITE_REGS = "writeRegs";

    record Step(String name, Consumer<StepContext> body, boolean sideEffects, List<Key<?>> produces) {}

    record ReadPort(Key<UInt> index, Key<Bool> uses, Key<UInt> value) {}

    record ResultPoint(String step, Key<UInt> value, Key<Bool> ready, Forward forward) {}

    record Resolve(String step, Key<UInt> nextPc, Key<Bool> when, Key<Bool> always, Key<UInt> plainNext) {}

    final List<Step> steps = new ArrayList<>();
    Key<UInt> pcKey;
    int pcWidth;
    long pcReset, pcIncrement;
    RegisterFile regFile;
    final List<ReadPort> readPorts = new ArrayList<>();
    Key<UInt> rdKey;
    Key<Bool> writesKey;
    Key<UInt> rdValueKey;
    boolean zeroRegister;
    final List<ResultPoint> results = new ArrayList<>();
    String redirectStep, haltStep;
    Key<UInt> predictKey;
    Key<Bool> lane1OkKey, serializesKey, issueWritesKey;
    final List<Resolve> resolves = new ArrayList<>();
    Key<Bool> redirectKey, haltKey;
    /** The decode-time "may write rd" (see {@link #mayWrite}); null: none. */
    Key<Bool> mayWriteKey;
    Key<UInt> targetKey;

    private InOrderModel() {}

    public static InOrderModel create() {
        return new InOrderModel();
    }

    /** The program counter: available to the first step as {@code key}; advances by {@code increment}. */
    public InOrderModel pc(Key<UInt> key, int width, long reset, long increment) {
        pcKey = key;
        pcWidth = width;
        pcReset = reset;
        pcIncrement = increment;
        return this;
    }

    /** A step: reads keys produced by earlier steps and puts new ones. */
    public InOrderModel step(String name, Consumer<StepContext> body) {
        return add(new Step(name, body, false, List.of()));
    }

    /**
     * A step with side effects (memory writes, I/O). It must not be scheduled before the stage
     * where redirects resolve, or a mispredicted item could commit; gate effects with
     * {@link StepContext#fire()}.
     */
    public InOrderModel effectStep(String name, Consumer<StepContext> body) {
        return add(new Step(name, body, true, List.of()));
    }

    private InOrderModel add(Step s) {
        for (var o : steps)
            if (o.name().equals(s.name())) throw TesseraException.here("duplicate step '" + s.name() + "'");
        steps.add(s);
        return this;
    }

    /** The register file. {@code zeroRegister}: index 0 reads as zero and never causes hazards. */
    public InOrderModel registerFile(Mem<UInt> mem, boolean zeroRegister) {
        return registerFile(RegisterFile.of(mem), zeroRegister);
    }

    /**
     * The register file as any implementation (for two lanes: a live-value table instead of a memory in registers).
     * The generator only reads, writes and asks for its width.
     */
    public InOrderModel registerFile(RegisterFile rf, boolean zeroRegister) {
        regFile = rf;
        this.zeroRegister = zeroRegister;
        return this;
    }

    /** A register-file read port, served by the built-in {@value #READ_REGS} step (with forwarding). */
    public InOrderModel readPort(Key<UInt> index, Key<Bool> uses, Key<UInt> value) {
        readPorts.add(new ReadPort(index, uses, value));
        return this;
    }

    /** The built-in {@value #READ_REGS} step goes here in step order. */
    public InOrderModel readRegs() {
        return add(new Step(READ_REGS, null, false, List.of()));
    }

    /** The built-in {@value #WRITE_REGS} step: writes {@code value} to register {@code rd} when {@code writes}. */
    public InOrderModel writeRegs(Key<UInt> rd, Key<Bool> writes, Key<UInt> value) {
        rdKey = rd;
        writesKey = writes;
        rdValueKey = value;
        return add(new Step(WRITE_REGS, null, true, List.of()));
    }

    /**
     * After {@code step}, the destination value is {@code value}, valid for forwarding when
     * {@code ready} (null: always). Later result points take precedence.
     */
    public InOrderModel resultAfter(String step, Key<UInt> value, Key<Bool> ready) {
        return resultAfter(step, value, ready, Forward.SAME_STAGE);
    }

    /** As {@link #resultAfter(String, Key, Key)}, forwarded from where {@code forward} says. */
    public InOrderModel resultAfter(String step, Key<UInt> value, Key<Bool> ready, Forward forward) {
        results.add(new ResultPoint(step, value, ready, forward));
        return this;
    }

    /** {@code step} decides whether to redirect fetch to {@code target}; younger items are then cancelled. */
    public InOrderModel redirect(String step, Key<Bool> redirect, Key<UInt> target) {
        redirectStep = step;
        redirectKey = redirect;
        targetKey = target;
        return this;
    }

    /**
     * Fetch's guess of the next pc, put by a step in the first stage (a branch predictor); without it fetch
     * guesses pc+increment. A model that predicts says where the guess is checked with {@link #resolve}.
     */
    public InOrderModel predict(Key<UInt> predictedNext) {
        predictKey = predictedNext;
        return this;
    }

    /**
     * After {@code step}, the item's next pc is known to be {@code nextPc} (when {@code when}; null: always). If it
     * differs from what was guessed so far, fetch is redirected there and younger items are cancelled. Several
     * steps may resolve, in different stages (decode for jumps, execute for branches): each checks the guess as
     * corrected by the earlier ones, and when two redirect in one cycle the older item's wins. A step resolves in
     * the cycle its item leaves the stage (not while it is held). Replaces {@link #redirect}.
     */
    public InOrderModel resolve(String step, Key<UInt> nextPc, Key<Bool> when) {
        return resolve(step, nextPc, when, null);
    }

    /**
     * As {@link #resolve(String, Key, Key)}, and when {@code always} (null: never) fetch is redirected to
     * {@code nextPc} even if the guess was right: for events after which instructions must be fetched again
     * (a trap or an interrupt, where what fetch captured, such as the interrupt mark, has changed).
     */
    public InOrderModel resolve(String step, Key<UInt> nextPc, Key<Bool> when, Key<Bool> always) {
        return resolve(step, nextPc, when, always, null);
    }

    /**
     * As {@link #resolve(String, Key, Key, Key)}, with {@code plainNext}: the next pc when {@code always} is false (it
     * then equals {@code nextPc}). The guess is compared with it, so the comparison need not wait for whatever decides
     * {@code always} (a trap). Used by the dual-issue generator; the single-issue one compares {@code nextPc}.
     */
    public InOrderModel resolve(String step, Key<UInt> nextPc, Key<Bool> when, Key<Bool> always, Key<UInt> plainNext) {
        resolves.add(new Resolve(step, nextPc, when, always, plainNext));
        return this;
    }

    /**
     * Issue rules for a two-lane schedule ({@link Schedule#withLanes}): {@code lane1Ok} says an instruction may go
     * in the second lane, {@code serializes} that nothing may issue beside it, {@code writes} that it may write its
     * destination register (as decoded: the pair check runs before {@link #writeRegs}'s condition is known). All are
     * produced by a step in the register-read stage. Ignored by single-issue pipelines.
     */
    public InOrderModel lanes(Key<Bool> lane1Ok, Key<Bool> serializes, Key<Bool> writes) {
        lane1OkKey = lane1Ok;
        serializesKey = serializes;
        issueWritesKey = writes;
        return this;
    }

    /** A key whose resolving-item value the repair reads ({@link #speculative}). */
    record Speculative(Key<? extends org.eninehq.tessera.core.Data> key, int width) {}

    final List<Speculative> speculative = new ArrayList<>();
    Consumer<Redirect> repair;

    /**
     * Speculative state: {@code key} ({@code width} bits, a {@link UInt} or, at width 1, a {@link Bool}) is carried with
     * each item, and on a redirect the {@link #onRedirect repair} gets the resolving item's value of it. Typically a
     * first-stage step puts a snapshot of a predictor register as it was before the item's fetch updated it (a global
     * history, a return-stack pointer); keys produced later (the item's decoded kind) may be declared too, as long as
     * every resolve point has them. In a dual-issue pipeline, a first-stage step gates its update of the state with
     * {@link StepContext#fire()}, which in lane 1 is true only when that slot is kept (not dropped behind a guessed
     * jump in slot 0, nor fetched again after a split).
     */
    public InOrderModel speculative(Key<? extends org.eninehq.tessera.core.Data> key, int width) {
        if (width < 1) throw TesseraException.here("speculative " + key + ": width must be at least 1");
        for (var s : speculative) if (s.key() == key) throw TesseraException.here("speculative " + key + " declared twice");
        speculative.add(new Speculative(key, width));
        return this;
    }

    /**
     * The repair of speculative state, run once by the generator after all steps: it reads the redirect of the cycle
     * ({@link Redirect}) and writes the model's registers. The generator's one hook for predictor state; what the
     * state is and how it is repaired is model code.
     */
    public InOrderModel onRedirect(Consumer<Redirect> repair) {
        if (this.repair != null) throw TesseraException.here("onRedirect(...) given twice");
        this.repair = repair;
        return this;
    }

    /**
     * {@code writes}, produced at decode: the item may write its destination register. The in-order generator uses it
     * for hazards in stages where {@link #writeRegs}'s condition is not produced yet (it is known only where the side
     * effects are decided); the out-of-order generator renames with it.
     */
    public InOrderModel mayWrite(Key<Bool> writes) {
        mayWriteKey = writes;
        return this;
    }

    /** A pair the read stage does not issue together: lane 0's {@code older} and lane 1's {@code younger} both hold. */
    record PairConflict(Key<Bool> older, Key<Bool> younger) {}

    final List<PairConflict> pairConflicts = new ArrayList<>();

    /**
     * Issue rule for a two-lane schedule: the pair is split (the second instruction waits for the next cycle, in lane 0)
     * when the first has {@code older} and the second has {@code younger}, both produced by the register-read stage.
     * For units the lanes share in a way the generator does not know about, such as one memory written by both.
     */
    public InOrderModel splitPairWhen(Key<Bool> older, Key<Bool> younger) {
        pairConflicts.add(new PairConflict(older, younger));
        return this;
    }

    /** {@code step} decides whether the item halts the pipeline (fetch stops; it retires last). */
    public InOrderModel halt(String step, Key<Bool> halt) {
        haltStep = step;
        haltKey = halt;
        return this;
    }

    List<String> stepNames() {
        return steps.stream().map(Step::name).toList();
    }

    String producerOf(Key<?> key) {
        if (key == pcKey) return "(pc)";
        for (var p : readPorts) if (p.value() == key) return READ_REGS;
        return null;
    }
}
