// P02: uint division is unsigned
// EXPECT: 2147483647
// LOWERED
public class P02 {
    @java.lang.annotation.Target(java.lang.annotation.ElementType.TYPE_USE)
    @interface Unsigned {}

    public static void main(String[] args) {
        @Unsigned int u = 0xFFFF_FFFE;
        System.out.println("" + (u / 2));
    }
}
