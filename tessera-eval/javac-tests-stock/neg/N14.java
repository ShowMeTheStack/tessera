// N14: negative constant for a uint
// ERROR: cannot convert signed int to @Unsigned int
public class N14 {
    @java.lang.annotation.Target(java.lang.annotation.ElementType.TYPE_USE)
    @interface Unsigned {}

    public static void main(String[] args) {
        @Unsigned int u = -1; // HERE
    }
}
