#!/usr/bin/env python3
"""The javac test suite (tessera-eval/javac-tests, for the Tessera javac) in stock-Java syntax,
for stock JDK 25 with -Xplugin:TesseraCheck (public API) and -Xplugin:TesseraSyntaxLite (internal API).

Same IDs as the original suite. Each original test is either ported (pos/<id>.java, neg/<id>.java) or listed
in NOT_PORTED with the reason. pos tests marked "// LOWERED" use operators whose Java meaning is signed; they
must compile and print EXPECT with the syntax-lite plugin, and be rejected by TesseraCheck alone. New tests
(no original) have ids from N30 up. Unsigned values use a local @Unsigned annotation (TYPE_USE), so every
file compiles on its own; the checker accepts any annotation named Unsigned."""
from pathlib import Path
import textwrap
HERE = Path(__file__).resolve().parent
P, N = [], []

def pos(i, what, body, expect, lowered, extra=""):
    P.append((i, what, body, expect, lowered, extra))

def neg(i, what, body, error, extra=""):
    N.append((i, what, body, error, extra))

# ---------------------------------------------------------------- unsigned semantics
pos("P01", "uint maximum prints unsigned", "@Unsigned int u = 0xFFFF_FFFF;\nSystem.out.println(\"\" + u);", ["4294967295"], True)
pos("P02", "uint division is unsigned", "@Unsigned int u = 0xFFFF_FFFE;\nSystem.out.println(\"\" + (u / 2));", ["2147483647"], True)
pos("P03", "uint remainder is unsigned", "@Unsigned int u = 0xFFFF_FFFF;\nSystem.out.println(\"\" + (u % 10));", ["5"], True)
pos("P04", "uint comparison is unsigned", "@Unsigned int a = 0x8000_0000, b = 1;\nSystem.out.println(\"\" + (a > b));\nSystem.out.println(\"\" + (a <= b));", ["true", "false"], True)
pos("P05", "uint >> is a logical shift", "@Unsigned int u = 0x8000_0000;\nSystem.out.println(\"\" + (u >> 31));", ["1"], True)
pos("P06", "ulong maximum prints unsigned", "@Unsigned long u = 0xFFFF_FFFF_FFFF_FFFFL;\nSystem.out.println(\"\" + u);", ["18446744073709551615"], True)
pos("P07", "ulong division is unsigned", "@Unsigned long u = 0xFFFF_FFFF_FFFF_FFFFL;\nSystem.out.println(\"\" + (u / 3L));", ["6148914691236517205"], True)
pos("P08", "ulong comparison is unsigned", "@Unsigned long a = 0x8000_0000_0000_0000L;\nSystem.out.println(\"\" + (a > 1L));", ["true"], True)
# ---------------------------------------------------------------- conversions
pos("P30", "explicit cast int to uint", "int i = -1;\n@Unsigned int u = (@Unsigned int) i;\nSystem.out.println(\"\" + u);", ["4294967295"], True)
pos("P31", "explicit cast uint to int", "@Unsigned int u = 0xFFFF_FFFF;\nint i = (int) u;\nSystem.out.println(\"\" + i);", ["-1"], False)
pos("P32", "uint widens to long with zero extension", "@Unsigned int u = 0xFFFF_FFFF;\nlong l = u;\nSystem.out.println(\"\" + l);", ["4294967295"], True)
pos("P33", "constants that fit are accepted", "@Unsigned int x = 5;\n@Unsigned int y = 255;\nSystem.out.println(\"\" + (x + 1));\nSystem.out.println(\"\" + y);", ["6", "255"], True)
pos("P34", "uint in arrays", "@Unsigned int[] a = {1, 0xFFFF_FFFF};\na[1]++;\nSystem.out.println(\"\" + a[1]);\nSystem.out.println(\"\" + (a[0] + a[1]));", ["0", "1"], True)
pos("P35", "uint parameters and results", "System.out.println(\"\" + twice(0x8000_0001));", ["2"], True, extra="static @Unsigned int twice(@Unsigned int x) { return x + x; }")
pos("P36", "compound assignment below zero", "@Unsigned int u = 0;\nu -= 1;\nSystem.out.println(\"\" + u);", ["4294967295"], True)
pos("P37", "uint loop counter", "for (@Unsigned int i = 0; i < 3; i++) System.out.println(\"\" + i);", ["0", "1", "2"], True)
pos("P38", "string concatenation shows the unsigned value", "@Unsigned int u = 0xFFFF_FFFF;\nSystem.out.println(\"u=\" + u);", ["u=4294967295"], True)
# ---------------------------------------------------------------- against the JDK's unsigned methods
pos("P40", "comparison agrees with Integer.compareUnsigned",
    """var r = new java.util.Random(1);
boolean ok = true;
for (int k = 0; k < 100000; k++) {
    int a = r.nextInt(), b = r.nextInt();
    @Unsigned int ua = (@Unsigned int) a, ub = (@Unsigned int) b;
    ok &= (ua < ub) == (Integer.compareUnsigned(a, b) < 0);
}
System.out.println(ok);""", ["true"], True)
pos("P41", "division agrees with Integer.divideUnsigned",
    """var r = new java.util.Random(2);
boolean ok = true;
for (int k = 0; k < 100000; k++) {
    int a = r.nextInt(), b = r.nextInt() | 1;
    ok &= (int) ((@Unsigned int) a / (@Unsigned int) b) == Integer.divideUnsigned(a, b);
    ok &= (int) ((@Unsigned int) a % (@Unsigned int) b) == Integer.remainderUnsigned(a, b);
}
System.out.println(ok);""", ["true"], True)
pos("P42", "ulong printing agrees with Long.toUnsignedString",
    """var r = new java.util.Random(3);
boolean ok = true;
for (int k = 0; k < 100000; k++) {
    long a = r.nextLong();
    ok &= ("" + (@Unsigned long) a).equals(Long.toUnsignedString(a));
}
System.out.println(ok);""", ["true"], True)
pos("P43", "ulong division agrees with Long.divideUnsigned",
    """var r = new java.util.Random(4);
boolean ok = true;
for (int k = 0; k < 100000; k++) {
    long a = r.nextLong(), b = r.nextLong() | 1;
    ok &= (long) ((@Unsigned long) a / (@Unsigned long) b) == Long.divideUnsigned(a, b);
}
System.out.println(ok);""", ["true"], True)

# ---------------------------------------------------------------- rejected programs
neg("N01", "signed int into uint", "int i = 3;\n@Unsigned int u = i; // HERE", "cannot convert signed int to @Unsigned int")
neg("N02", "uint into signed int", "@Unsigned int u = 3;\nint i = u; // HERE", "cannot convert @Unsigned int to signed int")
neg("N03", "uint plus int", "@Unsigned int u = 3;\nint i = 1;\nvar s = u + i; // HERE", "cannot mix signed int and @Unsigned int")
neg("N04", "uint compared with int", "@Unsigned int u = 3;\nint i = -1;\nboolean b = u < i; // HERE", "cannot mix")
neg("N05", "int argument for a uint parameter", "int i = 3;\nf(i); // HERE", "cannot convert", extra="static void f(@Unsigned int x) {}")
neg("N06", "boxing a uint", "@Unsigned int u = 3;\nObject o = u; // HERE", "cannot be boxed")
neg("N14", "negative constant for a uint", "@Unsigned int u = -1; // HERE", "cannot convert signed int to @Unsigned int")
neg("N15", "long into ulong", "long l = 1;\n@Unsigned long u = l; // HERE", "cannot convert")
neg("N16", "ulong into long", "@Unsigned long u = 1L;\nlong l = u; // HERE", "cannot convert")
neg("N17", "uint equals int", "@Unsigned int u = 1;\nint i = 1;\nboolean b = u == i; // HERE", "cannot mix")
neg("N18", "constant too large for uint", "@Unsigned int u = 4294967296L; // HERE", "possible lossy conversion from long to int")
neg("N22", "a uint passed to println(int) (no implicit conversion to int)", "@Unsigned int u = 1;\nSystem.out.println(u); // HERE", "cannot convert @Unsigned int to signed int")
neg("N23", "a uint quotient passed to println(int): the error is on its line", "@Unsigned int u = 4;\nSystem.out.println(u / 2); // HERE", "cannot convert @Unsigned int to signed int")
neg("N21", "uint returned as int", "System.out.println(g());", "cannot convert", extra="static int g() { @Unsigned int u = 1; return u; } // HERE")
# new: P44 (ulong to double) has no JDK method, so it is not lowered and the checker rejects it
neg("N30", "ulong to double is rejected (P44 has no lowering)", "@Unsigned long u = 0xFFFF_FFFF_FFFF_FFFFL;\ndouble d = (double) u; // HERE", "to double uses the signed value")
neg("N31", "promotion of a uint to long sign-extends", "@Unsigned int u = 0xFFFF_FFFF;\nlong p = u + 1L; // HERE", "promoted with sign extension")
neg("N32", "a signed int in a uint array initialiser", "int i = 1;\n@Unsigned int[] a = {1, i}; // HERE", "in an array initializer")

NOT_PORTED = {
    "P09": ("ushort/ubyte", "the checker covers @Unsigned int and long only; 16/8-bit wrapping needs masking after every operation"),
    "P10": ("ushort/ubyte", "as P09"),
    "N20": ("ushort/ubyte", "as P09"),
    "P11": ("uint<N>", "a width in the type needs javac's type system (new syntax)"),
    "P12": ("uint<N>", "as P11"), "P13": ("uint<N>", "as P11"), "P14": ("uint<N>", "as P11"),
    "P15": ("uint<N>", "as P11"), "P16": ("uint<N>", "as P11"), "N08": ("uint<N>", "as P11"), "N09": ("uint<N>", "as P11"),
    "P20": ("slices and bit select", "x[hi:lo] and x[i] on integers are parse/attribution errors in stock javac"),
    "P21": ("slices and bit select", "as P20"), "P26": ("slices and bit select", "as P20"),
    "N12": ("slices and bit select", "as P20"), "N13": ("slices and bit select", "as P20"),
    "P22": ("concatenation", "{a, b} as an expression is a parse error in stock javac"),
    "P23": ("concatenation", "as P22"), "N19": ("concatenation", "as P22"),
    "P24": ("bits<N>", "new type syntax"), "P25": ("bits<N>", "new type syntax"),
    "N10": ("bits<N>", "new type syntax"), "N11": ("bits<N>", "new type syntax"),
    "P44": ("unsigned int/long", "ulong to double: no JDK method to lower to; ported as the negative test N30"),
    "N07": ("unsigned int/long", "uint as a type argument: there is no unsigned box type; List<@Unsigned Integer> is not checked"),
    "P50": ("operators on classes", "a + b on a class is an attribution error before any plugin runs"),
    "P51": ("operators on classes", "as P50"),
    "P52": ("trailing blocks", "a block after a call is a parse error in stock javac"),
    "P53": ("trailing blocks", "as P52"),
}

ANN = "@java.lang.annotation.Target(java.lang.annotation.ElementType.TYPE_USE)\n@interface Unsigned {}"

def write(dirname, items, kind):
    d = HERE / dirname
    d.mkdir(exist_ok=True)
    for f in d.glob("*.java"): f.unlink()
    for item in items:
        if kind == "pos":
            i, what, body, exp, lowered, extra = item
        else:
            i, what, body, exp, extra = item
        hdr = f"// {i}: {what}\n"
        if kind == "pos":
            hdr += "".join(f"// EXPECT: {e}\n" for e in exp)
            if lowered: hdr += "// LOWERED\n"
        else:
            hdr += f"// ERROR: {exp}\n"
        src = hdr + f"public class {i} {{\n" + textwrap.indent(ANN, "    ") + "\n\n"
        if extra: src += textwrap.indent(extra, "    ") + "\n\n"
        src += "    public static void main(String[] args) {\n" + textwrap.indent(body, " " * 8) + "\n    }\n}\n"
        (d / f"{i}.java").write_text(src)

if __name__ == "__main__":
    write("pos", P, "pos")
    write("neg", N, "neg")
    with open(HERE / "not-ported.txt", "w") as f:
        for k in sorted(NOT_PORTED): f.write(f"{k}\t{NOT_PORTED[k][0]}\t{NOT_PORTED[k][1]}\n")
    print(len(P), "positive,", len(N), "negative,", len(NOT_PORTED), "not ported")
