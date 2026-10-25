package org.eninehq.tessera.sim;

import java.util.List;
import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

/**
 * Emits a Java Flight Recorder event for every simulated clock edge, carrying the values of
 * chosen signals, so a hardware run can be inspected in JDK Mission Control next to the
 * JVM's own events (GC, JIT, threads). Enable a recording with {@code -XX:StartFlightRecording}
 * or {@code jcmd <pid> JFR.start}.
 */
public final class JfrRecorder implements Simulator.Listener {

    @Name("tessera.Cycle")
    @Label("Simulated Cycle")
    @Category({"Tessera", "Simulation"})
    @Description("One clock edge of a Tessera simulation, with the values of the watched signals")
    @StackTrace(false)
    static final class CycleEvent extends Event {
        @Label("Cycle") long cycle;
        @Label("Signals") String values;
    }

    private final List<String> paths;

    private JfrRecorder(List<String> paths) {
        this.paths = List.copyOf(paths);
    }

    /** Record {@code paths} on every edge of {@code sim}. */
    public static JfrRecorder attach(Simulator sim, List<String> paths) {
        var r = new JfrRecorder(paths);
        sim.addListener(r);
        return r;
    }

    @Override
    public void beforeEdge(Simulator sim, long cycle) {
        var ev = new CycleEvent();
        if (!ev.isEnabled()) return;
        var sb = new StringBuilder();
        for (var p : paths) {
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(p).append("=0x").append(sim.peekBig(p).toString(16));
        }
        ev.cycle = cycle;
        ev.values = sb.toString();
        ev.commit();
    }
}
