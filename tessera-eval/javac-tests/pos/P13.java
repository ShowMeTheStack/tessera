// P13: uint<5> shifts mask to 5 bits
// EXPECT: 16
// EXPECT: 0
public class P13 {
    public static void main(String[] args) {
        uint<5> x = 1;
        x <<= 4;
        System.out.println("" + (x));
        x <<= 1;
        System.out.println("" + (x));
    }
}
