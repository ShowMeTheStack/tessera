// N15: long into ulong
// ERROR: cannot convert
public class N15 {
    @java.lang.annotation.Target(java.lang.annotation.ElementType.TYPE_USE)
    @interface Unsigned {}

    public static void main(String[] args) {
        long l = 1;
        @Unsigned long u = l; // HERE
    }
}
