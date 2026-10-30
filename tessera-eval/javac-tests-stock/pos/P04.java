// P04: uint comparison is unsigned
// EXPECT: true
// EXPECT: false
// LOWERED
public class P04 {
    @java.lang.annotation.Target(java.lang.annotation.ElementType.TYPE_USE)
    @interface Unsigned {}

    public static void main(String[] args) {
        @Unsigned int a = 0x8000_0000, b = 1;
        System.out.println("" + (a > b));
        System.out.println("" + (a <= b));
    }
}
