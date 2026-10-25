// P08: ulong comparison is unsigned
// EXPECT: true
public class P08 {
    public static void main(String[] args) {
        ulong a = 0x8000_0000_0000_0000uL;
        System.out.println("" + (a > 1uL));
    }
}
