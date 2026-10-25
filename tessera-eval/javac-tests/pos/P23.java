// P23: concatenation of slices
// EXPECT: 30738
public class P23 {
    public static void main(String[] args) {
        uint u = 0x1234_5678u;
        uint<16> s = {u[7:0], u[31:24]};
        System.out.println("" + (s));
    }
}
