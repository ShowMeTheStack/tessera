// P01: uint maximum prints unsigned
// EXPECT: 4294967295
// LOWERED
public class P01 {
    @java.lang.annotation.Target(java.lang.annotation.ElementType.TYPE_USE)
    @interface Unsigned {}

    public static void main(String[] args) {
        @Unsigned int u = 0xFFFF_FFFF;
        System.out.println("" + u);
    }
}
