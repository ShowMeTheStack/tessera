// P03: uint remainder is unsigned
// EXPECT: 5
// LOWERED
public class P03 {
    @java.lang.annotation.Target(java.lang.annotation.ElementType.TYPE_USE)
    @interface Unsigned {}

    public static void main(String[] args) {
        @Unsigned int u = 0xFFFF_FFFF;
        System.out.println("" + (u % 10));
    }
}
