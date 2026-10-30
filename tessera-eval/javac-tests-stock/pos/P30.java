// P30: explicit cast int to uint
// EXPECT: 4294967295
// LOWERED
public class P30 {
    @java.lang.annotation.Target(java.lang.annotation.ElementType.TYPE_USE)
    @interface Unsigned {}

    public static void main(String[] args) {
        int i = -1;
        @Unsigned int u = (@Unsigned int) i;
        System.out.println("" + u);
    }
}
