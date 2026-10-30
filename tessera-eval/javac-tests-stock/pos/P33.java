// P33: constants that fit are accepted
// EXPECT: 6
// EXPECT: 255
// LOWERED
public class P33 {
    @java.lang.annotation.Target(java.lang.annotation.ElementType.TYPE_USE)
    @interface Unsigned {}

    public static void main(String[] args) {
        @Unsigned int x = 5;
        @Unsigned int y = 255;
        System.out.println("" + (x + 1));
        System.out.println("" + y);
    }
}
