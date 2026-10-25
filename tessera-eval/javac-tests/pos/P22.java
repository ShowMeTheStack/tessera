// P22: concatenation puts the first operand on top
// EXPECT: 165
public class P22 {
    public static void main(String[] args) {
        uint<4> h = 0xA;
        uint<4> l = 0x5;
        uint<8> b = {h, l};
        System.out.println("" + (b));
    }
}
