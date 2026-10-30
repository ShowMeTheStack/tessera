// P06: ulong maximum prints unsigned
// EXPECT: 18446744073709551615
// LOWERED
public class P06 {
    @java.lang.annotation.Target(java.lang.annotation.ElementType.TYPE_USE)
    @interface Unsigned {}

    public static void main(String[] args) {
        @Unsigned long u = 0xFFFF_FFFF_FFFF_FFFFL;
        System.out.println("" + u);
    }
}
