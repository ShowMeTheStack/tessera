// N22: a uint passed to println(int) (no implicit conversion to int)
// ERROR: cannot convert @Unsigned int to signed int
public class N22 {
    @java.lang.annotation.Target(java.lang.annotation.ElementType.TYPE_USE)
    @interface Unsigned {}

    public static void main(String[] args) {
        @Unsigned int u = 1;
        System.out.println(u); // HERE
    }
}
