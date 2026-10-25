package org.eninehq.tessera.examples;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Random;
import org.eninehq.tessera.core.Tessera;
import org.eninehq.tessera.ir.Signal;
import org.eninehq.tessera.sim.Sim;
import org.eninehq.tessera.test.Verilator;
import org.junit.jupiter.api.Test;

/** Signals wider than 64 bits: interpreter, compiled simulator, a BigInteger model and Verilator agree. */
class WideSimTest {

    static BigInteger rnd(Random r, int w) {
        return new BigInteger(w, r);
    }

    static BigInteger mask(int w) {
        return BigInteger.ONE.shiftLeft(w).subtract(BigInteger.ONE);
    }

    static BigInteger signed(BigInteger x, int w) {
        return x.testBit(w - 1) ? x.subtract(BigInteger.ONE.shiftLeft(w)) : x;
    }

    @Test
    void matchesBigIntegerModel() {
        var e = Tessera.elaborate(WideZoo::new);
        var r = new Random(3);
        try (var sim = Sim.create(e, Sim.Backend.INTERPRETER)) {
            for (int i = 0; i < 300; i++) {
                var a = rnd(r, 100);
                var b = i % 7 == 0 ? a : rnd(r, 130);
                var c = rnd(r, 100);
                var s = rnd(r, 90);
                var t = rnd(r, 70);
                int sh = r.nextInt(256);
                var n = rnd(r, 20);
                sim.pokeBig("a", a);
                sim.pokeBig("b", b);
                sim.pokeBig("c", c);
                sim.pokeBig("s", s);
                sim.pokeBig("t", t);
                sim.poke("sh", sh);
                sim.pokeBig("n", n);
                assertEquals(a.add(b), sim.peekBig("add"));
                assertEquals(a.subtract(b).and(mask(131)), sim.peekBig("sub"));
                assertEquals(a.add(c).and(mask(100)), sim.peekBig("addw"));
                assertEquals(a.multiply(b), sim.peekBig("mul"));
                assertEquals(a.multiply(n), sim.peekBig("muln"));
                assertEquals(a.xor(c), sim.peekBig("xor"));
                assertEquals(a.xor(mask(100)), sim.peekBig("not"));
                assertEquals(a.shiftLeft(67), sim.peekBig("shl"));
                assertEquals(a.shiftRight(70), sim.peekBig("shr"));
                assertEquals(sh >= 100 ? BigInteger.ZERO : a.shiftLeft(sh).and(mask(100)), sim.peekBig("dshl"));
                assertEquals(b.shiftRight(sh), sim.peekBig("dshr"));
                BigInteger ss = signed(s, 90), tt = signed(t, 70);
                assertEquals(ss.add(tt).and(mask(91)), sim.peekBig("sadd"));
                assertEquals(ss.multiply(tt).and(mask(160)), sim.peekBig("smul"));
                assertEquals(ss.negate().and(mask(91)), sim.peekBig("sneg"));
                assertEquals(ss.shiftRight(66).and(mask(90)), sim.peekBig("sra"));
                assertEquals(ss.shiftRight(Math.min(sh, 90)).and(mask(90)), sim.peekBig("dsra"));
                assertEquals(tt.and(mask(200)), sim.peekBig("sext"));
                int cmp = (a.equals(b) ? 32 : 0) | (!a.equals(c) ? 16 : 0) | (a.compareTo(b) < 0 ? 8 : 0)
                        | (a.compareTo(c) <= 0 ? 4 : 0) | (a.compareTo(b) > 0 ? 2 : 0) | (a.compareTo(c) >= 0 ? 1 : 0);
                assertEquals(cmp, sim.peek("cmp"), "cmp");
                int scmp = (ss.equals(tt) ? 32 : 0) | (!ss.equals(tt) ? 16 : 0) | (ss.compareTo(tt) < 0 ? 8 : 0)
                        | (ss.compareTo(tt) <= 0 ? 4 : 0) | (ss.compareTo(tt) > 0 ? 2 : 0) | (ss.compareTo(tt) >= 0 ? 1 : 0);
                assertEquals(scmp, sim.peek("scmp"), "scmp");
                assertEquals(n.testBit(0) ? a : b, sim.peekBig("mux"));
                assertEquals(b.shiftRight(60), sim.peekBig("slice"));
                assertEquals(b.shiftRight(62).and(mask(9)), sim.peekBig("slicen"));
                assertEquals(n.shiftLeft(230).or(b.shiftLeft(100)).or(a), sim.peekBig("cat"));
                int red = (a.equals(mask(100)) ? 4 : 0) | (b.signum() != 0 ? 2 : 0) | (c.bitCount() & 1);
                assertEquals(red, sim.peek("red"));
                assertEquals(n, sim.peekBig("ext"));
                sim.step();
            }
        }
    }

    @Test
    void compiledMatchesInterpreter() {
        var e = Tessera.elaborate(WideZoo::new);
        var r = new Random(9);
        try (var a = Sim.create(e, Sim.Backend.INTERPRETER); var b = Sim.create(e, Sim.Backend.COMPILED)) {
            var paths = a.netlist().signalsByPath().keySet().stream().filter(p -> !p.equals("clock")).toList();
            var inputs = e.circuit().top().ports().stream().filter(p -> p.kind() == Signal.Kind.INPUT && !p.name().equals("clock")).toList();
            for (int cyc = 0; cyc < 500; cyc++) {
                for (var p : inputs) {
                    var v = p.name().equals("reset") ? BigInteger.valueOf(cyc < 2 ? 1 : 0) : rnd(r, p.width());
                    a.pokeBig(p.name(), v);
                    b.pokeBig(p.name(), v);
                }
                for (var p : paths) assertEquals(a.peekBig(p), b.peekBig(p), "cycle " + cyc + " " + p);
                a.step();
                b.step();
            }
        }
    }

    @Test
    void matchesVerilator() throws Exception {
        assumeTrue(Verilator.available());
        var e = Tessera.elaborate(WideZoo::new);
        var r = new Random(5);
        var inputs = e.circuit().top().ports().stream().filter(p -> p.kind() == Signal.Kind.INPUT && !p.name().equals("clock")).toList();
        var stim = new ArrayList<BigInteger[]>();
        for (int cyc = 0; cyc < 300; cyc++) {
            var row = new BigInteger[inputs.size()];
            for (int i = 0; i < row.length; i++)
                row[i] = inputs.get(i).name().equals("reset") ? BigInteger.valueOf(cyc < 2 ? 1 : 0) : rnd(r, inputs.get(i).width());
            stim.add(row);
        }
        Verilator.lint(e.circuit(), "lint-WideZoo");
        Verilator.crossCheckBig(e, "xcheck-WideZoo", stim, Sim.Backend.COMPILED);
    }
}
