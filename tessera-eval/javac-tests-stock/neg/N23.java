// N23: a uint quotient passed to println(int): the error is on its line
// ERROR: cannot convert @Unsigned int to signed int
public class N23 {
    @java.lang.annotation.Target(java.lang.annotation.ElementType.TYPE_USE)
    @interface Unsigned {}

    public static void main(String[] args) {
        @Unsigned int u = 4;
        System.out.println(u / 2); // HERE
    }
}
