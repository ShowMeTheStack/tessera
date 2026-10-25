// P35: uint parameters and results
// EXPECT: 2
public class P35 {
    static uint twice(uint x) { return x + x; }

    public static void main(String[] args) {
        System.out.println("" + (twice(0x8000_0001u)));
    }
}
