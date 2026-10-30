// N17: uint equals int
// ERROR: cannot mix
public class N17 {
    @java.lang.annotation.Target(java.lang.annotation.ElementType.TYPE_USE)
    @interface Unsigned {}

    public static void main(String[] args) {
        @Unsigned int u = 1;
        int i = 1;
        boolean b = u == i; // HERE
    }
}
