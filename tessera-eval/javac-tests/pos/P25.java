// P25: bits<N> equality
// EXPECT: true
public class P25 {
    public static void main(String[] args) {
        bits<8> a = 0x0F;
        bits<8> b = 0x0F;
        System.out.println("" + (a == b));
    }
}
