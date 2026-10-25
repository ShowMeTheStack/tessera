// P03: uint remainder is unsigned
// EXPECT: 5
public class P03 {
    public static void main(String[] args) {
        uint u = 0xFFFF_FFFFu;
        System.out.println("" + (u % 10u));
    }
}
