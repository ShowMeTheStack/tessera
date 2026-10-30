// P43: ulong division agrees with Long.divideUnsigned
// EXPECT: true
// LOWERED
public class P43 {
    @java.lang.annotation.Target(java.lang.annotation.ElementType.TYPE_USE)
    @interface Unsigned {}

    public static void main(String[] args) {
        var r = new java.util.Random(4);
        boolean ok = true;
        for (int k = 0; k < 100000; k++) {
            long a = r.nextLong(), b = r.nextLong() | 1;
            ok &= (long) ((@Unsigned long) a / (@Unsigned long) b) == Long.divideUnsigned(a, b);
        }
        System.out.println(ok);
    }
}
