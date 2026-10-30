// N30: ulong to double is rejected (P44 has no lowering)
// ERROR: to double uses the signed value
public class N30 {
    @java.lang.annotation.Target(java.lang.annotation.ElementType.TYPE_USE)
    @interface Unsigned {}

    public static void main(String[] args) {
        @Unsigned long u = 0xFFFF_FFFF_FFFF_FFFFL;
        double d = (double) u; // HERE
    }
}
