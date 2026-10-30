// N06: boxing a uint
// ERROR: cannot be boxed
public class N06 {
    @java.lang.annotation.Target(java.lang.annotation.ElementType.TYPE_USE)
    @interface Unsigned {}

    public static void main(String[] args) {
        @Unsigned int u = 3;
        Object o = u; // HERE
    }
}
