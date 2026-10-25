package org.eninehq.tessera.riscv;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.eninehq.tessera.core.Elaborated;
import org.eninehq.tessera.core.Tessera;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Embench-IoT 1.0 and CoreMark (built by scripts/workloads.sh) on every core in
 * {@link Cores}, each retired instruction checked against the ISS. The benchmark region is
 * the cycles between the marker instructions {@code addi x0,x0,1} and {@code addi x0,x0,2}.
 * Opt-in (slow): {@code ./gradlew :tessera-riscv:test --tests '*WorkloadTest' -Pworkloads[=threads]};
 * writes build/results/workloads.csv.
 */
@Execution(ExecutionMode.CONCURRENT)
class WorkloadTest {
    /** {@code -PworkloadSet=rv32i}: the same programs without M (for comparison with RV32I-only cores). */
    static final String SET = System.getProperty("workloadSet", "");
    static final Path DIR = Path.of(System.getProperty("user.dir")).getParent().resolve("build/workloads" + (SET.isBlank() ? "" : "-" + SET));
    static final int MEM = 16384;
    static final int START = 0x00100013, STOP = 0x00200013;
    static final Map<String, String> ROWS = new TreeMap<>();
    static final Map<String, Elaborated<? extends RvCore>> BUILT = new java.util.concurrent.ConcurrentHashMap<>();

    static List<String> workloads() {
        try (var s = Files.list(DIR)) {
            var only = List.of(System.getProperty("workloadNames", "").split(",")).stream().filter(x -> !x.isBlank()).toList();
            return s.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".bin")).map(n -> n.substring(0, n.length() - 4))
                    .filter(n -> only.isEmpty() || only.contains(n)).sorted().toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    static List<Arguments> cases() {
        var out = new ArrayList<Arguments>();
        for (var c : CoreMatrixTest.ids()) for (var w : workloads()) out.add(Arguments.of(c, w));
        return out;
    }

    static int[] image(String name) throws IOException {
        return Programs.words(Files.readAllBytes(DIR.resolve(name + ".bin")));
    }

    /** One measured run: the timed region, the whole run, and the program's own result check. */
    record Measured(long regionCycles, long regionRetired, long cycles, long retired, String check, double seconds) {
        double cpi() { return (double) regionCycles / regionRetired; }
    }

    @ParameterizedTest(name = "{0} {1}", allowZeroInvocations = true)   // no cases until scripts/workloads.sh has run
    @MethodSource("cases")
    void runs(String id, String workload) throws IOException {
        assumeTrue(Boolean.getBoolean("workloads"), "opt-in: -Pworkloads");
        var e = BUILT.computeIfAbsent(id, k -> Tessera.elaborate(() -> Cores.get(k).build(MEM, null)));
        var m = measure(e, workload);
        synchronized (ROWS) {
            ROWS.put(id + "," + workload, String.format("%s,%s,%d,%d,%d,%d,%.4f,%s,%.1f", id, workload, m.regionCycles(),
                    m.regionRetired(), m.cycles(), m.retired(), m.cpi(), m.check(), m.seconds()));
        }
    }

    /** Run {@code workload} on an elaborated core, every retirement checked against the ISS. */
    static Measured measure(Elaborated<? extends RvCore> e, String workload) throws IOException {
        int[] img = image(workload);
        RvCore core = e.top();
        try (var sim = CoreRunner.sim(e)) {
            long[] imem = sim.memory(core.imem), dmem = sim.memory(core.dmem), regs = sim.memory(core.regs);
            Arrays.fill(imem, 0);
            Arrays.fill(dmem, 0);
            Arrays.fill(regs, 0);
            for (int i = 0; i < img.length; i++) {
                imem[i] = Integer.toUnsignedLong(img[i]);
                dmem[i] = Integer.toUnsignedLong(img[i]);
            }
            var iss = new Rv32iIss(MEM, img, true);
            sim.reset(1);
            long start = sim.cycle(), retired = 0, t0 = -1, t1 = -1, r0 = 0, r1 = 0;
            long t = System.nanoTime();
            var ports = core.retirePorts();
            while (sim.peek(core.halted) == 0) {
                if (sim.cycle() - start > 400_000_000L) fail("no halt; ISS pc " + Integer.toHexString(iss.pc));
                for (var port : ports) {
                    if (sim.peek(port.valid()) != 1) continue;
                    var got = new Retire((int) sim.peek(port.pc()), (int) sim.peek(port.insn()), (int) sim.peek(port.rd()),
                            (int) sim.peek(port.rdValue()));
                    var want = iss.step();
                    if (!got.equals(want)) fail("retire #" + retired + " differs:\n  core " + got + "\n  iss  " + want);
                    if (got.insn() == START && t0 < 0) { t0 = sim.cycle(); r0 = retired; }
                    if (got.insn() == STOP) { t1 = sim.cycle(); r1 = retired; }
                    retired++;
                }
                sim.step();
            }
            double secs = (System.nanoTime() - t) / 1e9;
            assertTrue(iss.halted, "ISS did not halt");
            assertTrue(t0 >= 0 && t1 > t0, "markers not seen");
            String ok;
            if (workload.equals("coremark")) {
                String report = report(dmem, workload);
                ok = report.contains("Correct operation validated") ? "valid" : "INVALID";
                assertEquals("valid", ok, report);
            } else {
                ok = regs[10] == 0 ? "verified" : "WRONG";
                assertEquals(0, regs[10], "Embench verify_benchmark failed");
            }
            return new Measured(t1 - t0, r1 - r0, sim.cycle() - start, retired, ok, secs);
        }
    }

    /** CoreMark's ee_printf output, from the {@code report} buffer the build script located. */
    static String report(long[] dmem, String workload) throws IOException {
        int addr = -1;
        for (String l : Files.readAllLines(DIR.resolve(workload + ".map")))
            if (l.startsWith("report ")) addr = Integer.parseUnsignedInt(l.substring(7).trim(), 16);
        var sb = new StringBuilder();
        for (int a = addr; a < addr + 2048; a++) {
            int c = (int) (dmem[a >>> 2] >>> (8 * (a & 3))) & 0xFF;
            if (c == 0) break;
            sb.append((char) c);
        }
        return sb.toString();
    }

    @AfterAll
    static void write() {
        if (ROWS.isEmpty()) return;
        // -PworkloadsOut=<file> writes elsewhere than build/results
        String to = System.getProperty("workloadsOut", "");
        Path out = !to.isBlank() ? Path.of(to)
                : Path.of(System.getProperty("resultsDir", Path.of(System.getProperty("user.dir")).getParent().resolve("build/results").toString())).resolve("workloads" + (SET.isBlank() ? "" : "-" + SET) + ".csv");
        var merged = new TreeMap<String, String>();
        try {
            if (Files.exists(out))
                for (String l : Files.readAllLines(out).subList(1, Files.readAllLines(out).size())) {
                    String[] f = l.split(",");
                    merged.put(f[0] + "," + f[1], l);
                }
            merged.putAll(ROWS);
            try (var w = new PrintWriter(Files.newBufferedWriter(out))) {
                w.println("core,workload,region_cycles,region_retired,total_cycles,total_retired,region_cpi,check,sim_seconds");
                merged.values().forEach(w::println);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
