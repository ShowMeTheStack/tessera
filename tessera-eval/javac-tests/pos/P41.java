// P41: division agrees with Integer.divideUnsigned
// EXPECT: true
public class P41 {
    public static void main(String[] args) {
        var r = new java.util.Random(2);
        boolean ok = true;
        for (int k = 0; k < 100000; k++) {
            int a = r.nextInt(), b = r.nextInt() | 1;
            ok &= (int) ((uint) a / (uint) b) == Integer.divideUnsigned(a, b);
            ok &= (int) ((uint) a % (uint) b) == Integer.remainderUnsigned(a, b);
        }
        System.out.println(ok);
    }
}
