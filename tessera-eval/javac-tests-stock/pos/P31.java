// P31: explicit cast uint to int
// EXPECT: -1
public class P31 {
    @java.lang.annotation.Target(java.lang.annotation.ElementType.TYPE_USE)
    @interface Unsigned {}

    public static void main(String[] args) {
        @Unsigned int u = 0xFFFF_FFFF;
        int i = (int) u;
        System.out.println("" + i);
    }
}
