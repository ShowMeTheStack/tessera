// P41: division agrees with Integer.divideUnsigned
// EXPECT: true
// LOWERED
public class P41 {
    @java.lang.annotation.Target(java.lang.annotation.ElementType.TYPE_USE)
    @interface Unsigned {}

    public static void main(String[] args) {
        var r = new java.util.Random(2);
        boolean ok = true;
        for (int k = 0; k < 100000; k++) {
            int a = r.nextInt(), b = r.nextInt() | 1;
            ok &= (int) ((@Unsigned int) a / (@Unsigned int) b) == Integer.divideUnsigned(a, b);
            ok &= (int) ((@Unsigned int) a % (@Unsigned int) b) == Integer.remainderUnsigned(a, b);
        }
        System.out.println(ok);
    }
}
