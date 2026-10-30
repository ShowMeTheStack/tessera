// P37: uint loop counter
// EXPECT: 0
// EXPECT: 1
// EXPECT: 2
// LOWERED
public class P37 {
    @java.lang.annotation.Target(java.lang.annotation.ElementType.TYPE_USE)
    @interface Unsigned {}

    public static void main(String[] args) {
        for (@Unsigned int i = 0; i < 3; i++) System.out.println("" + i);
    }
}
