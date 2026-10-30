// P05: uint >> is a logical shift
// EXPECT: 1
// LOWERED
public class P05 {
    @java.lang.annotation.Target(java.lang.annotation.ElementType.TYPE_USE)
    @interface Unsigned {}

    public static void main(String[] args) {
        @Unsigned int u = 0x8000_0000;
        System.out.println("" + (u >> 31));
    }
}
