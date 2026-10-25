// P16: uint<N> subtraction wraps
// EXPECT: 62
public class P16 {
    public static void main(String[] args) {
        uint<6> a = 1;
        uint<6> b = 3;
        uint<6> c = a - b;
        System.out.println("" + (c));
    }
}
