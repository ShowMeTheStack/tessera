// P32: uint widens to long with zero extension
// EXPECT: 4294967295
public class P32 {
    public static void main(String[] args) {
        uint u = 0xFFFF_FFFFu;
        long l = u;
        System.out.println("" + (l));
    }
}
