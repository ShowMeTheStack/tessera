package org.eninehq.tessera.riscv;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import org.eninehq.tessera.core.Elaborated;
import org.eninehq.tessera.core.Tessera;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * riscv-arch-test 2.7.4 (rv32i_m/I, built by scripts/arch-test.sh): the signature written by
 * the golden ISS must equal the reference signature from the suite, and every core must
 * retire exactly what the ISS retires and leave the same signature in memory.
 */
class ArchTest {

    record Test(String name, int[] image, int begin, int end, List<Integer> reference) {
        int memWords() {
            int need = Math.max(image.length, end / 4 + 1);
            return Integer.highestOneBit(need - 1) << 1;
        }
    }

    static String text(String path) {
        try (InputStream in = ArchTest.class.getResourceAsStream(path)) {
            return new String(Objects.requireNonNull(in, path).readAllBytes());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static List<String> tests() {
        var out = new ArrayList<String>();
        for (String line : text("/arch-test/").split("\n"))
            if (line.endsWith(".bin")) out.add(line.substring(0, line.length() - 4));
        out.sort(null);
        return out;
    }

    static Test load(String name) {
        int[] image = RiscvTests.image("../arch-test/" + name);
        String[] sig = text("/arch-test/" + name + ".sig").trim().split("\\s+");
        var ref = new ArrayList<Integer>();
        for (String l : text("/arch-test/" + name + ".reference_output").split("\n"))
            if (!l.isBlank()) ref.add((int) Long.parseLong(l.trim(), 16));
        return new Test(name, image, Integer.parseInt(sig[0], 16), Integer.parseInt(sig[1], 16), ref);
    }

    static List<Integer> signature(int[] wordsAt0, int begin, int end) {
        var out = new ArrayList<Integer>();
        for (int a = begin; a < end; a += 4) out.add(wordsAt0[a >>> 2]);
        return out;
    }

    @ParameterizedTest(name = "iss {0}")
    @MethodSource("tests")
    void issMatchesReferenceSignature(String name) {
        var t = load(name);
        var iss = new Rv32iIss(t.memWords(), t.image());
        assertTrue(iss.run(5_000_000), name + " did not halt");
        assertEquals(t.reference(), signature(iss.mem, t.begin(), t.end()), name);
    }

    static final Map<String, Elaborated<? extends RvCore>> CORES = new ConcurrentHashMap<>();

    static Elaborated<? extends RvCore> core(String kind, int memWords) {
        BiFunction<Integer, String, Elaborated<? extends RvCore>> make = (m, k) -> switch (k) {
            case "single" -> Tessera.elaborate(() -> new SingleCycleCore(m, null));
            case "five" -> Tessera.elaborate(() -> new FiveStageCore(m, null));
            case "sched4" -> Tessera.elaborate(() -> new ScheduledCore(m, null, ScheduledCore.FOUR_STAGE));
            default -> throw new IllegalArgumentException(k);
        };
        return CORES.computeIfAbsent(kind + memWords, x -> make.apply(memWords, kind));
    }

    void runOn(String kind, String name) {
        var t = load(name);
        var e = core(kind, t.memWords());
        try (var sim = CoreRunner.sim(e)) {
            CoreRunner.run(e, sim, t.image(), 20_000_000);
            long[] dmem = sim.memory(e.top().dmem);
            int[] words = new int[dmem.length];
            for (int i = 0; i < words.length; i++) words[i] = (int) dmem[i];
            assertEquals(t.reference(), signature(words, t.begin(), t.end()), kind + " " + name);
        }
    }

    @ParameterizedTest(name = "single-cycle {0}")
    @MethodSource("tests")
    void singleCycle(String name) {
        runOn("single", name);
    }

    @ParameterizedTest(name = "five-stage {0}")
    @MethodSource("tests")
    void fiveStage(String name) {
        runOn("five", name);
    }

    @ParameterizedTest(name = "scheduled 4-stage {0}")
    @MethodSource("tests")
    void scheduledFourStage(String name) {
        runOn("sched4", name);
    }
}
