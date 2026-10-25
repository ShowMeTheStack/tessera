package org.eninehq.tessera.babylontests;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Supplier;
import jdk.incubator.code.Reflect;
import org.eninehq.tessera.babylon.Hardware;
import org.eninehq.tessera.babylon.JavaHw;
import org.eninehq.tessera.core.HwEnum;
import org.eninehq.tessera.core.Module;
import org.eninehq.tessera.core.Tessera;
import org.eninehq.tessera.core.UInt;
import org.eninehq.tessera.emit.SvEmitter;
import org.eninehq.tessera.pipeline.Pipeline;
import org.eninehq.tessera.examples.Alu.Op;
import org.junit.jupiter.api.Test;

/** Babylon notation: the combinational and pipelined comparison designs as plain Java. */
class NotationBabylon {
    // [alu]
    @Reflect
    static int alu(Op op, int a, int b) {
        return switch (op) {
            case ADD -> a + b;
            case SUB -> a - b;
            case AND -> a & b;
            case OR -> a | b;
            case XOR -> a ^ b;
            case SLL -> a << b;
            case SRL -> a >>> b;
            case SRA -> a >> b;
            case SLT -> a < b ? 1 : 0;
            case SLTU -> Integer.compareUnsigned(a, b) < 0 ? 1 : 0;
        };
    }

    // [/alu]

    // [immdecode]
    @Reflect static int immI(int inst) { return inst >> 20; }
    @Reflect static int immS(int inst) { return ((inst >> 25) << 5) | ((inst >>> 7) & 0x1F); }
    @Reflect static int immB(int inst) {
        return ((inst >> 31) << 12) | (((inst >>> 7) & 1) << 11) | (((inst >>> 25) & 0x3F) << 5) | (((inst >>> 8) & 0xF) << 1);
    }
    @Reflect static int immU(int inst) { return inst & 0xFFFFF000; }
    @Reflect static int immJ(int inst) {
        return ((inst >> 31) << 20) | (inst & 0xFF000) | (((inst >>> 20) & 1) << 11) | (((inst >>> 21) & 0x3FF) << 1);
    }

    // [/immdecode]

    // [muladd]
    @Reflect
    static int mulAdd(int a, int b, int c) {
        int prod = a * b;
        Hardware.stage();
        int sum = prod + c;
        Hardware.stage();
        return sum;
    }

    // [/muladd]

    // [alu]
    static class Alu extends Module {
        Alu() {
            var op = input("op", HwEnum.of(Op.class));
            UInt a = input("a", UInt.w(32)), b = input("b", UInt.w(32));
            output("y", UInt.w(32)).set((UInt) JavaHw.of(NotationBabylon.class, "alu").apply(op, a, b));
        }
    }

    // [/alu]

    // [immdecode]
    static class ImmDecode extends Module {
        ImmDecode() {
            UInt inst = input("inst", UInt.w(32));
            for (var f : new String[]{"i", "s", "b", "u", "j"})
                output(f, UInt.w(32)).set((UInt) JavaHw.of(NotationBabylon.class, "imm" + f.toUpperCase()).apply(inst));
        }
    }

    // [/immdecode]

    // [muladd]
    static class MulAdd extends Module {
        MulAdd() {
            UInt a = input("a", UInt.w(16)), b = input("b", UInt.w(16)), c = input("c", UInt.w(32));
            var p = Pipeline.begin("mac");
            UInt y = (UInt) JavaHw.of(NotationBabylon.class, "mulAdd").apply(p.input(a).zext(32), p.input(b).zext(32), p.input(c));
            output("y", UInt.w(32)).set(y);
            p.end();
        }
    }

    // [/muladd]

    /** Writes build/notation/<design>/babylon.sv for the comparison (scripts/notation.py). */
    @Test
    void export() throws Exception {
        Map<String, Supplier<? extends Module>> designs = Map.of("alu", Alu::new, "immdecode", ImmDecode::new, "muladd", MulAdd::new);
        Path root = Path.of(System.getProperty("user.dir")).getParent().resolve("build/notation");
        for (var e : designs.entrySet()) {
            Path out = root.resolve(e.getKey()).resolve("babylon.sv");
            Files.createDirectories(out.getParent());
            SvEmitter.write(Tessera.elaborate(e.getValue()).circuit(), out);
        }
    }
}
