package org.eninehq.tessera.riscv;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Supplier;
import org.eninehq.tessera.core.Tessera;
import org.eninehq.tessera.pipeline.Schedule;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * CPI of the generators' parameters on CoreMark and five Embench
 * programs. The scheduled model with each schedule, with LUT and block RAM; the out-of-order
 * core with ROB 4..32 and RS 3..8. Opt-in: -Pworkloads; writes build/results/sweep.csv.
 */
@Execution(ExecutionMode.CONCURRENT)
class SweepTest {
    static final List<String> PROGRAMS = List.of("coremark", "embench-aha-mont64", "embench-crc32", "embench-edn",
            "embench-nsichneu", "embench-statemate");
    static final Map<String, String> ROWS = new TreeMap<>();

    record Config(String family, String name, Supplier<RvCore> core) {
        @Override public String toString() { return name; }
    }

    static List<Config> configs() {
        var out = new ArrayList<Config>();
        Object[][] scheds = {{"3", ScheduledCore.THREE_STAGE, ScheduledCore.THREE_STAGE_BRAM}, {"4", ScheduledCore.FOUR_STAGE, ScheduledCore.FOUR_STAGE_BRAM},
                {"5", ScheduledCore.FIVE_STAGE, ScheduledCore.FIVE_STAGE}, {"6", ScheduledCore.SIX_STAGE, ScheduledCore.SIX_STAGE}};
        for (var s : scheds) {
            out.add(new Config("scheduled", s[0] + "-stage LUT RAM", () -> new ScheduledCore(WorkloadTest.MEM, null, (Schedule) s[1], Cores.common(false))));
            out.add(new Config("scheduled", s[0] + "-stage BRAM", () -> new ScheduledCore(WorkloadTest.MEM, null, (Schedule) s[2], Cores.common(true))));
        }
        for (int rob : new int[]{4, 8, 16, 32})
            for (int rs : new int[]{3, 6, 8}) {
                if (rs > rob) continue;
                out.add(new Config("ooo", "ROB " + rob + " RS " + rs, () -> new OooCore(WorkloadTest.MEM, null, Cores.common(true), rob, rs)));
            }
        return out;
    }

    static List<Arguments> cases() {
        var out = new ArrayList<Arguments>();
        for (var c : configs()) for (var p : PROGRAMS) out.add(Arguments.of(c, p));
        return out;
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("cases")
    void cpi(Config c, String program) throws IOException {
        assumeTrue(Boolean.getBoolean("workloads"), "opt-in: -Pworkloads");
        var m = WorkloadTest.measure(Tessera.elaborate(c.core()), program);
        synchronized (ROWS) {
            ROWS.put(c.name() + "," + program, String.format("%s,%s,%s,%d,%d,%.4f,%s", c.family(), c.name(), program, m.regionCycles(),
                    m.regionRetired(), m.cpi(), m.check()));
        }
    }

    @AfterAll
    static void write() throws IOException {
        if (ROWS.isEmpty()) return;
        Path out = Path.of(System.getProperty("resultsDir", Path.of(System.getProperty("user.dir")).getParent().resolve("build/results").toString())).resolve("sweep.csv");
        try (var w = new PrintWriter(Files.newBufferedWriter(out))) {
            w.println("family,config,program,region_cycles,region_retired,region_cpi,check");
            ROWS.values().forEach(w::println);
        }
    }
}
