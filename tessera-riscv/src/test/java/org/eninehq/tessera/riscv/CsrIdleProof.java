package org.eninehq.tessera.riscv;

import static org.eninehq.tessera.core.Hw.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import org.eninehq.tessera.core.Bool;
import org.eninehq.tessera.core.Module;
import org.eninehq.tessera.core.Tessera;
import org.eninehq.tessera.core.UInt;
import org.eninehq.tessera.formal.Formal;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Every core updates its CSRs with {@code updateCsrs(csrs, eff.next(), en)} where {@code eff = Zicsr.step(..., valid = en)}.
 * This proves, for any instruction, operands, CSR state and interrupt line, which CSRs {@code Zicsr.step} leaves unchanged
 * when {@code valid} is false: for those, enabling the update in more cycles than {@code en} (the M7 mutants) writes the
 * value they already hold. The logic is combinational, so a one-step BMC over free inputs covers every input value.
 */
class CsrIdleProof {
    static final String[] NAMES = {"mstatus", "mie", "mtvec", "mscratch", "mepc", "mcause", "mtval", "mip", "mcycle", "mcycleh",
            "minstret", "minstreth"};

    static class Idle extends Module {
        Idle(boolean dual, boolean valid) {
            var d = HwDom.INSTANCE;
            UInt inst = input("inst", UInt.w(32)), pc = input("pc", UInt.w(32)), a = input("a", UInt.w(32)), b = input("b", UInt.w(32));
            Bool irq = input("irq", Bool.TYPE);
            List<UInt> cur = new ArrayList<>();
            for (String n : NAMES) cur.add(input(n, UInt.w(32)));
            var csrs = new Zicsr.Csrs<>(cur.get(0), cur.get(1), cur.get(2), cur.get(3), cur.get(4), cur.get(5), cur.get(6), cur.get(7),
                    cur.get(8), cur.get(9), cur.get(10), cur.get(11));
            var dec = Rv32i.decode(d, inst, true);
            var ex = Rv32i.execute(d, dec, pc, a, b);
            Bool no = Bool.of(false), v = Bool.of(valid);
            var eff = dual ? Zicsr.step(d, dec, ex, inst, pc, a, csrs, irq, v, no, no) : Zicsr.step(d, dec, ex, inst, pc, a, csrs, irq, v);
            var n = eff.next();
            UInt[] next = {n.mstatus(), n.mie(), n.mtvec(), n.mscratch(), n.mepc(), n.mcause(), n.mtval(), n.mip(), n.mcycle(), n.mcycleh(),
                    n.minstret(), n.minstreth()};
            for (int i = 0; i < NAMES.length; i++) assertThat(next[i].eq(cur.get(i)), "unchanged_" + NAMES[i]);
        }
    }

    @ParameterizedTest(name = "dual={0}")
    @ValueSource(booleans = {false, true})
    void csrsHoldWhenNotValid(boolean dual) {
        var e = Tessera.elaborate(() -> new Idle(dual, false));
        var r = Formal.bmc(e.circuit(), 1, Formal.Options.defaults());
        System.out.println("CSR-IDLE dual=" + dual + ": " + r);
        assertEquals(Formal.Status.PASS, r.status(), r.toString());
    }

    /** The same property with {@code valid} true must fail (mcycle advances): the check is not vacuous. */
    @ParameterizedTest(name = "dual={0}")
    @ValueSource(booleans = {false, true})
    void negativeControl(boolean dual) {
        var r = Formal.bmc(Tessera.elaborate(() -> new Idle(dual, true)).circuit(), 1, Formal.Options.defaults());
        System.out.println("CSR-IDLE control dual=" + dual + ": " + r);
        assertEquals(Formal.Status.FAIL, r.status(), r.toString());
    }
}
