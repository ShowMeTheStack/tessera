// P44: ulong to double is unsigned
// EXPECT: true
// EXPECT: -1
public class P44 {
    public static void main(String[] args) {
        ulong u = 0xFFFF_FFFF_FFFF_FFFFuL;
        double d = (double) u;
        System.out.println("" + (d > 1.8e19));
        long l = (long) u;
        System.out.println("" + (l));
    }
}
