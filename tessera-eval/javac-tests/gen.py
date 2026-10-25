#!/usr/bin/env python3
"""Tests for the Tessera javac. pos/<id>.java must compile and print the EXPECT lines when
run on a stock JDK; neg/<id>.java must fail to compile with an error containing ERROR, on the
line marked // HERE."""
from pathlib import Path
import re, textwrap
HERE = Path(__file__).resolve().parent
P, N = [], []

def pos(i, what, body, expect, extra=""):
    P.append((i, what, body, expect, extra))

def neg(i, what, body, error, extra=""):
    N.append((i, what, body, error, extra))

# ---------------------------------------------------------------- unsigned semantics
pos("P01", "uint maximum prints unsigned", "uint u = 0xFFFF_FFFFu;\nSystem.out.println(u);", ["4294967295"])
pos("P02", "uint division is unsigned", "uint u = 0xFFFF_FFFEu;\nSystem.out.println(u / 2u);", ["2147483647"])
pos("P03", "uint remainder is unsigned", "uint u = 0xFFFF_FFFFu;\nSystem.out.println(u % 10u);", ["5"])
pos("P04", "uint comparison is unsigned", "uint a = 0x8000_0000u, b = 1u;\nSystem.out.println(a > b);\nSystem.out.println(a <= b);", ["true", "false"])
pos("P05", "uint >> is a logical shift", "uint u = 0x8000_0000u;\nSystem.out.println(u >> 31);", ["1"])
pos("P06", "ulong maximum prints unsigned", "ulong u = 18446744073709551615uL;\nSystem.out.println(u);", ["18446744073709551615"])
pos("P07", "ulong division is unsigned", "ulong u = 0xFFFF_FFFF_FFFF_FFFFuL;\nSystem.out.println(u / 3uL);", ["6148914691236517205"])
pos("P08", "ulong comparison is unsigned", "ulong a = 0x8000_0000_0000_0000uL;\nSystem.out.println(a > 1uL);", ["true"])
pos("P09", "ushort wraps at 2^16", "ushort s = (ushort) 65535;\ns++;\nSystem.out.println(s);", ["0"])
pos("P10", "ubyte arithmetic wraps at 2^8", "ubyte b = (ubyte) 200;\nb += (ubyte) 100;\nSystem.out.println(b);", ["44"])
pos("P11", "uint<12> increments wrap", "uint<12> x = 4095;\nx++;\nSystem.out.println(x);", ["0"])
pos("P12", "uint<12> addition wraps", "uint<12> a = 4000;\nuint<12> b = 200;\nuint<12> c = a + b;\nSystem.out.println(c);", ["104"])
pos("P13", "uint<5> shifts mask to 5 bits", "uint<5> x = 1;\nx <<= 4;\nSystem.out.println(x);\nx <<= 1;\nSystem.out.println(x);", ["16", "0"])
pos("P14", "uint<N> multiply masks", "uint<8> a = 200;\nuint<8> b = 3;\nuint<8> c = a * b;\nSystem.out.println(c);", ["88"])
pos("P15", "uint<N> bitwise not masks", "uint<4> a = 5;\nuint<4> b = ~a;\nSystem.out.println(b);", ["10"])
pos("P16", "uint<N> subtraction wraps", "uint<6> a = 1;\nuint<6> b = 3;\nuint<6> c = a - b;\nSystem.out.println(c);", ["62"])
# ---------------------------------------------------------------- slices, bits, concatenation
pos("P20", "slice gives the field", "uint u = 0xABCD_1234u;\nSystem.out.println(u[31:28]);\nSystem.out.println(u[15:0]);", ["10", "4660"])
pos("P21", "bit select is a boolean", "uint u = 0b1010u;\nSystem.out.println(u[1]);\nSystem.out.println(u[0]);", ["true", "false"])
pos("P22", "concatenation puts the first operand on top", "uint<4> h = 0xA;\nuint<4> l = 0x5;\nuint<8> b = {h, l};\nSystem.out.println(b);", ["165"])
pos("P23", "concatenation of slices", "uint u = 0x1234_5678u;\nuint<16> s = {u[7:0], u[31:24]};\nSystem.out.println(s);", ["30738"])
pos("P24", "bits<N> bitwise operations", "bits<8> a = 0xF0;\nbits<8> b = 0x3C;\nbits<8> c = a & b;\nSystem.out.println((uint<8>) c);\nSystem.out.println((uint<8>) (a ^ b));", ["48", "204"])
pos("P25", "bits<N> equality", "bits<8> a = 0x0F;\nbits<8> b = 0x0F;\nSystem.out.println(a == b);", ["true"])
pos("P26", "slice of a long-backed value", "ulong u = 0xDEAD_BEEF_0000_0000uL;\nSystem.out.println(u[63:32]);", ["3735928559"])
# ---------------------------------------------------------------- conversions
pos("P30", "explicit cast int to uint", "int i = -1;\nuint u = (uint) i;\nSystem.out.println(u);", ["4294967295"])
pos("P31", "explicit cast uint to int", "uint u = 0xFFFF_FFFFu;\nint i = (int) u;\nSystem.out.println(i);", ["-1"])
pos("P32", "uint widens to long with zero extension", "uint u = 0xFFFF_FFFFu;\nlong l = u;\nSystem.out.println(l);", ["4294967295"])
pos("P33", "constants that fit are accepted", "uint x = 5;\nuint<8> y = 255;\nSystem.out.println(x + 1u);\nSystem.out.println(y);", ["6", "255"])
pos("P34", "uint in arrays", "uint[] a = {1u, 0xFFFF_FFFFu};\na[1]++;\nSystem.out.println(a[1]);\nSystem.out.println(a[0] + a[1]);", ["0", "1"])
pos("P35", "uint parameters and results", "System.out.println(twice(0x8000_0001u));", ["2"], extra="static uint twice(uint x) { return x + x; }")
pos("P36", "compound assignment below zero", "uint u = 0u;\nu -= 1u;\nSystem.out.println(u);", ["4294967295"])
pos("P37", "uint loop counter", "for (uint i = 0u; i < 3u; i++) System.out.println(i);", ["0", "1", "2"])
pos("P38", "string concatenation shows the unsigned value", "uint u = 0xFFFF_FFFFu;\nSystem.out.println(\"u=\" + u);", ["u=4294967295"])
# ---------------------------------------------------------------- against the JDK's unsigned methods
pos("P40", "comparison agrees with Integer.compareUnsigned",
    """var r = new java.util.Random(1);
boolean ok = true;
for (int k = 0; k < 100000; k++) {
    int a = r.nextInt(), b = r.nextInt();
    uint ua = (uint) a, ub = (uint) b;
    ok &= (ua < ub) == (Integer.compareUnsigned(a, b) < 0);
}
System.out.println(ok);""", ["true"])
pos("P41", "division agrees with Integer.divideUnsigned",
    """var r = new java.util.Random(2);
boolean ok = true;
for (int k = 0; k < 100000; k++) {
    int a = r.nextInt(), b = r.nextInt() | 1;
    ok &= (int) ((uint) a / (uint) b) == Integer.divideUnsigned(a, b);
    ok &= (int) ((uint) a % (uint) b) == Integer.remainderUnsigned(a, b);
}
System.out.println(ok);""", ["true"])
pos("P42", "ulong printing agrees with Long.toUnsignedString",
    """var r = new java.util.Random(3);
boolean ok = true;
for (int k = 0; k < 100000; k++) {
    long a = r.nextLong();
    ok &= ("" + (ulong) a).equals(Long.toUnsignedString(a));
}
System.out.println(ok);""", ["true"])
pos("P44", "ulong to double is unsigned", "ulong u = 0xFFFF_FFFF_FFFF_FFFFuL;\ndouble d = (double) u;\nSystem.out.println(d > 1.8e19);\nlong l = (long) u;\nSystem.out.println(l);", ["true", "-1"])
pos("P43", "ulong division agrees with Long.divideUnsigned",
    """var r = new java.util.Random(4);
boolean ok = true;
for (int k = 0; k < 100000; k++) {
    long a = r.nextLong(), b = r.nextLong() | 1;
    ok &= (long) ((ulong) a / (ulong) b) == Long.divideUnsigned(a, b);
}
System.out.println(ok);""", ["true"])
# ---------------------------------------------------------------- operators, trailing blocks
OPCLS = """static final class V {
    final int x;
    V(int x) { this.x = x; }
    @Operator("+") V plus(V o) { return new V(x + o.x); }
    @Operator("~") V inv() { return new V(-x); }
    @Operator("[]") boolean bit(int i) { return ((x >> i) & 1) != 0; }
    @Operator("[:]") int field(int hi, int lo) { return (x >> lo) & ((1 << (hi - lo + 1)) - 1); }
    public String toString() { return "V" + x; }
}
@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.CLASS)
@interface Operator { String value(); }"""
pos("P50", "operators on a class with @Operator", "V a = new V(2), b = new V(3);\nSystem.out.println(a + b);\nSystem.out.println(~a);", ["V5", "V-2"], extra=OPCLS)
pos("P51", "bit select and slice operators on a class", "V a = new V(0b1100);\nSystem.out.println(a[2]);\nSystem.out.println(a[3:2]);", ["true", "3"], extra=OPCLS)
TB = """static Chain when(boolean c, Runnable r) { if (c) r.run(); return new Chain(c); }
record Chain(boolean done) {
    Chain elseWhen(boolean c, Runnable r) { if (!done && c) r.run(); return new Chain(done || c); }
    void otherwise(Runnable r) { if (!done) r.run(); }
}"""
pos("P52", "trailing blocks: when/elseWhen/otherwise", "int v = 2;\nwhen (v == 1) {\n    System.out.println(\"one\");\n}.elseWhen (v == 2) {\n    System.out.println(\"two\");\n}.otherwise {\n    System.out.println(\"other\");\n}", ["two"], extra=TB)
pos("P53", "a trailing block captures locals", "int[] n = {0};\nwhen (true) {\n    n[0] = 7;\n}\nSystem.out.println(n[0]);", ["7"], extra=TB)

# ---------------------------------------------------------------- rejected programs
neg("N01", "signed int into uint", "int i = 3;\nuint u = i; // HERE", "cannot convert signed int to uint")
neg("N02", "uint into signed int", "uint u = 3u;\nint i = u; // HERE", "cannot convert uint to signed int")
neg("N03", "uint plus int", "uint u = 3u;\nint i = 1;\nvar s = u + i; // HERE", "cannot mix signed int and uint")
neg("N04", "uint compared with int", "uint u = 3u;\nint i = -1;\nboolean b = u < i; // HERE", "cannot mix")
neg("N05", "int argument for a uint parameter", "int i = 3;\nf(i); // HERE", "cannot convert", extra="static void f(uint x) {}")
neg("N06", "boxing a uint", "uint u = 3u;\nObject o = u; // HERE", "")
neg("N07", "uint as a type argument", "java.util.List<uint> l = null; // HERE", "")
neg("N08", "constant does not fit uint<8>", "uint<8> x = 300; // HERE", "")
neg("N09", "uint<65> has no representation", "uint<65> x = 1; // HERE", "")
neg("N10", "arithmetic on bits<N>", "bits<8> b = 1;\nvar c = b + 1; // HERE", "not defined for bit vectors")
neg("N11", "ordering on bits<N>", "bits<8> a = 1, b = 2;\nboolean c = a < b; // HERE", "")
neg("N12", "slice above the top bit", "uint u = 1u;\nvar s = u[32:0]; // HERE", "")
neg("N13", "slice with hi below lo", "uint u = 1u;\nvar s = u[3:7]; // HERE", "")
neg("N14", "negative constant for a uint", "uint u = -1; // HERE", "")
neg("N15", "long into ulong", "long l = 1;\nulong u = l; // HERE", "cannot convert")
neg("N16", "ulong into long", "ulong u = 1uL;\nlong l = u; // HERE", "cannot convert")
neg("N17", "uint equals int", "uint u = 1u;\nint i = 1;\nboolean b = u == i; // HERE", "")
neg("N18", "constant too large for uint", "uint u = 4294967296L; // HERE", "")
neg("N19", "concatenating an int of unknown width", "int i = 1;\nuint<4> h = 1;\nvar c = {h, i}; // HERE", "")
neg("N20", "unsigned value into a signed short", "ushort s = (ushort) 1;\nshort t = s; // HERE", "")
neg("N22", "a uint passed to println(int) (no implicit conversion to int)", "uint u = 1u;\nSystem.out.println(u); // HERE", "cannot convert uint to signed int")
neg("N23", "a uint quotient passed to println(int): the error is on its line", "uint u = 4u;\nSystem.out.println(u / 2u); // HERE", "cannot convert uint to signed int")
neg("N21", "uint returned as int", "System.out.println(g());", "cannot convert", extra="static int g() { uint u = 1u; return u; } // HERE")

def idiom(body):
    return re.sub(r'System\.out\.println\((?!"|ok\))(.*)\);', r'System.out.println("" + (\1));', body)

def write(dirname, items, kind):
    d = HERE / dirname
    d.mkdir(exist_ok=True)
    for f in d.glob("*.java"): f.unlink()
    for i, what, body, exp, extra in items:
        hdr = f"// {i}: {what}\n"
        hdr += "".join(f"// EXPECT: {e}\n" for e in exp) if kind == "pos" else f"// ERROR: {exp}\n"
        src = hdr + f"public class {i} {{\n"
        if extra: src += textwrap.indent(extra, "    ") + "\n\n"
        if kind == "pos" and i not in ("P50", "P51", "P52", "P53"): body = idiom(body)
        src += "    public static void main(String[] args) {\n" + textwrap.indent(body, " " * 8) + "\n    }\n}\n"
        (d / f"{i}.java").write_text(src)

write("pos", P, "pos")
write("neg", N, "neg")
print(len(P), "positive,", len(N), "negative")
