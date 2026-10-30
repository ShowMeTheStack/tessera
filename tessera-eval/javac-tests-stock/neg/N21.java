// N21: uint returned as int
// ERROR: cannot convert
public class N21 {
    @java.lang.annotation.Target(java.lang.annotation.ElementType.TYPE_USE)
    @interface Unsigned {}

    static int g() { @Unsigned int u = 1; return u; } // HERE

    public static void main(String[] args) {
        System.out.println(g());
    }
}
