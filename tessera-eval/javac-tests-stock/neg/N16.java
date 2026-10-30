// N16: ulong into long
// ERROR: cannot convert
public class N16 {
    @java.lang.annotation.Target(java.lang.annotation.ElementType.TYPE_USE)
    @interface Unsigned {}

    public static void main(String[] args) {
        @Unsigned long u = 1L;
        long l = u; // HERE
    }
}
