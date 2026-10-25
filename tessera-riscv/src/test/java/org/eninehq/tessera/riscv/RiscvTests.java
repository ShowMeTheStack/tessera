package org.eninehq.tessera.riscv;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Access to the prebuilt rv32ui riscv-tests binaries (see scripts/riscv-tests.sh). */
final class RiscvTests {
    private RiscvTests() {}

    static final int MEM_WORDS = 4096;

    static List<String> names() {
        var out = new ArrayList<String>();
        try (InputStream in = RiscvTests.class.getResourceAsStream("/riscv-tests/")) {
            Objects.requireNonNull(in, "riscv-tests resources missing; run scripts/riscv-tests.sh");
            for (String line : new String(in.readAllBytes()).split("\n"))
                if (line.endsWith(".bin")) out.add(line.substring(0, line.length() - 4));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        out.sort(null);
        return out;
    }

    static int[] image(String name) {
        try (InputStream in = RiscvTests.class.getResourceAsStream("/riscv-tests/" + name + ".bin")) {
            return Programs.words(Objects.requireNonNull(in, name).readAllBytes());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Pass/fail as reported by the test environment in gp (x3). */
    static String verdict(int gp) {
        return gp == 1 ? "pass" : "fail (test " + (gp >>> 1) + ")";
    }
}
