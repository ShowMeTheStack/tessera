// P24: bits<N> bitwise operations
// EXPECT: 48
// EXPECT: 204
public class P24 {
    public static void main(String[] args) {
        bits<8> a = 0xF0;
        bits<8> b = 0x3C;
        bits<8> c = a & b;
        System.out.println("" + ((uint<8>) c));
        System.out.println("" + ((uint<8>) (a ^ b)));
    }
}
