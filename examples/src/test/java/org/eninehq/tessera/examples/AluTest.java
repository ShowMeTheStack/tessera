package org.eninehq.tessera.examples;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import org.eninehq.tessera.core.Elaborated;
import org.eninehq.tessera.core.Tessera;
import org.eninehq.tessera.sim.Sim;
import org.eninehq.tessera.sim.Simulator;

class AluTest {
    private static final Elaborated<Alu> ALU = Tessera.elaborate(() -> new Alu(32));

    static int reference(Alu.Op op, int a, int b) {
        return switch (op) {
            case ADD -> a + b;
            case SUB -> a - b;
            case AND -> a & b;
            case OR -> a | b;
            case XOR -> a ^ b;
            case SLL -> a << (b & 31);
            case SRL -> a >>> (b & 31);
            case SRA -> a >> (b & 31);
            case SLT -> a < b ? 1 : 0;
            case SLTU -> Integer.compareUnsigned(a, b) < 0 ? 1 : 0;
        };
    }

    @Property(tries = 2000)
    void matchesJavaSemantics(@ForAll Alu.Op op, @ForAll int a, @ForAll int b) {
        try (Simulator sim = Sim.create(ALU)) {
            var alu = ALU.top();
            sim.poke(alu.op, op.ordinal());
            sim.poke(alu.a, Integer.toUnsignedLong(a));
            sim.poke(alu.b, Integer.toUnsignedLong(b));
            assertEquals(Integer.toUnsignedLong(reference(op, a, b)), sim.peek(alu.y), op + " " + a + " " + b);
        }
    }
}
