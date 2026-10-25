// P12: uint<12> addition wraps
// EXPECT: 104
public class P12 {
    public static void main(String[] args) {
        uint<12> a = 4000;
        uint<12> b = 200;
        uint<12> c = a + b;
        System.out.println("" + (c));
    }
}
