package org.eninehq.tessera.fpga;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;
import org.eninehq.tessera.core.Tessera;
import org.eninehq.tessera.riscv.RvCore;
import org.eninehq.tessera.riscv.ScheduledCore;
import org.eninehq.tessera.sim.Sim;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The SoC as the ARM sees it: load a program over AXI-Lite, run it, read the registers back. */
class RvSocTest {

    static int[] image(String path) {
        try (var in = RvSocTest.class.getResourceAsStream("/" + path + ".bin")) {
            return org.eninehq.tessera.riscv.Programs.words(Objects.requireNonNull(in, path).readAllBytes());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static RvCore core(String kind) {
        var cfg = RvCore.Config.fpga();
        return switch (kind) {
            case "bram5" -> new ScheduledCore(Designs.MEM_WORDS, null, ScheduledCore.FIVE_STAGE, cfg);
            default -> throw new IllegalArgumentException(kind);
        };
    }

    @ParameterizedTest
    @ValueSource(strings = {"riscv-tests/rv32ui-add", "riscv-tests/rv32ui-lw", "riscv-tests/rv32ui-sb",
            "riscv-tests/rv32um-div", "riscv-tests/rv32ui-jalr"})
    void runsProgramsOverAxi(String program) {
        var e = Tessera.elaborate(() -> new RvSoc(() -> core("bram5"), 0x7E55_0001L));
        int[] img = image(program);
        try (var sim = Sim.create(e, Sim.Backend.COMPILED)) {
            RvSoc soc = e.top();
            var axi = new AxiDriver(sim, soc.sAxi);
            sim.poke("aresetn", 0);
            sim.step(2);
            sim.poke("aresetn", 1);
            assertEquals(0x7E55_0001L, axi.read(0x10));
            for (int i = 0; i < img.length; i++) {
                axi.write(0x10000 + 4L * i, Integer.toUnsignedLong(img[i]));
                axi.write(0x20000 + 4L * i, Integer.toUnsignedLong(img[i]));
            }
            assertEquals(Integer.toUnsignedLong(img[3]), axi.read(0x20000 + 12));
            axi.write(0x0, 1);
            for (int i = 0; (axi.read(0x4) & 1) == 0; i++) if (i > 20_000) fail("no halt");
            long cycles = axi.read(0x8), retired = axi.read(0xC);
            axi.write(0x0, 0);
            long gp = axi.read(0x80 + 4 * 3);
            System.out.printf("%s: gp=%d cycles=%d retired=%d%n", program, gp, cycles, retired);
            assertEquals(1, gp, "test reports pass in gp");
        }
    }
}
