// P36: compound assignment below zero
// EXPECT: 4294967295
// LOWERED
public class P36 {
    @java.lang.annotation.Target(java.lang.annotation.ElementType.TYPE_USE)
    @interface Unsigned {}

    public static void main(String[] args) {
        @Unsigned int u = 0;
        u -= 1;
        System.out.println("" + u);
    }
}
