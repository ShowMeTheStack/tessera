// N01: signed int into uint
// ERROR: cannot convert signed int to @Unsigned int
public class N01 {
    @java.lang.annotation.Target(java.lang.annotation.ElementType.TYPE_USE)
    @interface Unsigned {}

    public static void main(String[] args) {
        int i = 3;
        @Unsigned int u = i; // HERE
    }
}
