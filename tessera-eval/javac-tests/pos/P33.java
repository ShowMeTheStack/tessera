// P33: constants that fit are accepted
// EXPECT: 6
// EXPECT: 255
public class P33 {
    public static void main(String[] args) {
        uint x = 5;
        uint<8> y = 255;
        System.out.println("" + (x + 1u));
        System.out.println("" + (y));
    }
}
