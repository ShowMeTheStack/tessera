// P42: ulong printing agrees with Long.toUnsignedString
// EXPECT: true
// LOWERED
public class P42 {
    @java.lang.annotation.Target(java.lang.annotation.ElementType.TYPE_USE)
    @interface Unsigned {}

    public static void main(String[] args) {
        var r = new java.util.Random(3);
        boolean ok = true;
        for (int k = 0; k < 100000; k++) {
            long a = r.nextLong();
            ok &= ("" + (@Unsigned long) a).equals(Long.toUnsignedString(a));
        }
        System.out.println(ok);
    }
}
