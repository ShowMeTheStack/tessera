package org.eninehq.tessera.fpga;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import org.eninehq.tessera.riscv.RandomProgram;
import org.eninehq.tessera.riscv.Rv32iIss;

/**
 * The board test: every program runs on the SoC and on the golden ISS ({@link Rv32iIss}); the
 * board must halt, retire the same number of instructions, and leave the same registers and
 * the same data (riscv-arch-test signatures must also equal the suite's reference output).
 *
 * <pre>ZedSuite &lt;ssh host&gt; &lt;dir on host&gt; &lt;core&gt; [random programs] [FCLK slowdown] [keep]</pre>
 * The same code runs against the simulator in the tests ({@code ZedSuiteSimTest}).
 */
public final class ZedSuite {
    private ZedSuite() {}

    /** One program; {@code sigBegin..sigEnd} is the arch-test signature (empty otherwise). */
    public record Case(String kind, String name, int[] image, int sigBegin, int sigEnd, List<Integer> reference) {}

    public record Outcome(Case c, boolean pass, String detail, long cycles, long retired) {}

    static InputStream resource(String path) {
        return Objects.requireNonNull(ZedSuite.class.getResourceAsStream(path), path + " (run with the test resources)");
    }

    static String text(String path) {
        try (var in = resource(path)) {
            return new String(in.readAllBytes());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static int[] image(String path) {
        try (var in = resource(path)) {
            return org.eninehq.tessera.riscv.Programs.words(in.readAllBytes());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static List<String> listing(String dir, String suffix) {
        var out = new ArrayList<String>();
        for (String l : text(dir).split("\n"))
            if (l.endsWith(suffix)) out.add(l.substring(0, l.length() - suffix.length()));
        out.sort(null);
        return out;
    }

    /**
     * riscv-tests (rv32ui, rv32um), the riscv-arch-tests that fit in memory, random RV32IM
     * programs, and with {@code zicsr} the machine-mode programs (CSRs, traps, an external interrupt).
     */
    public static List<Case> cases(int randomPrograms, boolean zicsr) {
        return cases(randomPrograms, zicsr, Designs.MEM_WORDS, null);
    }

    /**
     * As above for a core with {@code memWords} of memory, plus the Embench-IoT and CoreMark
     * images in {@code workloads} (built by scripts/workloads.sh for this memory size), if given.
     */
    public static List<Case> cases(int randomPrograms, boolean zicsr, int memWords, Path workloads) {
        var out = new ArrayList<Case>();
        if (zicsr)
            for (String t : List.of("csr", "traps", "irq"))
                out.add(new Case(t.equals("irq") ? "irq" : "zicsr", t, image("/zicsr/" + t + ".bin"), 0, 0, List.of()));
        for (String t : listing("/riscv-tests/", ".bin"))
            out.add(new Case("riscv-tests", t, image("/riscv-tests/" + t + ".bin"), 0, 0, List.of()));
        for (String t : listing("/arch-test/", ".bin")) {
            String[] sig = text("/arch-test/" + t + ".sig").trim().split("\\s+");
            int begin = Integer.parseInt(sig[0], 16), end = Integer.parseInt(sig[1], 16);
            int[] img = image("/arch-test/" + t + ".bin");
            if (img.length > memWords || end > 4 * memWords) continue;
            var ref = new ArrayList<Integer>();
            for (String l : text("/arch-test/" + t + ".reference_output").split("\n"))
                if (!l.isBlank()) ref.add((int) Long.parseLong(l.trim(), 16));
            out.add(new Case("arch-test", t, img, begin, end, ref));
        }
        out.add(new Case("bench", "divide-overlap", org.eninehq.tessera.riscv.Benchmarks.divideOverlap(), 0, 0, List.of()));
        out.add(new Case("bench", "multiply-overlap", org.eninehq.tessera.riscv.Benchmarks.multiplyOverlap(), 0, 0, List.of()));
        out.add(new Case("bench", "dependent-chain", org.eninehq.tessera.riscv.Benchmarks.dependentChain(), 0, 0, List.of()));
        for (int s = 0; s < randomPrograms; s++)
            out.add(new Case("random", "seed" + s, RandomProgram.generate(1000 + s, 400, s % 2 == 1), 0, 0, List.of()));
        if (workloads != null && Files.isDirectory(workloads))
            try (var files = Files.list(workloads)) {
                for (Path b : files.filter(f -> f.toString().endsWith(".bin")).sorted().toList()) {
                    int[] img = org.eninehq.tessera.riscv.Programs.words(Files.readAllBytes(b));
                    if (img.length > memWords) continue;
                    String name = b.getFileName().toString().replace(".bin", "");
                    // the report buffer (CoreMark) as the "signature" range, so its text can be read back
                    int rep = 0;
                    Path map = b.resolveSibling(name + ".map");
                    if (Files.exists(map))
                        for (String l : Files.readAllLines(map))
                            if (l.startsWith("report ")) rep = Integer.parseUnsignedInt(l.substring(7).trim(), 16);
                    out.add(new Case("workload", name, img, rep, rep == 0 ? 0 : rep + 2048, List.of()));
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        return out;
    }

    /** Run one case on the SoC and on the ISS and compare. */
    public static Outcome run(SocHost host, Case c, boolean zicsr, int maxPolls) {
        return run(host, c, zicsr, maxPolls, Designs.MEM_WORDS);
    }

    public static Outcome run(SocHost host, Case c, boolean zicsr, int maxPolls, int memWords) {
        if (c.kind().equals("irq")) return runInterrupt(host, c, maxPolls);
        host.clearRegisters();
        host.load(c.image());
        // the ISS's memory is zero beyond the image: so must the data memory be where programs look
        int dataEnd = Math.max(c.sigEnd() / 4, (RandomProgram.DATA_BASE + 1024) / 4);
        if (dataEnd > c.image().length) host.clearData(c.image().length, dataEnd - c.image().length);
        var r = host.run(maxPolls);

        var iss = new Rv32iIss(memWords, c.image(), zicsr);
        boolean issHalted = iss.run(c.kind().equals("workload") ? 400_000_000L : 10_000_000);
        var errors = new ArrayList<String>();
        if (!issHalted) errors.add("ISS did not halt");
        if (!r.halted()) errors.add("did not halt (last pc 0x" + Long.toHexString(r.lastPc()) + ")");
        if (r.retired() != iss.retired) errors.add("retired " + r.retired() + ", ISS " + iss.retired);
        int[] regs = host.regs();
        for (int i = 1; i < 32; i++)
            if (regs[i] != iss.x[i]) errors.add(String.format("x%d=%08x, ISS %08x", i, regs[i], iss.x[i]));
        if (c.kind().equals("riscv-tests") && regs[3] != 1) errors.add("gp=" + regs[3] + " (test " + (regs[3] >>> 1) + " failed)");
        if (c.kind().equals("workload")) {
            if (c.name().equals("coremark")) {
                int[] rep = host.data(c.sigBegin() / 4, 512);
                var sb = new StringBuilder();
                for (int w : rep) for (int k = 0; k < 4 && (w >>> (8 * k) & 0xFF) != 0; k++) sb.append((char) (w >>> (8 * k) & 0xFF));
                if (!sb.toString().contains("Correct operation validated")) errors.add("CoreMark did not validate");
            } else if (regs[10] != 0) errors.add("Embench verify_benchmark failed (a0=" + regs[10] + ")");
        } else if (c.sigEnd() > c.sigBegin()) {
            int n = (c.sigEnd() - c.sigBegin()) / 4;
            int[] sig = host.data(c.sigBegin() / 4, n);
            List<Integer> got = Arrays.stream(sig).boxed().toList();
            if (!got.equals(c.reference())) errors.add("signature differs from the reference");
        }
        if (c.kind().equals("random")) {
            int from = RandomProgram.DATA_BASE / 4;
            int[] data = host.data(from, 256);
            for (int i = 0; i < 256; i++)
                if (data[i] != iss.mem[from + i]) {
                    errors.add(String.format("mem[%x]=%08x, ISS %08x", 4 * (from + i), data[i], iss.mem[from + i]));
                    break;
                }
        }
        return new Outcome(c, errors.isEmpty(), String.join("; ", errors), r.cycles(), r.retired());
    }

    /**
     * The interrupt program loops until its handler runs: raise {@code irq} (CTRL bit 1) while
     * it runs, at a time the ISS cannot know, and check what the handler and the loop recorded.
     */
    static Outcome runInterrupt(SocHost host, Case c, int maxPolls) {
        host.clearRegisters();
        host.load(c.image());
        var bus = host.bus();
        bus.write(SocHost.CTRL, 0);
        bus.write(SocHost.CTRL, 1);
        bus.write(SocHost.CTRL, 3);
        boolean halted = bus.waitFor(SocHost.STATUS, 1, maxPolls);
        long cycles = bus.read(SocHost.CYCLES), retired = bus.read(SocHost.RETIRED);
        bus.write(SocHost.CTRL, 0);
        int[] x = host.regs();
        var errors = new ArrayList<String>();
        if (!halted) errors.add("did not halt");
        if (x[18] != 1) errors.add("handler did not run (s2=" + x[18] + ")");
        if (x[9] < 1) errors.add("loop did not run before the interrupt (s1=" + x[9] + ")");
        if (x[19] != 0x8000000B) errors.add(String.format("mcause %08x", x[19]));
        if (x[20] != 0x1888) errors.add(String.format("mstatus after mret %08x", x[20]));
        String info = String.format("loop ran %d times before the interrupt, mepc %08x", x[9], x[21]);
        return new Outcome(c, errors.isEmpty(), errors.isEmpty() ? info : String.join("; ", errors), cycles, retired);
    }

    public static List<Outcome> runAll(SocHost host, List<Case> cases, boolean zicsr, int maxPolls,
                                       java.util.function.Consumer<Outcome> progress) {
        return runAll(host, cases, zicsr, maxPolls, Designs.MEM_WORDS, progress);
    }

    public static List<Outcome> runAll(SocHost host, List<Case> cases, boolean zicsr, int maxPolls, int memWords,
                                       java.util.function.Consumer<Outcome> progress) {
        var out = new ArrayList<Outcome>();
        for (var c : cases) {
            var o = run(host, c, zicsr, c.kind().equals("workload") ? maxPolls * 50 : maxPolls, memWords);
            progress.accept(o);
            out.add(o);
        }
        return out;
    }

    public static String summary(List<Outcome> outcomes) {
        var sb = new StringBuilder();
        for (String kind : List.of("zicsr", "irq", "riscv-tests", "arch-test", "bench", "random", "workload")) {
            var k = outcomes.stream().filter(o -> o.c().kind().equals(kind)).toList();
            if (k.isEmpty()) continue;
            long pass = k.stream().filter(Outcome::pass).count();
            long cyc = k.stream().mapToLong(Outcome::cycles).sum(), ret = k.stream().mapToLong(Outcome::retired).sum();
            sb.append(String.format("%-12s %3d/%3d pass   %9d cycles %9d retired   CPI %.3f%n", kind, pass, k.size(), cyc, ret,
                    (double) cyc / Math.max(1, ret)));
        }
        return sb.toString();
    }

    public static void main(String[] args) throws Exception {
        String host = args[0], dir = args[1], core = args[2];
        int random = args.length > 3 ? Integer.parseInt(args[3]) : 100;
        int slowdown = args.length > 4 ? Integer.parseInt(args[4]) : 1;
        boolean program = !(args.length > 5 && args[5].equals("keep"));     // keep: use the design already on the FPGA
        int words = ZedBuild.memWords(core);
        // Embench-IoT and CoreMark linked for this memory size (scripts/workloads.sh, MEM_KB)
        Path wl = Path.of(words == Designs.MEM_WORDS ? "build/workloads" : "build/workloads-" + (4 * words / 1024) + "k");
        var cases = cases(random, ZedBuild.zicsr(core), words, args.length > 6 ? Path.of(args[6]) : wl);
        long t0 = System.nanoTime();
        try (var bus = XsctBus.open(host, dir, slowdown, program)) {
            var h = new SocHost(bus);
            long id = h.id();
            System.out.printf("board: SoC id 0x%08x (expected 0x%08x for %s)%n", id, ZedBuild.id(core), core);
            if (id != ZedBuild.id(core)) throw new IllegalStateException("wrong bitstream on the board");
            // the clock the core really runs at: an endless loop (jal x0, 0), cycles over host time
            h.clearRegisters();
            h.load(new int[]{0x0000006F});
            bus.write(SocHost.CTRL, 0);
            bus.write(SocHost.CTRL, 1);
            double mhz = bus.measureClockMHz(2000);
            bus.write(SocHost.CTRL, 0);
            System.out.printf("board: fabric clock measured %.2f MHz%n", mhz);
            var outcomes = runAll(h, cases, ZedBuild.zicsr(core), 2000, words, o -> System.out.printf("  %-4s %-11s %-22s %7d cycles %7d retired %s%n",
                    o.pass() ? "ok" : "FAIL", o.c().kind(), o.c().name(), o.cycles(), o.retired(), o.detail()));
            String s = summary(outcomes);
            System.out.print(s);
            System.out.printf("%.0f s%n", (System.nanoTime() - t0) / 1e9);
            // batch builds (tessera-zed/pb/<flow>/<core>_<MHz>) are reported as <flow>-<core>_<MHz>
            String run = dir.startsWith("tessera-zed/pb/") ? dir.substring("tessera-zed/pb/".length()).replace('/', '-') : Path.of(dir).getFileName().toString();
            Path report = Path.of("build/zed/" + run + "/board-report.txt");
            Files.createDirectories(report.getParent());
            var sb = new StringBuilder();
            for (var o : outcomes)
                sb.append(String.format("%s %s %s %d %d %s%n", o.pass() ? "ok" : "FAIL", o.c().kind(), o.c().name(), o.cycles(),
                        o.retired(), o.detail()));
            Files.writeString(report, String.format("fabric clock measured %.2f MHz%n", mhz) + sb + s);
            if (outcomes.stream().anyMatch(o -> !o.pass())) System.exit(1);
        }
    }
}
