// N03: uint plus int
// ERROR: cannot mix signed int and @Unsigned int
public class N03 {
    @java.lang.annotation.Target(java.lang.annotation.ElementType.TYPE_USE)
    @interface Unsigned {}

    public static void main(String[] args) {
        @Unsigned int u = 3;
        int i = 1;
        var s = u + i; // HERE
    }
}
