#!/usr/bin/env python3
"""Writes the error corpus: errors/<id>/{case.properties, Design.java, design.sv, syntax/Design.java}.
Each case holds one seeded mistake, on the line marked // MISTAKE."""
from pathlib import Path
import textwrap

HERE = Path(__file__).resolve().parent / "errors"
IMPORTS = """import org.eninehq.tessera.core.*;
import org.eninehq.tessera.core.Module;
import static org.eninehq.tessera.core.Hw.*;
import org.eninehq.tessera.pipeline.*;
"""

cases = []
def case(cid, category, what, java=None, sv=None, syntax=None):
    cases.append((cid, category, what, java, sv, syntax))

def mod(body, extra=""):
    return IMPORTS + extra + "\npublic class Design extends Module {\n    public Design() {\n" + textwrap.indent(textwrap.dedent(body).strip("\n"), " " * 8) + "\n    }\n}\n"

# ------------------------------------------------------------------ widths and types
case("E01", "width", "sum of two 8-bit values assigned to an 8-bit output (carry lost)",
     mod("""
        UInt a = input("a", UInt.w(8)), b = input("b", UInt.w(8));
        UInt y = output("y", UInt.w(8));
        y.set(a.add(b)); // MISTAKE
     """),
     """module dut(input logic [7:0] a, b, output logic [7:0] y);
  assign y = a + b; // MISTAKE
endmodule
""")
case("E02", "width", "bitwise AND of an 8-bit and a 4-bit value",
     mod("""
        UInt a = input("a", UInt.w(8)), c = input("c", UInt.w(4));
        UInt y = output("y", UInt.w(8));
        y.set(a.and(c)); // MISTAKE
     """),
     """module dut(input logic [7:0] a, input logic [3:0] c, output logic [7:0] y);
  assign y = a & c; // MISTAKE
endmodule
""")
case("E03", "width", "slice beyond the top bit",
     mod("""
        UInt a = input("a", UInt.w(8));
        UInt y = output("y", UInt.w(8));
        y.set(a.bits(9, 2)); // MISTAKE
     """),
     """module dut(input logic [7:0] a, output logic [7:0] y);
  assign y = a[9:2]; // MISTAKE
endmodule
""")
case("E04", "width", "literal 300 in an 8-bit constant",
     mod("""
        UInt y = output("y", UInt.w(8));
        y.set(UInt.lit(300, 8)); // MISTAKE
     """),
     """module dut(output logic [7:0] y);
  assign y = 8'd300; // MISTAKE
endmodule
""")
case("E05", "width", "sign extension to fewer bits than the value has",
     mod("""
        SInt s = input("s", SInt.w(8));
        SInt y = output("y", SInt.w(4));
        y.set(s.sext(4)); // MISTAKE
     """))
case("E06", "width", "memory of 16 words addressed with 8 bits",
     mod("""
        UInt addr = input("addr", UInt.w(8));
        UInt y = output("y", UInt.w(8));
        Mem<UInt> m = mem("m", UInt.w(8), 16);
        y.set(m.read(addr)); // MISTAKE
     """),
     """module dut(input logic clk, input logic [7:0] addr, output logic [7:0] y);
  logic [7:0] m [0:15];
  assign y = m[addr]; // MISTAKE
endmodule
""")
case("E07", "width", "8-bit value written into a 16-bit raw Bits wire",
     mod("""
        UInt a = input("a", UInt.w(8));
        Bits b = wire("b", Bits.w(16));
        b.set(a.asBits()); // MISTAKE
        output("y", UInt.w(16)).set(b.asUInt());
     """))
case("E08", "type", "signed and unsigned values compared",
     mod("""
        SInt s = input("s", SInt.w(8));
        UInt u = input("u", UInt.w(8));
        Bool y = output("y", Bool.TYPE);
        y.set(s.lt(u)); // MISTAKE
     """),
     """module dut(input logic signed [7:0] s, input logic [7:0] u, output logic y);
  assign y = s < u; // MISTAKE
endmodule
""")
case("E09", "type", "signed and unsigned values in one multiplexer",
     mod("""
        Bool c = input("c", Bool.TYPE);
        SInt s = input("s", SInt.w(8));
        UInt u = input("u", UInt.w(8));
        Data y = mux(c, (Data) s, (Data) u); // MISTAKE
        output("y", UInt.w(8)).set(u);
     """),
     """module dut(input logic c, input logic signed [7:0] s, input logic [7:0] u, output logic [7:0] y);
  assign y = c ? s : u; // MISTAKE
endmodule
""")
case("E10", "type", "values of two different enum types compared",
     mod("""
        HwEnum<Light> a = input("a", HwEnum.of(Light.class));
        HwEnum<Door> b = input("b", HwEnum.of(Door.class));
        output("y", Bool.TYPE).set(a.eq(b)); // MISTAKE
     """, extra="enum Light { RED, GREEN }\nenum Door { OPEN, SHUT }\n"),
     """typedef enum logic { RED, GREEN } light_t;
typedef enum logic { OPEN, SHUT } door_t;
module dut(input light_t a, input door_t b, output light_t y);
  assign y = b; // MISTAKE
endmodule
""")
case("E11", "type", "match over a tagged union misses a variant",
     IMPORTS + """
sealed interface Op permits Op.Add, Op.Neg, Op.Nop {
    record Add(UInt a, UInt b) implements Op {}
    record Neg(UInt a) implements Op {}
    record Nop() implements Op {}
}

public class Design extends Module {
    public Design() {
        var t = HwUnion.of(Op.class, HwUnion.variant(Op.Add.class, UInt.w(8), UInt.w(8)),
                HwUnion.variant(Op.Neg.class, UInt.w(8)), HwUnion.variant(Op.Nop.class));
        HwUnion<Op> op = input("op", t);
        UInt y = output("y", UInt.w(8));
        y.set(op.match(o -> switch (o) { // MISTAKE
            case Op.Add a -> a.a().addWrap(a.b());
            case Op.Neg n -> UInt.lit(0, 8).subWrap(n.a());
        }));
    }
}
""",
     """module dut(input logic [1:0] op, input logic [7:0] a, b, output logic [7:0] y);
  always_comb
    case (op) // MISTAKE
      2'd0: y = a + b;
      2'd1: y = -a;
    endcase
endmodule
""")
case("E12", "type", "constant index past the end of a Vec",
     mod("""
        Vec<UInt> v = input("v", Vec.of(4, UInt.w(8)));
        output("y", UInt.w(8)).set(v.get(5)); // MISTAKE
     """),
     """module dut(input logic [7:0] v [4], output logic [7:0] y);
  assign y = v[5]; // MISTAKE
endmodule
""")
# ------------------------------------------------------------------ driving
case("E20", "driving", "output never assigned",
     mod("""
        UInt a = input("a", UInt.w(8));
        UInt y = output("y", UInt.w(8)); // MISTAKE
     """),
     """module dut(input logic [7:0] a, output logic [7:0] y); // MISTAKE
endmodule
""")
case("E21", "driving", "wire assigned on one branch only (a latch)",
     mod("""
        Bool c = input("c", Bool.TYPE);
        UInt a = input("a", UInt.w(8));
        UInt w = wire("w", UInt.w(8));
        when(c, () -> w.set(a)); // MISTAKE
        output("y", UInt.w(8)).set(w);
     """),
     """module dut(input logic c, input logic [7:0] a, output logic [7:0] y);
  always_comb if (c) y = a; // MISTAKE
endmodule
""")
case("E22", "driving", "combinational loop",
     mod("""
        UInt w = wire("w", UInt.w(8));
        w.set(w.addWrap(1)); // MISTAKE
        output("y", UInt.w(8)).set(w);
     """),
     """module dut(output logic [7:0] y);
  logic [7:0] w;
  assign w = w + 8'd1; // MISTAKE
  assign y = w;
endmodule
""")
case("E23", "driving", "output assigned twice",
     mod("""
        UInt a = input("a", UInt.w(8)), b = input("b", UInt.w(8));
        UInt y = output("y", UInt.w(8));
        y.set(a);
        y.set(b); // MISTAKE
     """),
     """module dut(input logic [7:0] a, b, output logic [7:0] y);
  assign y = a;
  assign y = b; // MISTAKE
endmodule
""")
case("E24", "driving", "a value of the parent used inside a child module",
     IMPORTS + """
class Child extends Module {
    Child(UInt fromParent) {
        output("y", UInt.w(8)).set(fromParent); // MISTAKE
    }
}

public class Design extends Module {
    public Design() {
        UInt a = input("a", UInt.w(8));
        Child c = instance("c", () -> new Child(a));
        output("y", UInt.w(8)).set(UInt.lit(0, 8));
    }
}
""")
case("E25", "driving", "register reset to a non-constant value",
     mod("""
        UInt a = input("a", UInt.w(8));
        UInt r = regInit("r", a); // MISTAKE
        r.set(r.addWrap(1));
        output("y", UInt.w(8)).set(r);
     """))
case("E26", "driving", "otherwise() not attached to a when",
     mod("""
        Bool c = input("c", Bool.TYPE);
        UInt y = output("y", UInt.w(8));
        var w = when(c, () -> y.set(1));
        y.set(2);
        w.otherwise(() -> y.set(3)); // MISTAKE
     """))
case("E27", "driving", "module created with new instead of instance()",
     IMPORTS + """
class Child extends Module {
    Child() { output("y", UInt.w(8)).set(UInt.lit(1, 8)); }
}

public class Design extends Module {
    public Design() {
        Child c = new Child(); // MISTAKE
        output("y", UInt.w(8)).set(UInt.lit(0, 8));
    }
}
""")
# ------------------------------------------------------------------ pipelines
SUB = """
@Latency(2)
class Mul2 extends Module {
    final UInt a = input("a", UInt.w(8)), b = input("b", UInt.w(8));
    final UInt out = output("out", UInt.w(16));
    Mul2() {
        var p = Pipeline.begin("m");
        UInt prod = p.input(a).mul(p.input(b));
        p.stage();
        p.stage();
        out.set(prod);
        p.end();
    }
}
"""
case("E30", "pipeline", "sub-pipeline result used one stage too early",
     IMPORTS + SUB + """
public class Design extends Module {
    public Design() {
        UInt x = input("x", UInt.w(8));
        UInt y = output("y", UInt.w(17));
        var p = Pipeline.begin("outer");
        UInt xs = p.input(x);
        Mul2 m = p.inst(2, Mul2::new);
        m.a.set(xs);
        m.b.set(xs);
        p.stage();
        y.set(m.out.add(xs)); // MISTAKE
        p.end();
    }
}
""",
     """module mul2(input logic clk, input logic [7:0] a, b, output logic [15:0] out);
  logic [15:0] p1, p2;
  always_ff @(posedge clk) begin p1 <= a * b; p2 <= p1; end
  assign out = p2;
endmodule
module dut(input logic clk, input logic [7:0] x, output logic [16:0] y);
  logic [7:0] x1;
  logic [15:0] m;
  mul2 u(.clk(clk), .a(x), .b(x), .out(m));
  always_ff @(posedge clk) x1 <= x;
  assign y = {1'b0, m} + {9'd0, x1}; // MISTAKE
endmodule
""")
case("E31", "pipeline", "sub-pipeline declared with the wrong latency",
     IMPORTS + SUB + """
public class Design extends Module {
    public Design() {
        UInt x = input("x", UInt.w(8));
        UInt y = output("y", UInt.w(16));
        var p = Pipeline.begin("outer");
        UInt xs = p.input(x);
        Mul2 m = p.inst(3, Mul2::new); // MISTAKE
        m.a.set(xs);
        m.b.set(xs);
        p.stage();
        p.stage();
        p.stage();
        y.set(m.out);
        p.end();
    }
}
""")
case("E32", "pipeline", "late value never set",
     mod("""
        UInt x = input("x", UInt.w(8));
        var p = Pipeline.begin("p");
        Late<UInt> fwd = p.late(UInt.w(8)); // MISTAKE
        UInt a = p.input(x).addWrap(fwd.get());
        p.stage();
        output("y", UInt.w(8)).set(a);
        p.end();
     """))
case("E33", "pipeline", "stage boundary inside a when block",
     mod("""
        Bool c = input("c", Bool.TYPE);
        UInt x = input("x", UInt.w(8));
        var p = Pipeline.begin("p");
        UInt a = p.input(x);
        when(c, () -> p.stage()); // MISTAKE
        output("y", UInt.w(8)).set(a);
        p.end();
     """))
case("E34", "pipeline", "pipeline value used after the pipeline ended",
     mod("""
        UInt x = input("x", UInt.w(8));
        var p = Pipeline.begin("p");
        UInt a = p.input(x).addWrap(1);
        p.stage();
        UInt b = a.addWrap(1);
        p.end();
        output("y", UInt.w(8)).set(b.addWrap(1)); // MISTAKE
     """))
case("E35", "pipeline", "value tagged @Stage(1) used in stage 0",
     IMPORTS + """
public class Design extends Module {
    public Design() {
        UInt x = input("x", UInt.w(8));
        var p = Pipeline.begin("p");
        UInt a = p.input(x);
        @Stage(1) UInt late = a.addWrap(1);
        UInt early = late.addWrap(1); // MISTAKE
        p.stage();
        output("y", UInt.w(8)).set(early);
        p.end();
    }
}
""")
# ------------------------------------------------------------------ clocks and resets
case("E40", "clock", "register of one clock samples a register of another, unsynchronised",
     mod("""
        ClockDomain fast = clockDomain("fast");
        UInt a = withClock(fast, () -> reg("a", UInt.w(8), 0));
        a.set(a.addWrap(1));
        UInt b = reg("b", UInt.w(8), 0);
        b.set(a); // MISTAKE
        output("y", UInt.w(8)).set(b);
     """),
     """module dut(input logic clk, input logic fast_clk, output logic [7:0] y);
  logic [7:0] a, b;
  always_ff @(posedge fast_clk) a <= a + 8'd1;
  always_ff @(posedge clk) b <= a; // MISTAKE
  assign y = b;
endmodule
""")
case("E41", "clock", "multi-bit value synchronised bit by bit (bits can arrive in different cycles)",
     mod("""
        ClockDomain fast = clockDomain("fast");
        UInt a = withClock(fast, () -> reg("a", UInt.w(4), 0));
        a.set(a.addWrap(1));
        Bool b0 = synchronize(a.bit(0), 2), b1 = synchronize(a.bit(1), 2); // MISTAKE
        output("y", UInt.w(2)).set(cat(b1, b0));
     """),
     """module dut(input logic clk, input logic fast_clk, output logic [1:0] y);
  logic [3:0] a;
  logic [1:0] s1, s2;
  always_ff @(posedge fast_clk) a <= a + 4'd1;
  always_ff @(posedge clk) begin s1 <= a[1:0]; s2 <= s1; end // MISTAKE
  assign y = s2;
endmodule
""")
case("E42", "clock", "synchroniser with a single flip-flop",
     mod("""
        ClockDomain fast = clockDomain("fast");
        Bool a = withClock(fast, () -> reg("a", Bool.TYPE, 0));
        a.set(a.not());
        output("y", Bool.TYPE).set(synchronize(a, 1)); // MISTAKE
     """),
     """module dut(input logic clk, input logic fast_clk, output logic y);
  logic a, s1;
  always_ff @(posedge fast_clk) a <= ~a;
  always_ff @(posedge clk) s1 <= a; // MISTAKE
  assign y = s1;
endmodule
""")
case("E43", "clock", "reset kind changed after registers exist",
     mod("""
        UInt r = reg("r", UInt.w(8), 0);
        r.set(r.addWrap(1));
        useReset(org.eninehq.tessera.ir.ClockDomainDef.ResetKind.ASYNC_HIGH); // MISTAKE
        output("y", UInt.w(8)).set(r);
     """))
# ------------------------------------------------------------------ functions
case("E50", "purity", "@Fn function creates a register",
     IMPORTS + """
public class Design extends Module {
    @Fn
    static UInt next(UInt x) {
        UInt r = Hw.register("r", UInt.w(8), 0L); // MISTAKE
        r.set(x);
        return r;
    }

    public Design() {
        UInt a = input("a", UInt.w(8));
        output("y", UInt.w(8)).set(next(a));
    }
}
""")
case("E51", "purity", "combinational function (Hw.fn) creates a register",
     mod("""
        UInt a = input("a", UInt.w(8));
        UInt y = output("y", UInt.w(8));
        y.set(fn(() -> { UInt r = Hw.register("r", UInt.w(8), 0L); r.set(a); return r; })); // MISTAKE
     """))
# ------------------------------------------------------------------ logic errors no checker sees
case("E70", "logic", "counter wraps at 10 instead of 9 (a decade counter that counts 0..10)",
     mod("""
        UInt r = reg("r", UInt.w(4), 0);
        r.set(mux(r.eq(10), UInt.lit(0, 4), r.addWrap(1))); // MISTAKE
        output("y", UInt.w(4)).set(r);
     """),
     """module dut(input logic clk, input logic reset, output logic [3:0] y);
  logic [3:0] r;
  always_ff @(posedge clk) r <= reset ? 4'd0 : (r == 4'd10 ? 4'd0 : r + 4'd1); // MISTAKE
  assign y = r;
endmodule
""")
# ------------------------------------------------------------------ unsigned types (the Tessera javac)
def syn(body):
    return "public class Design {\n    public static void main(String[] args) {\n" + textwrap.indent(textwrap.dedent(body).strip("\n"), " " * 8) + "\n    }\n}\n"

case("E60", "unsigned", "signed int assigned to a uint", syntax=syn("""
        int i = -1;
        uint u = i; // MISTAKE
        System.out.println(u);
     """), sv="""module dut(input int i, output logic [31:0] u);
  assign u = i; // MISTAKE
endmodule
""")
case("E61", "unsigned", "uint compared with a signed int", syntax=syn("""
        uint u = 5u;
        int i = -3;
        boolean b = u < i; // MISTAKE
        System.out.println(b);
     """), sv="""module dut(input logic [31:0] u, input int i, output logic b);
  assign b = u < i; // MISTAKE
endmodule
""")
case("E62", "unsigned", "constant 300 in a uint<8>", syntax=syn("""
        uint<8> x = 300; // MISTAKE
        System.out.println(x);
     """), sv="""module dut(output logic [7:0] x);
  assign x = 300; // MISTAKE
endmodule
""")
case("E63", "unsigned", "uint assigned to a signed int", syntax=syn("""
        uint u = 0xFFFF_FFFFu;
        int x = u; // MISTAKE
        System.out.println(x);
     """), sv="""module dut(input logic [31:0] u, output int x);
  assign x = u; // MISTAKE
endmodule
""")
case("E64", "unsigned", "arithmetic on a raw bits<8> value", syntax=syn("""
        bits<8> b = 0x0F;
        var c = b + 1; // MISTAKE
        System.out.println(c);
     """), sv="""module dut(input logic [7:0] b, output logic [7:0] c);
  assign c = b + 8'd1; // MISTAKE
endmodule
""")
case("E65", "unsigned", "uint passed where a signed int parameter is expected", syntax="""public class Design {
    static int twice(int x) { return 2 * x; }

    public static void main(String[] args) {
        uint u = 7u;
        System.out.println(twice(u)); // MISTAKE
    }
}
""")

# ------------------------------------------------------------------ Chisel versions (Chisel 6.5, firtool 1.62)
CH = {}
def chisel(cid, body, extra=""):
    CH[cid] = "import chisel3._\nimport chisel3.util._\n" + extra + "\nclass Design extends Module {\n" + textwrap.indent(textwrap.dedent(body).strip("\n"), "  ") + "\n}\n"

chisel("E01", """
    val a = IO(Input(UInt(8.W))); val b = IO(Input(UInt(8.W)))
    val y = IO(Output(UInt(8.W)))
    y := a +& b // MISTAKE
""")
chisel("E02", """
    val a = IO(Input(UInt(8.W))); val c = IO(Input(UInt(4.W)))
    val y = IO(Output(UInt(8.W)))
    y := a & c // MISTAKE
""")
chisel("E03", """
    val a = IO(Input(UInt(8.W)))
    val y = IO(Output(UInt(8.W)))
    y := a(9, 2) // MISTAKE
""")
chisel("E04", """
    val y = IO(Output(UInt(8.W)))
    y := 300.U(8.W) // MISTAKE
""")
chisel("E06", """
    val addr = IO(Input(UInt(8.W)))
    val y = IO(Output(UInt(8.W)))
    val m = Mem(16, UInt(8.W))
    y := m(addr) // MISTAKE
""")
chisel("E08", """
    val s = IO(Input(SInt(8.W))); val u = IO(Input(UInt(8.W)))
    val y = IO(Output(Bool()))
    y := s < u // MISTAKE
""")
chisel("E09", """
    val c = IO(Input(Bool())); val s = IO(Input(SInt(8.W))); val u = IO(Input(UInt(8.W)))
    val y = IO(Output(UInt(8.W)))
    y := Mux(c, s, u) // MISTAKE
""")
chisel("E10", """
    val a = IO(Input(Light())); val b = IO(Input(Door()))
    val y = IO(Output(Bool()))
    y := a === b // MISTAKE
""", extra="object Light extends ChiselEnum { val Red, Green = Value }\nobject Door extends ChiselEnum { val Open, Shut = Value }\n")
chisel("E11", """
    val op = IO(Input(UInt(2.W))); val a = IO(Input(UInt(8.W))); val b = IO(Input(UInt(8.W)))
    val y = IO(Output(UInt(8.W)))
    switch(op) { // MISTAKE
      is(0.U) { y := a + b }
      is(1.U) { y := 0.U - a }
    }
""")
chisel("E12", """
    val v = IO(Input(Vec(4, UInt(8.W))))
    val y = IO(Output(UInt(8.W)))
    y := v(5) // MISTAKE
""")
chisel("E20", """
    val a = IO(Input(UInt(8.W)))
    val y = IO(Output(UInt(8.W))) // MISTAKE
""")
chisel("E21", """
    val c = IO(Input(Bool())); val a = IO(Input(UInt(8.W)))
    val y = IO(Output(UInt(8.W)))
    val w = Wire(UInt(8.W))
    when(c) { w := a } // MISTAKE
    y := w
""")
chisel("E22", """
    val y = IO(Output(UInt(8.W)))
    val w = Wire(UInt(8.W))
    w := w + 1.U // MISTAKE
    y := w
""")
chisel("E23", """
    val a = IO(Input(UInt(8.W))); val b = IO(Input(UInt(8.W)))
    val y = IO(Output(UInt(8.W)))
    y := a
    y := b // MISTAKE
""")
chisel("E24", """
    val a = IO(Input(UInt(8.W)))
    val y = IO(Output(UInt(8.W)))
    val c = Module(new Child(a))
    y := 0.U
""", extra="class Child(fromParent: UInt) extends Module {\n  val y = IO(Output(UInt(8.W)))\n  y := fromParent // MISTAKE\n}\n")
chisel("E30", """
    val x = IO(Input(UInt(8.W)))
    val y = IO(Output(UInt(17.W)))
    val m = RegNext(RegNext(x * x))
    val x1 = RegNext(x)
    y := m +& x1 // MISTAKE
""")
chisel("E40", """
    val fastClock = IO(Input(Clock()))
    val y = IO(Output(UInt(8.W)))
    val a = withClock(fastClock) { RegInit(0.U(8.W)) }
    a := a + 1.U
    val b = RegNext(a) // MISTAKE
    y := b
""")
chisel("E41", """
    val fastClock = IO(Input(Clock()))
    val y = IO(Output(UInt(2.W)))
    val a = withClock(fastClock) { RegInit(0.U(4.W)) }
    a := a + 1.U
    y := RegNext(RegNext(a(1, 0))) // MISTAKE
""")
chisel("E42", """
    val fastClock = IO(Input(Clock()))
    val y = IO(Output(Bool()))
    val a = withClock(fastClock) { RegInit(false.B) }
    a := !a
    y := RegNext(a) // MISTAKE
""")
chisel("E60", """
    val s = IO(Input(SInt(32.W)))
    val u = IO(Output(UInt(32.W)))
    u := s // MISTAKE
""")
chisel("E61", """
    val u = IO(Input(UInt(32.W))); val i = IO(Input(SInt(32.W)))
    val b = IO(Output(Bool()))
    b := u < i // MISTAKE
""")
chisel("E62", """
    val x = IO(Output(UInt(8.W)))
    x := 300.U // MISTAKE
""")
chisel("E63", """
    val u = IO(Input(UInt(32.W)))
    val x = IO(Output(SInt(32.W)))
    x := u // MISTAKE
""")
chisel("E70", """
    val y = IO(Output(UInt(4.W)))
    val r = RegInit(0.U(4.W))
    r := Mux(r === 10.U, 0.U, r + 1.U) // MISTAKE
    y := r
""")

def main():
    for cid, cat, what, java, sv, syntax in cases:
        d = HERE / cid
        d.mkdir(parents=True, exist_ok=True)
        (d / "case.properties").write_text(f"id={cid}\ncategory={cat}\nwhat={what}\n")
        if java: (d / "Design.java").write_text(java)
        if sv: (d / "design.sv").write_text(sv)
        if cid in CH: (d / "Design.scala").write_text(CH[cid])
        if syntax:
            (d / "syntax").mkdir(exist_ok=True)
            (d / "syntax" / "Design.java").write_text(syntax)
    print(len(cases), "cases")

main()
