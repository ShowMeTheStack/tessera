// P43: ulong division agrees with Long.divideUnsigned
// EXPECT: true
public class P43 {
    public static void main(String[] args) {
        var r = new java.util.Random(4);
        boolean ok = true;
        for (int k = 0; k < 100000; k++) {
            long a = r.nextLong(), b = r.nextLong() | 1;
            ok &= (long) ((ulong) a / (ulong) b) == Long.divideUnsigned(a, b);
        }
        System.out.println(ok);
    }
}
