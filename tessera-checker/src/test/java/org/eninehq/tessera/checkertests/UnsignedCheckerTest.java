package org.eninehq.tessera.checkertests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The {@code @Unsigned int/long} checks of -Xplugin:TesseraCheck (public compiler API only). */
class UnsignedCheckerTest {

    static final String HEADER = """
            package demo;
            import org.eninehq.tessera.core.Unsigned;
            """;

    /** Compiles {@code body} as the body of {@code static void m()} in class {@code name}; line 6 is the first body line. */
    static List<String> body(String name, String body, String extra) {
        return CheckerTest.compile(name, HEADER + "public class " + name + " {\n" + "    static void m() {\n"
                + "        int i = 1; long l = 1L; @Unsigned int u = 1; @Unsigned long ul = 1L;\n"
                + body + "\n    }\n" + extra + "\n}\n");
    }

    static void rejects(String name, String code, String message) {
        var errs = body(name, code, "");
        assertEquals(1, errs.size(), errs.toString());
        assertTrue(errs.getFirst().startsWith("line 6:") && errs.getFirst().contains(message), errs.toString());
    }

    @Test
    void cleanUnsignedCodeHasNoErrors() {
        var errs = body("Clean", """
                @Unsigned int a = 0xFFFF_FFFF, b = 3, c = a + b * 2 - (a ^ b);
                @Unsigned long big = 0xFFFF_FFFF_FFFF_FFFFL;
                @Unsigned int q = Integer.divideUnsigned(a, b), r = Integer.remainderUnsigned(a, b);
                boolean lt = Integer.compareUnsigned(a, b) < 0, eq = a == b;
                String s = Integer.toUnsignedString(a) + Long.toUnsignedString(big) + Integer.toHexString(a);
                long wide = Integer.toUnsignedLong(a);
                int back = (int) a;
                @Unsigned int fromSigned = (@Unsigned int) i;
                @Unsigned int shifted = a >>> 3 << 1;
                @Unsigned int rot = Integer.rotateLeft(a, 3);
                int n = Integer.bitCount(a);
                @Unsigned int cnt = Integer.numberOfLeadingZeros(a);
                a += 1; a -= b; a *= 3; a >>>= 1; a &= 0xFF;
                @Unsigned int sel = lt ? a : 7;
                @Unsigned int t = twice(a) + twice(5);
                for (@Unsigned int k = 0; k != 3; k++) { a ^= k; }
                Runnable run = () -> { @Unsigned int z = 1; z += 2; };
                """, """
                    static @Unsigned int twice(@Unsigned int x) { return x + x; }
                    static int signedOk(int x) { return x * 2; }
                """);
        assertEquals(List.of(), errs);
    }

    @Test
    void signedIntoUnsigned() { rejects("N01", "@Unsigned int x = i;", "cannot convert signed int to @Unsigned int implicitly; use a cast (@Unsigned int)"); }

    @Test
    void unsignedIntoSigned() { rejects("N02", "int x = u;", "cannot convert @Unsigned int to signed int implicitly; use a cast (int)"); }

    @Test
    void unsignedPlusSigned() { rejects("N03", "var s = u + i;", "cannot mix signed int and @Unsigned int; cast one of them"); }

    @Test
    void unsignedComparedWithSigned() { rejects("N04", "boolean b = u < i;", "cannot mix signed int and @Unsigned int"); }

    @Test
    void unsignedEqualsSigned() { rejects("N17", "boolean b = u == i;", "cannot mix signed int and @Unsigned int"); }

    @Test
    void negativeConstant() { rejects("N14", "@Unsigned int x = -1;", "cannot convert signed int to @Unsigned int"); }

    @Test
    void signedLongIntoUnsignedLong() { rejects("N15", "@Unsigned long x = l;", "cannot convert signed long to @Unsigned long"); }

    @Test
    void unsignedLongIntoSignedLong() { rejects("N16", "long x = ul;", "cannot convert @Unsigned long to signed long"); }

    @Test
    void boxing() { rejects("N06", "Object o = u;", "@Unsigned int cannot be boxed"); }

    @Test
    void printlnOfUnsigned() { rejects("N22", "System.out.println(u);", "cannot convert @Unsigned int to signed int implicitly (argument for parameter"); }

    @Test
    void assignmentStatement() { rejects("Assign", "i = u;", "cannot convert @Unsigned int to signed int"); }

    @Test
    void compoundMix() { rejects("Compound", "u += i;", "cannot mix signed int and @Unsigned int"); }

    @Test
    void conditionalMix() { rejects("Cond", "var c = (i > 0 ? u : i) + u;", "cannot mix signed int and @Unsigned int"); }

    @Test
    void divisionIsSigned() { rejects("Div", "@Unsigned int q = u / 3;", "operator / has signed semantics in Java; for @Unsigned values use Integer/Long.divideUnsigned(a, b)"); }

    @Test
    void remainderIsSigned() { rejects("Rem", "@Unsigned int q = u % 3;", "operator % has signed semantics"); }

    @Test
    void orderingIsSigned() { rejects("Lt", "boolean b = u < 3;", "operator < has signed semantics"); }

    @Test
    void longOrderingIsSigned() { rejects("Ge", "boolean b = ul >= ul;", "operator >= has signed semantics"); }

    @Test
    void arithmeticShift() { rejects("Sra", "@Unsigned int s = u >> 1;", "operator >> on an @Unsigned value is an arithmetic shift; use >>>"); }

    @Test
    void compoundDivision() { rejects("DivAsg", "u /= 2;", "operator / has signed semantics"); }

    @Test
    void stringConcatenation() { rejects("Concat", "String s = \"u=\" + u;", "string concatenation shows the signed value of an @Unsigned int"); }

    @Test
    void wideningSignExtends() { rejects("Widen", "long w = u;", "widening @Unsigned int to long sign-extends"); }

    @Test
    void castToLongSignExtends() { rejects("CastL", "long w = (long) u;", "widening @Unsigned int to long sign-extends"); }

    @Test
    void castToDouble() { rejects("CastD", "double d = (double) ul;", "converting @Unsigned long to double uses the signed value"); }

    @Test
    void promotion() { rejects("Promo", "long w = u + l;", "promoted with sign extension"); }

    @Test
    void returnFromSignedMethod() {
        var errs = CheckerTest.compile("N21", HEADER + """
                public class N21 {
                    static int g() { @Unsigned int u = 1; return u; }
                    static @Unsigned int h(int x) { return x; }
                }
                """);
        assertEquals(2, errs.size(), errs.toString());
        assertTrue(errs.get(0).startsWith("line 4:") && errs.get(0).contains("cannot convert @Unsigned int to signed int implicitly in a return"), errs.toString());
        assertTrue(errs.get(1).startsWith("line 5:") && errs.get(1).contains("cannot convert signed int to @Unsigned int implicitly in a return"), errs.toString());
    }

    @Test
    void signedArgumentForUnsignedParameter() {
        var errs = body("N05", "f(i);", "    static void f(@Unsigned int x) {}");
        assertEquals(1, errs.size(), errs.toString());
        assertTrue(errs.getFirst().contains("cannot convert signed int to @Unsigned int implicitly (argument for parameter 'x' of f)"), errs.toString());
    }

    @Test
    void unsignedArrayElements() {
        var errs = body("Arr", """
                @Unsigned int[] a = {1, 0xFFFF_FFFF};
                @Unsigned int ok = a[0] + a[1];
                int bad = a[1];
                """, "");
        assertEquals(1, errs.size(), errs.toString());
        assertTrue(errs.getFirst().startsWith("line 8:") && errs.getFirst().contains("cannot convert @Unsigned int to signed int"), errs.toString());
    }

    @Test
    void ownAnnotationNamedUnsignedWorksToo() {
        var errs = CheckerTest.compile("Own", """
                package demo;
                public class Own {
                    @java.lang.annotation.Target(java.lang.annotation.ElementType.TYPE_USE) @interface Unsigned {}
                    static void m(int i) { @Unsigned int u = i; }
                }
                """);
        assertEquals(1, errs.size(), errs.toString());
        assertTrue(errs.getFirst().startsWith("line 4:") && errs.getFirst().contains("cannot convert signed int to @Unsigned int"), errs.toString());
    }

    @Test
    void unsignedCastToLongSignExtends() { rejects("CastUL", "@Unsigned long w = (@Unsigned long) u;", "widening @Unsigned int to long sign-extends"); }

    @Test
    void compoundPromotion() { rejects("CompPromo", "ul += u;", "promoted with sign extension"); }

    @Test
    void conditionalPromotion() { rejects("CondPromo", "@Unsigned long w = i > 0 ? u : ul;", "of ?: is promoted with sign extension"); }

    @Test
    void arrayInitialiserMix() { rejects("ArrMix", "@Unsigned int[] a = {u, i};", "cannot convert signed int to @Unsigned int implicitly in an array initializer"); }

    @Test
    void arrayInitialiserWidening() { rejects("ArrWide", "long[] a = {u};", "widening @Unsigned int to long sign-extends"); }

    @Test
    void constructorArgument() {
        var errs = body("Ctor", "new Ctor(i);", "    Ctor(@Unsigned int x) {}");
        assertEquals(1, errs.size(), errs.toString());
        assertTrue(errs.getFirst().contains("cannot convert signed int to @Unsigned int implicitly (argument for parameter 'x' of <init>)"), errs.toString());
    }
}
