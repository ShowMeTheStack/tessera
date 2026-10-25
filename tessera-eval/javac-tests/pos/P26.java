// P26: slice of a long-backed value
// EXPECT: 3735928559
public class P26 {
    public static void main(String[] args) {
        ulong u = 0xDEAD_BEEF_0000_0000uL;
        System.out.println("" + (u[63:32]));
    }
}
