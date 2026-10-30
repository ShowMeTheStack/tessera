package org.eninehq.tessera.riscv;

import java.util.Arrays;
import org.eninehq.tessera.core.Elaborated;
import org.eninehq.tessera.core.Tessera;
import org.eninehq.tessera.sim.BatchSim;
import org.eninehq.tessera.sim.Sim;

/**
 * Command-line simulator benchmark for profiling and A/B runs ; one JVM per call.
 * {@code SimBench <core> <compiled|interpreter|batch> <iterations> <reps>}: elaborates the core once
 * with {@link Benchmarks#loop}, then runs it {@code reps} times to the halt (batch: {@code iterations}
 * cycles of 64 lanes). Each rep creates a new simulator and prints one {@code REP} line with the
 * cycle count, the seconds, the rate and a hash of the final state, so two builds can be checked
 * for identical behaviour as well as compared for speed.
 *
 * <p>{@code <core>} may be {@code NxCID} (e.g. {@code 4xC7}): {@link MultiCore}, N independent copies.
 * Backend {@code parallelT} is the experimental {@code ParallelSim} with T threads (created by reflection,
 * so this file also compiles against a simulator without it).
 */
public final class SimBench {
    private SimBench() {}

    public static void main(String[] args) throws Exception {
        String id = args[0], backend = args[1];
        int iterations = Integer.parseInt(args[2]), reps = Integer.parseInt(args[3]);
        if (backend.equals("barrier")) { // id = thread count; iterations = barriers per rep
            ParallelScope.barrier(Integer.parseInt(id), iterations, reps);
            return;
        }
        if (backend.equals("cones")) {
            var c = id.contains("x")
                    ? Tessera.elaborate(() -> new MultiCore(Integer.parseInt(id.substring(0, id.indexOf('x'))), id.substring(id.indexOf('x') + 1), null)).circuit()
                    : Tessera.elaborate(() -> Cores.get(id).build(4096, null)).circuit();
            ParallelScope.cones(id, org.eninehq.tessera.sim.Netlist.build(c));
            return;
        }
        int[] prog = Benchmarks.loop(backend.equals("batch") ? 1_000_000 : iterations);
        int copies = id.contains("x") ? Integer.parseInt(id.substring(0, id.indexOf('x'))) : 0;
        String coreId = id.contains("x") ? id.substring(id.indexOf('x') + 1) : id;
        Elaborated<? extends org.eninehq.tessera.core.Module> e = copies > 0
                ? Tessera.elaborate(() -> new MultiCore(copies, coreId, prog))
                : Tessera.elaborate(() -> Cores.get(coreId).build(4096, prog));
        var haltedData = e.top() instanceof RvCore r ? r.halted : ((MultiCore) e.top()).halted;
        if (backend.equals("stats")) {
            stats(id, org.eninehq.tessera.sim.Netlist.build(e.circuit()));
            return;
        }
        for (int rep = 0; rep < reps; rep++) {
            long cycles;
            long hash;
            long t0, c0;
            double s, cpu;
            if (backend.equals("batch")) {
                var b = new BatchSim(e.circuit());
                b.pokeAll("reset", 1);
                b.step();
                b.pokeAll("reset", 0);
                perf(rep, "enable");
                c0 = CPU.getCurrentThreadCpuTime();
                t0 = System.nanoTime();
                b.step(iterations);
                s = (System.nanoTime() - t0) / 1e9;
                cpu = (CPU.getCurrentThreadCpuTime() - c0) / 1e9;
                perf(rep, "disable");
                cycles = (long) iterations * BatchSim.LANES;
                hash = 0;
                for (int lane = 0; lane < BatchSim.LANES; lane += 21)
                    for (var m : b.netlist().memsByPath().keySet()) hash = hash * 31 + Arrays.hashCode(b.memory(m, lane));
            } else {
                try (var sim = create(e, backend)) {
                    sim.reset(1);
                    long start = sim.cycle();
                    perf(rep, "enable");
                    c0 = CPU.getCurrentThreadCpuTime();
                    t0 = System.nanoTime();
                    while (sim.peek(haltedData) == 0) sim.step();
                    s = (System.nanoTime() - t0) / 1e9;
                    cpu = (CPU.getCurrentThreadCpuTime() - c0) / 1e9;
                    perf(rep, "disable");
                    cycles = sim.cycle() - start;
                    if (e.top() instanceof RvCore c) hash = Arrays.hashCode(sim.memory(c.regs)) * 31L + Arrays.hashCode(sim.memory(c.dmem));
                    else {
                        hash = 0;
                        for (int k = 0; k < copies; k++)
                            hash = hash * 961 + Arrays.hashCode(sim.memory("core" + k + ".regs")) * 31L + Arrays.hashCode(sim.memory("core" + k + ".dmem"));
                    }
                }
            }
            System.out.printf("REP %s %s %d %d %.6f %.0f %016x cpu=%.6f shared=%d%n", id, backend, rep, cycles, s, cycles / s, hash,
                    cpu, sharedHits());
        }
    }

    /**
     * {@code -Dsimbench.ctl=<fifo>}: a {@code perf stat --control fifo:<fifo> -D -1} control FIFO; counting is enabled
     * exactly around the timed loop of every rep from {@code -Dsimbench.warm} (default 2) on.
     */
    private static final String CTL = System.getProperty("simbench.ctl", "");
    private static final int WARM = Integer.getInteger("simbench.warm", 2);
    private static java.io.OutputStream ctl;

    private static void perf(int rep, String cmd) {
        if (CTL.isEmpty() || rep < WARM) return;
        try {
            if (ctl == null) ctl = new java.io.FileOutputStream(CTL);
            ctl.write((cmd + "\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            ctl.flush();
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static org.eninehq.tessera.sim.Simulator create(Elaborated<?> e, String backend) throws ReflectiveOperationException {
        if (backend.startsWith("parallel")) {
            int t = Integer.parseInt(backend.substring("parallel".length()));
            var cls = Class.forName("org.eninehq.tessera.sim.ParallelSim");
            var net = org.eninehq.tessera.sim.Netlist.build(e.circuit());
            var sim = (org.eninehq.tessera.sim.Simulator) cls.getConstructor(org.eninehq.tessera.sim.Netlist.class, int.class).newInstance(net, t);
            System.err.println("GROUPS " + net.insns().size() + " insns: " + Arrays.toString((int[]) cls.getMethod("groupSizes").invoke(sim)));
            return sim;
        }
        return Sim.create(e, backend.equals("compiled") ? Sim.Backend.COMPILED : Sim.Backend.INTERPRETER);
    }

    private static final java.lang.management.ThreadMXBean CPU = java.lang.management.ManagementFactory.getThreadMXBean();

    /** CompiledSim.sharedHits() where it exists (it does not in older versions); -1 otherwise. */
    private static long sharedHits() {
        try {
            return (long) org.eninehq.tessera.sim.CompiledSim.class.getMethod("sharedHits").invoke(null);
        } catch (ReflectiveOperationException e) {
            return -1;
        }
    }

    /** Netlist statistics: instructions by kind, registers, memory ports. */
    static void stats(String id, org.eninehq.tessera.sim.Netlist n) {
        var kinds = new java.util.TreeMap<String, Integer>();
        int wide = 0;
        for (var in : n.insns()) {
            String k = in.op() == org.eninehq.tessera.sim.Netlist.MEMREAD ? "MEMREAD" : org.eninehq.tessera.ir.OpKind.values()[in.op()].name();
            kinds.merge(k, 1, Integer::sum);
            if (in.wide()) wide++;
        }
        System.out.printf("STATS %s insns=%d wide=%d regs=%d memWrites=%d mems=%d slots=%d checks=%d%n", id, n.insns().size(), wide,
                n.regs().size(), n.memWrites().size(), n.mems().size(), n.slotCount(), n.checks().size());
        System.out.println("KINDS " + id + " " + kinds);
        try { // CompiledSim.planStats is missing in older versions
            System.out.println("PLAN " + id + " " + org.eninehq.tessera.sim.CompiledSim.class.getMethod("planStats", org.eninehq.tessera.sim.Netlist.class)
                    .invoke(null, n));
        } catch (ReflectiveOperationException e) {
            System.out.println("PLAN " + id + " n/a");
        }
    }
}
