// P15: uint<N> bitwise not masks
// EXPECT: 10
public class P15 {
    public static void main(String[] args) {
        uint<4> a = 5;
        uint<4> b = ~a;
        System.out.println("" + (b));
    }
}
