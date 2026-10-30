// P35: uint parameters and results
// EXPECT: 2
// LOWERED
public class P35 {
    @java.lang.annotation.Target(java.lang.annotation.ElementType.TYPE_USE)
    @interface Unsigned {}

    static @Unsigned int twice(@Unsigned int x) { return x + x; }

    public static void main(String[] args) {
        System.out.println("" + twice(0x8000_0001));
    }
}
