// P32: uint widens to long with zero extension
// EXPECT: 4294967295
// LOWERED
public class P32 {
    @java.lang.annotation.Target(java.lang.annotation.ElementType.TYPE_USE)
    @interface Unsigned {}

    public static void main(String[] args) {
        @Unsigned int u = 0xFFFF_FFFF;
        long l = u;
        System.out.println("" + l);
    }
}
