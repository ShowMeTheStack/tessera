// P02: uint division is unsigned
// EXPECT: 2147483647
public class P02 {
    public static void main(String[] args) {
        uint u = 0xFFFF_FFFEu;
        System.out.println("" + (u / 2u));
    }
}
