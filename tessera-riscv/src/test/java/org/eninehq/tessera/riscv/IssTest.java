package org.eninehq.tessera.riscv;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** R1: the golden instruction-set simulator passes riscv-tests. */
class IssTest {
    static List<String> tests() {
        return RiscvTests.names();
    }

    @Test
    void allTestsPresent() {
        assertTrue(tests().size() >= 39, "riscv-tests found: " + tests());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("tests")
    void passes(String name) {
        var iss = new Rv32iIss(RiscvTests.MEM_WORDS, RiscvTests.image(name));
        assertTrue(iss.run(100_000), "halted");
        assertEquals("pass", RiscvTests.verdict(iss.x[3]), name);
    }
}
