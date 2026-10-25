package org.eninehq.tessera.riscv;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

/** The riscv-tests harness must detect a wrong model (guards against vacuous passes). */
class HarnessSanityTest {

    @Test
    void brokenSltuIsCaught() {
        var img = RiscvTests.image("rv32ui-sltu");
        int[] x = new int[32];
        int[] mem = new int[RiscvTests.MEM_WORDS];
        System.arraycopy(img, 0, mem, 0, img.length);
        int pc = 0;
        // a model whose SLTU is signed: run the shared semantics with a mutated domain
        Dom<Integer, Boolean> broken = new MutatedDom();
        for (int i = 0; i < 100_000; i++) {
            int inst = mem[pc >>> 2];
            var dec = Rv32i.decode(broken, inst);
            var ex = Rv32i.execute(broken, dec, pc, x[dec.rs1()], x[dec.rs2()]);
            int v = dec.load() ? Rv32i.loadValue(broken, dec.funct3(), ex.memAddr(), mem[ex.memAddr() >>> 2]) : ex.result();
            if (dec.store()) mem[ex.memAddr() >>> 2] = Rv32i.storeMerge(broken, dec.funct3(), ex.memAddr(), mem[ex.memAddr() >>> 2], x[dec.rs2()]);
            if (dec.writesRd()) x[dec.rd()] = v;
            if (dec.halt()) break;
            pc = ex.redirect() ? ex.target() : pc + 4;
        }
        assertNotEquals("pass", RiscvTests.verdict(x[3]));
        assertEquals(1, x[3] & 1, "failure reported through gp");
    }

    /** SwDom with ltu replaced by signed comparison. */
    private static final class MutatedDom implements Dom<Integer, Boolean> {
        private final SwDom d = SwDom.INSTANCE;
        @Override public Integer k(long v) { return d.k(v); }
        @Override public Boolean bk(boolean v) { return d.bk(v); }
        @Override public Integer add(Integer a, Integer b) { return d.add(a, b); }
        @Override public Integer sub(Integer a, Integer b) { return d.sub(a, b); }
        @Override public Integer and(Integer a, Integer b) { return d.and(a, b); }
        @Override public Integer or(Integer a, Integer b) { return d.or(a, b); }
        @Override public Integer xor(Integer a, Integer b) { return d.xor(a, b); }
        @Override public Integer sll(Integer a, Integer s) { return d.sll(a, s); }
        @Override public Integer srl(Integer a, Integer s) { return d.srl(a, s); }
        @Override public Integer sra(Integer a, Integer s) { return d.sra(a, s); }
        @Override public Integer shl(Integer a, int n) { return d.shl(a, n); }
        @Override public Boolean eq(Integer a, Integer b) { return d.eq(a, b); }
        @Override public Boolean lt(Integer a, Integer b) { return d.lt(a, b); }
        @Override public Boolean ltu(Integer a, Integer b) { return d.lt(a, b); }
        @Override public Integer mux(Boolean c, Integer a, Integer b) { return d.mux(c, a, b); }
        @Override public Boolean bmux(Boolean c, Boolean a, Boolean b) { return d.bmux(c, a, b); }
        @Override public Boolean bnot(Boolean a) { return d.bnot(a); }
        @Override public Boolean band(Boolean a, Boolean b) { return d.band(a, b); }
        @Override public Boolean bor(Boolean a, Boolean b) { return d.bor(a, b); }
        @Override public Integer bits(Integer x, int hi, int lo) { return d.bits(x, hi, lo); }
        @Override public Integer sext(Integer x, int w) { return d.sext(x, w); }
        @Override public Boolean bit(Integer x, int i) { return d.bit(x, i); }
        @Override public Integer fromBool(Boolean b) { return d.fromBool(b); }
        @Override public Integer mul16(Integer a, Integer b) { return d.mul16(a, b); }
    }
}
