// P40: comparison agrees with Integer.compareUnsigned
// EXPECT: true
public class P40 {
    public static void main(String[] args) {
        var r = new java.util.Random(1);
        boolean ok = true;
        for (int k = 0; k < 100000; k++) {
            int a = r.nextInt(), b = r.nextInt();
            uint ua = (uint) a, ub = (uint) b;
            ok &= (ua < ub) == (Integer.compareUnsigned(a, b) < 0);
        }
        System.out.println(ok);
    }
}
