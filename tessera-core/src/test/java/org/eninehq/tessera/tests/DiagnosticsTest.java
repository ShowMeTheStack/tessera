package org.eninehq.tessera.tests;

import static org.eninehq.tessera.core.Hw.when;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.eninehq.tessera.core.Bool;
import org.eninehq.tessera.core.Module;
import org.eninehq.tessera.core.SInt;
import org.eninehq.tessera.core.Tessera;
import org.eninehq.tessera.core.TesseraException;
import org.eninehq.tessera.core.UInt;
import org.junit.jupiter.api.Test;

/** Errors must be reported against the user's Java line, with an actionable message. */
class DiagnosticsTest {

    static int lineOf(TesseraException e) {
        return e.diagnostics().getFirst().loc().line();
    }

    static class Truncating extends Module {
        static int LINE;
        Truncating() {
            UInt a = input("a", UInt.w(8));
            UInt y = output("y", UInt.w(8));
            LINE = new Throwable().getStackTrace()[0].getLineNumber() + 1;
            y.set(a.add(a));
        }
    }

    @Test
    void wideningAddCannotSilentlyTruncate() {
        var e = assertThrows(TesseraException.class, () -> Tessera.elaborate(Truncating::new));
        assertEquals(Truncating.LINE, lineOf(e));
        assertEquals("DiagnosticsTest.java", e.diagnostics().getFirst().loc().file());
        assertTrue(e.getMessage().contains("9 bits wide but 'y' is 8 bits"), e.getMessage());
        assertTrue(e.getMessage().contains(".trunc(8)"), e.getMessage());
    }

    static class BitwiseMismatch extends Module {
        BitwiseMismatch() {
            UInt a = input("a", UInt.w(8));
            UInt b = input("b", UInt.w(4));
            output("y", UInt.w(8)).set(a.and(b));
        }
    }

    @Test
    void bitwiseNeedsEqualWidths() {
        var e = assertThrows(TesseraException.class, () -> Tessera.elaborate(BitwiseMismatch::new));
        assertTrue(e.getMessage().contains("and: operand widths differ (8 vs 4)"), e.getMessage());
    }

    static class PartialWire extends Module {
        PartialWire() {
            Bool c = input("c", Bool.TYPE);
            UInt w = wire("w", UInt.w(4));
            when(c, () -> w.set(3));
            output("y", UInt.w(4)).set(w);
        }
    }

    @Test
    void wireAssignedOnlyUnderConditionIsAnError() {
        var e = assertThrows(TesseraException.class, () -> Tessera.elaborate(PartialWire::new));
        assertTrue(e.getMessage().contains("wire 'w' is not assigned on every path"), e.getMessage());
    }

    static class FullWire extends Module {
        FullWire() {
            Bool c = input("c", Bool.TYPE);
            Bool d = input("d", Bool.TYPE);
            UInt w = wire("w", UInt.w(4));
            when(c, () -> w.set(1)).elseWhen(d, () -> w.set(2)).otherwise(() -> w.set(3));
            output("y", UInt.w(4)).set(w);
        }
    }

    @Test
    void whenChainWithOtherwiseCoversEveryPath() {
        Tessera.elaborate(FullWire::new);
    }

    static class Loop extends Module {
        Loop() {
            UInt a = wire("a", UInt.w(4));
            UInt b = wire("b", UInt.w(4));
            a.set(b.xor(1));
            b.set(a);
            output("y", UInt.w(4)).set(a);
        }
    }

    @Test
    void combinationalLoopsAreRejected() {
        var e = assertThrows(TesseraException.class, () -> Tessera.elaborate(Loop::new));
        assertTrue(e.getMessage().contains("combinational loop"), e.getMessage());
    }

    static class Child extends Module {
        final UInt in = input("in", UInt.w(4));
        final UInt out = output("out", UInt.w(4));
        Child() { out.set(in); }
    }

    static class Unconnected extends Module {
        Unconnected() {
            Child c = instance(Child::new);
            output("y", UInt.w(4)).set(c.out);
        }
    }

    @Test
    void unconnectedChildInputIsAnError() {
        var e = assertThrows(TesseraException.class, () -> Tessera.elaborate(Unconnected::new));
        assertTrue(e.getMessage().contains("input 'in' of instance"), e.getMessage());
    }

    static class SignMix extends Module {
        SignMix() {
            SInt a = input("a", SInt.w(8));
            output("y", UInt.w(8)).set(a.asUInt());
            output("z", UInt.w(9)).set(a.asUInt().zext(9));
            UInt bad = output("bad", UInt.w(8));
            bad.set(UInt.lit(0, 8));
            output("w", SInt.w(8)).set(a);
        }
    }

    @Test
    void explicitConversionsAreAccepted() {
        Tessera.elaborate(SignMix::new);
    }

    static class NewInsteadOfInstance extends Module {
        NewInsteadOfInstance() {
            new Child();
        }
    }

    @Test
    void modulesMustBeCreatedThroughInstance() {
        var e = assertThrows(TesseraException.class, () -> Tessera.elaborate(NewInsteadOfInstance::new));
        assertTrue(e.getMessage().contains("instance(...)"), e.getMessage());
    }

    static class Naming extends Module {
        final UInt counter = reg(UInt.w(4), 0);
        final Child child = instance(Child::new);
        Naming() {
            child.in.set(counter);
            counter.set(counter.addWrap(1));
            output("y", UInt.w(4)).set(child.out);
        }
    }

    @Test
    void fieldsNameSignalsAndInstances() {
        var c = Tessera.elaborate(Naming::new).circuit();
        assertTrue(c.top().signals().stream().anyMatch(s -> s.name().equals("counter")));
        assertEquals("child", c.top().instances().getFirst().name());
    }
}
