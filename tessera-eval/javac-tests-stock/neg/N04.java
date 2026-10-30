// N04: uint compared with int
// ERROR: cannot mix
public class N04 {
    @java.lang.annotation.Target(java.lang.annotation.ElementType.TYPE_USE)
    @interface Unsigned {}

    public static void main(String[] args) {
        @Unsigned int u = 3;
        int i = -1;
        boolean b = u < i; // HERE
    }
}
