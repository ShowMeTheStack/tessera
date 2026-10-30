// N05: int argument for a uint parameter
// ERROR: cannot convert
public class N05 {
    @java.lang.annotation.Target(java.lang.annotation.ElementType.TYPE_USE)
    @interface Unsigned {}

    static void f(@Unsigned int x) {}

    public static void main(String[] args) {
        int i = 3;
        f(i); // HERE
    }
}
