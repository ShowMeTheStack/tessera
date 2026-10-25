package org.eninehq.tessera.test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.function.BooleanSupplier;
import org.eninehq.tessera.core.Data;
import org.eninehq.tessera.sim.Simulator;

/**
 * Concurrent testbench processes, in the style of cocotb coroutines, on virtual threads.
 *
 * <p>The test's own thread is the main process. {@link #fork} starts more processes; every
 * process calls {@link #step()} to wait for the next clock edge. Scheduling is deterministic:
 * in each cycle the main process runs first, then forked processes in the order they were
 * forked, and only then does the clock advance.
 * <pre>{@code
 * try (var tb = new Testbench(sim)) {
 *     tb.fork("driver", () -> { for (int b : bytes) send(tb, b); });
 *     tb.fork("monitor", () -> received.add(receive(tb)));
 *     tb.stepUntil(() -> received.size() == bytes.length, 10_000);
 * }
 * }</pre>
 */
public final class Testbench implements AutoCloseable {

    private static final class Proc {
        final String name;
        final Semaphore resume = new Semaphore(0);
        final Semaphore yielded = new Semaphore(0);
        volatile boolean done;
        volatile Throwable failure;
        Thread thread;

        Proc(String name) {
            this.name = name;
        }
    }

    private static final ThreadLocal<Proc> CURRENT = new ThreadLocal<>();

    private final Simulator sim;
    private final List<Proc> procs = new ArrayList<>();

    public Testbench(Simulator sim) {
        this.sim = sim;
    }

    public Simulator sim() { return sim; }

    /** Start a process. Its first part runs in the current cycle, after the main process. */
    public void fork(String name, Runnable body) {
        var p = new Proc(name);
        p.thread = Thread.ofVirtual().name("tb-" + name).unstarted(() -> {
            CURRENT.set(p);
            try {
                p.resume.acquire();
                body.run();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Throwable t) {
                p.failure = t;
            } finally {
                p.done = true;
                p.yielded.release();
            }
        });
        p.thread.start();
        procs.add(p);
    }

    /** Wait for the next rising clock edge. */
    public void step() {
        Proc me = CURRENT.get();
        if (me != null) {
            me.yielded.release();
            me.resume.acquireUninterruptibly();
            return;
        }
        runProcesses();
        sim.step();
    }

    public void step(int cycles) {
        for (int i = 0; i < cycles; i++) step();
    }

    /** Step until {@code cond} holds, failing after {@code maxCycles}. */
    public void stepUntil(BooleanSupplier cond, int maxCycles) {
        for (int i = 0; !cond.getAsBoolean(); i++) {
            if (i >= maxCycles) throw new AssertionError("condition not reached within " + maxCycles + " cycles");
            step();
        }
    }

    private void runProcesses() {
        for (var p : List.copyOf(procs)) {
            if (p.done) continue;
            p.resume.release();
            p.yielded.acquireUninterruptibly();
            if (p.failure != null) {
                var f = p.failure;
                p.failure = null;
                if (f instanceof Error err) throw err;
                throw new AssertionError("testbench process '" + p.name + "' failed", f);
            }
        }
        procs.removeIf(p -> p.done);
    }

    /** True while any forked process is still running. */
    public boolean busy() {
        return procs.stream().anyMatch(p -> !p.done);
    }

    public void poke(Data input, long value) { sim.poke(input, value); }
    public long peek(Data signal) { return sim.peek(signal); }
    public long cycle() { return sim.cycle(); }

    @Override
    public void close() {
        for (var p : procs) p.thread.interrupt();
        procs.clear();
    }
}
