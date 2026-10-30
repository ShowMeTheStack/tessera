package org.eninehq.tessera.pipeline;

import java.util.ArrayList;
import java.util.List;
import org.eninehq.tessera.core.Bool;
import org.eninehq.tessera.core.UInt;
import org.eninehq.tessera.core.TesseraException;

/**
 * The out-of-order schedule of an {@link InOrderModel}: where each step runs in an {@link OutOfOrderPipeline}.
 * <pre>{@code
 * OooSchedule.create()
 *         .fetch("fetch").decode("decode").renames(WRITES)           // in-order front end; READ_REGS becomes rename
 *         .unit("alu", null, "execute")                                // execution units, out of order
 *         .registeredUnit("md", IS_MULDIV, List.of("execute"), "mdFinish")
 *         .recompute("execute")                                        // pure steps re-run at commit
 *         .commitPrologue(MEM_ACCESS, "memRead")                       // at the head, one cycle before commit
 *         .commit("memory", WRITE_REGS, "retire")                      // at the head, in order: every effect step
 *         .window(16, 8);
 * }</pre>
 * The front end is the fetch step (run once per item; everything it produces is carried with the item) and the
 * decode steps (combinational; re-run wherever their keys are needed). Each item goes to the first unit whose
 * {@code takes} key is true, or to the unit with a null key. A unit runs its steps on the item's carried values and
 * operands; its last result point ({@link InOrderModel#resultAfter}) is its result bus. Commit runs, at the head of
 * the reorder buffer and in program order, the recomputed steps and then the commit steps; every resolve point and
 * every side effect is acted on there.
 */
public final class OooSchedule {
    /** An execution unit: {@code issueSteps} run in the issue cycle (and are latched), {@code steps} in the unit. */
    record Unit(String name, Key<Bool> takes, boolean registered, List<String> issueSteps, List<String> steps) {}

    String fetchStep;
    final List<String> decodeSteps = new ArrayList<>();
    final List<Unit> units = new ArrayList<>();
    final List<String> recompute = new ArrayList<>();
    Key<Bool> prologueWhen;
    final List<String> prologue = new ArrayList<>();
    final List<String> commit = new ArrayList<>();
    Key<Bool> renames;
    int rob = 16, rs = 8;
    boolean recoverAtExecute;
    /** The load/store ordering option (null: memory only at the head). */
    Lsq lsq;

    /** See {@link #loadStoreQueue}. */
    record Lsq(String unit, Key<Bool> isStore, Key<UInt> index, Key<UInt> loadMask, Key<UInt> storeMask, Key<UInt> storeData,
               Key<UInt> readWord) {}

    private OooSchedule() {}

    public static OooSchedule create() {
        return new OooSchedule();
    }

    /** The fetch step: it runs once per item, with the pc; what it produces travels with the item. */
    public OooSchedule fetch(String step) {
        fetchStep = step;
        return this;
    }

    /** Front-end steps after fetch: combinational functions of what fetch produced, re-run where needed. */
    public OooSchedule decode(String... steps) {
        decodeSteps.addAll(List.of(steps));
        return this;
    }

    /** The decode-time key saying the item may write its destination register (it is renamed at dispatch). */
    public OooSchedule renames(Key<Bool> writes) {
        renames = writes;
        return this;
    }

    /**
     * An execution unit that computes in the issue cycle: the oldest ready item is picked, its steps run on it, and
     * the result is on the unit's bus in the same cycle. {@code takes} null: every item no other unit takes.
     */
    public OooSchedule unit(String name, Key<Bool> takes, String... steps) {
        units.add(new Unit(name, takes, false, List.of(), List.of(steps)));
        return this;
    }

    /**
     * An execution unit with a stage register: {@code issueSteps} run in the issue cycle and what they produce is
     * latched with the item; {@code steps} run in the unit, which holds the item while a step stalls
     * ({@link StepContext#stall}) and puts the result on its bus when it leaves.
     */
    public OooSchedule registeredUnit(String name, Key<Bool> takes, List<String> issueSteps, String... steps) {
        units.add(new Unit(name, takes, true, List.copyOf(issueSteps), List.of(steps)));
        return this;
    }

    /** Steps (plain, combinational) re-run at commit from the carried values, instead of carrying what they produce. */
    public OooSchedule recompute(String... steps) {
        recompute.addAll(List.of(steps));
        return this;
    }

    /**
     * Steps run at the head one cycle before the commit steps, for items whose {@code when} key is true (a
     * synchronous memory read: its {@link StepContext#registered} value is there in the commit cycle).
     */
    public OooSchedule commitPrologue(Key<Bool> when, String... steps) {
        prologueWhen = when;
        prologue.addAll(List.of(steps));
        return this;
    }

    /** Steps run at the head of the reorder buffer, in program order, one item per cycle. */
    public OooSchedule commit(String... steps) {
        commit.addAll(List.of(steps));
        return this;
    }

    /**
     * Branch recovery at execute (early recovery, as a generator option): a resolve point among the steps
     * of an issue-cycle unit redirects fetch when the item executes, squashes every younger item (reorder buffer,
     * reservation station, units) and rebuilds the register alias table from the surviving entries. Commit still
     * acts on the other resolve points (traps, interrupts, returns), so they stay precise.
     */
    public OooSchedule recoverAtExecute() {
        recoverAtExecute = true;
        return this;
    }

    /**
     * Loads out of order (a load/store queue, as a generator option): {@code unit} is a registered unit whose
     * issue steps read memory ({@code readWord}, a {@link StepContext#registered} value) and whose steps compute the
     * load's result. A load issues only when every older store in the reorder buffer has executed (its {@code index},
     * {@code storeMask} and {@code storeData} are known); if the youngest older store to the same {@code index} covers
     * the load's {@code loadMask}, the load sees that store's data as {@code readWord}, and if it covers only part of
     * it, the load waits until the store has committed. Stores still write memory at commit. The reorder buffer is the
     * store queue and the reservation station the load queue; loads issue in order among themselves.
     */
    public OooSchedule loadStoreQueue(String unit, Key<Bool> isStore, Key<UInt> index, Key<UInt> loadMask, Key<UInt> storeMask,
                                      Key<UInt> storeData, Key<UInt> readWord) {
        lsq = new Lsq(unit, isStore, index, loadMask, storeMask, storeData, readWord);
        return this;
    }

    /** Reorder-buffer entries (a power of two) and reservation-station entries. */
    public OooSchedule window(int robEntries, int rsEntries) {
        if (robEntries < 2 || Integer.bitCount(robEntries) != 1) throw TesseraException.here("the ROB size must be a power of two >= 2");
        if (rsEntries < 1) throw TesseraException.here("at least one reservation station");
        rob = robEntries;
        rs = rsEntries;
        return this;
    }

    public int robEntries() { return rob; }

    public int rsEntries() { return rs; }

    @Override public String toString() {
        var sb = new StringBuilder("fetch: " + fetchStep + " | decode: " + String.join(", ", decodeSteps) + ", rename");
        for (var u : units)
            sb.append(" | unit ").append(u.name()).append(u.takes() == null ? "" : " (" + u.takes() + ")").append(": ")
                    .append(u.issueSteps().isEmpty() ? "" : String.join(", ", u.issueSteps()) + " / ").append(String.join(", ", u.steps()));
        if (!prologue.isEmpty()) sb.append(" | head (").append(prologueWhen).append("): ").append(String.join(", ", prologue));
        sb.append(" | commit: ").append(String.join(", ", commit));
        if (!recompute.isEmpty()) sb.append(" (recomputing ").append(String.join(", ", recompute)).append(")");
        if (recoverAtExecute) sb.append(" | recovery at execute");
        if (lsq != null) sb.append(" | loads out of order in ").append(lsq.unit());
        return sb.append(" | ROB ").append(rob).append(", RS ").append(rs).toString();
    }
}
