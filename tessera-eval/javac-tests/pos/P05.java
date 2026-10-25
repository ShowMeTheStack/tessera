// P05: uint >> is a logical shift
// EXPECT: 1
public class P05 {
    public static void main(String[] args) {
        uint u = 0x8000_0000u;
        System.out.println("" + (u >> 31));
    }
}
