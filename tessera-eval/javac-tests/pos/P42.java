// P42: ulong printing agrees with Long.toUnsignedString
// EXPECT: true
public class P42 {
    public static void main(String[] args) {
        var r = new java.util.Random(3);
        boolean ok = true;
        for (int k = 0; k < 100000; k++) {
            long a = r.nextLong();
            ok &= ("" + (ulong) a).equals(Long.toUnsignedString(a));
        }
        System.out.println(ok);
    }
}
