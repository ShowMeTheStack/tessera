// N02: uint into signed int
// ERROR: cannot convert @Unsigned int to signed int
public class N02 {
    @java.lang.annotation.Target(java.lang.annotation.ElementType.TYPE_USE)
    @interface Unsigned {}

    public static void main(String[] args) {
        @Unsigned int u = 3;
        int i = u; // HERE
    }
}
