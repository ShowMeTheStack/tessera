// P04: uint comparison is unsigned
// EXPECT: true
// EXPECT: false
public class P04 {
    public static void main(String[] args) {
        uint a = 0x8000_0000u, b = 1u;
        System.out.println("" + (a > b));
        System.out.println("" + (a <= b));
    }
}
