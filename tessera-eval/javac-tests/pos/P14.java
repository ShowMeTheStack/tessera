// P14: uint<N> multiply masks
// EXPECT: 88
public class P14 {
    public static void main(String[] args) {
        uint<8> a = 200;
        uint<8> b = 3;
        uint<8> c = a * b;
        System.out.println("" + (c));
    }
}
