package org.eninehq.tessera.pipeline;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.eninehq.tessera.core.TesseraException;

/**
 * The schedule half of a Level-2 pipeline: which stage each step of an {@link InOrderModel}
 * runs in.
 * <pre>{@code
 * Schedule.stages("IF", "ID", "EX", "MEM", "WB")
 *         .place("IF", "fetch").place("ID", "decode", READ_REGS)
 *         .place("EX", "execute").place("MEM", "memory").place("WB", WRITE_REGS, "retire");
 * }</pre>
 */
public final class Schedule {
    private final List<String> stages;
    private final Map<String, String> stepToStage = new LinkedHashMap<>();
    private int lanes = 1;

    private Schedule(List<String> stages) {
        this.stages = List.copyOf(stages);
    }

    public static Schedule stages(String... names) {
        if (names.length == 0) throw TesseraException.here("a schedule needs at least one stage");
        return new Schedule(List.of(names));
    }

    public Schedule place(String stage, String... steps) {
        if (!stages.contains(stage)) throw TesseraException.here("unknown stage '" + stage + "'");
        for (var s : steps) {
            if (stepToStage.containsKey(s)) throw TesseraException.here("step '" + s + "' is placed twice");
            stepToStage.put(s, stage);
        }
        return this;
    }

    /**
     * This schedule with {@code n} issue lanes (1 or 2): a copy, so a shared single-issue schedule is not changed.
     * Two lanes generate a dual-issue pipeline ({@link DualIssuePipeline}).
     */
    public Schedule withLanes(int n) {
        if (n < 1 || n > 2) throw TesseraException.here("a schedule has 1 or 2 lanes, not " + n);
        var copy = new Schedule(stages);
        copy.stepToStage.putAll(stepToStage);
        copy.lanes = n;
        return copy;
    }

    /** Issue lanes: 1, or 2 for a dual-issue pipeline. */
    public int lanes() { return lanes; }

    public List<String> stageNames() { return stages; }

    public String stageOf(String step) { return stepToStage.get(step); }

    int indexOf(String step) {
        String st = stepToStage.get(step);
        return st == null ? -1 : stages.indexOf(st);
    }

    /** Steps of {@code stage}, in model order. */
    List<String> stepsIn(int stage, List<String> modelOrder) {
        var out = new ArrayList<String>();
        for (var s : modelOrder) if (indexOf(s) == stage) out.add(s);
        return out;
    }

    @Override public String toString() {
        var sb = new StringBuilder();
        for (var st : stages) {
            if (!sb.isEmpty()) sb.append(" | ");
            sb.append(st).append(": ");
            sb.append(String.join(", ", stepToStage.entrySet().stream().filter(e -> e.getValue().equals(st)).map(Map.Entry::getKey).toList()));
        }
        return sb.toString();
    }
}
