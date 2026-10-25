// P07: ulong division is unsigned
// EXPECT: 6148914691236517205
public class P07 {
    public static void main(String[] args) {
        ulong u = 0xFFFF_FFFF_FFFF_FFFFuL;
        System.out.println("" + (u / 3uL));
    }
}
