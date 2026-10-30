// P08: ulong comparison is unsigned
// EXPECT: true
// LOWERED
public class P08 {
    @java.lang.annotation.Target(java.lang.annotation.ElementType.TYPE_USE)
    @interface Unsigned {}

    public static void main(String[] args) {
        @Unsigned long a = 0x8000_0000_0000_0000L;
        System.out.println("" + (a > 1L));
    }
}
