// P01: uint maximum prints unsigned
// EXPECT: 4294967295
public class P01 {
    public static void main(String[] args) {
        uint u = 0xFFFF_FFFFu;
        System.out.println("" + (u));
    }
}
