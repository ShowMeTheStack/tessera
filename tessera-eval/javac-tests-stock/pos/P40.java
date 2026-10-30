// P40: comparison agrees with Integer.compareUnsigned
// EXPECT: true
// LOWERED
public class P40 {
    @java.lang.annotation.Target(java.lang.annotation.ElementType.TYPE_USE)
    @interface Unsigned {}

    public static void main(String[] args) {
        var r = new java.util.Random(1);
        boolean ok = true;
        for (int k = 0; k < 100000; k++) {
            int a = r.nextInt(), b = r.nextInt();
            @Unsigned int ua = (@Unsigned int) a, ub = (@Unsigned int) b;
            ok &= (ua < ub) == (Integer.compareUnsigned(a, b) < 0);
        }
        System.out.println(ok);
    }
}
