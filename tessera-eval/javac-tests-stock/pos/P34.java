// P34: uint in arrays
// EXPECT: 0
// EXPECT: 1
// LOWERED
public class P34 {
    @java.lang.annotation.Target(java.lang.annotation.ElementType.TYPE_USE)
    @interface Unsigned {}

    public static void main(String[] args) {
        @Unsigned int[] a = {1, 0xFFFF_FFFF};
        a[1]++;
        System.out.println("" + a[1]);
        System.out.println("" + (a[0] + a[1]));
    }
}
