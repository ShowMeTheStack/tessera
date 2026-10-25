// P11: uint<12> increments wrap
// EXPECT: 0
public class P11 {
    public static void main(String[] args) {
        uint<12> x = 4095;
        x++;
        System.out.println("" + (x));
    }
}
