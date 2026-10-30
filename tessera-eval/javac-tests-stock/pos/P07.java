// P07: ulong division is unsigned
// EXPECT: 6148914691236517205
// LOWERED
public class P07 {
    @java.lang.annotation.Target(java.lang.annotation.ElementType.TYPE_USE)
    @interface Unsigned {}

    public static void main(String[] args) {
        @Unsigned long u = 0xFFFF_FFFF_FFFF_FFFFL;
        System.out.println("" + (u / 3L));
    }
}
