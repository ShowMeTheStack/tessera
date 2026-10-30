// N32: a signed int in a uint array initialiser
// ERROR: in an array initializer
public class N32 {
    @java.lang.annotation.Target(java.lang.annotation.ElementType.TYPE_USE)
    @interface Unsigned {}

    public static void main(String[] args) {
        int i = 1;
        @Unsigned int[] a = {1, i}; // HERE
    }
}
