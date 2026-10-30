// N31: promotion of a uint to long sign-extends
// ERROR: promoted with sign extension
public class N31 {
    @java.lang.annotation.Target(java.lang.annotation.ElementType.TYPE_USE)
    @interface Unsigned {}

    public static void main(String[] args) {
        @Unsigned int u = 0xFFFF_FFFF;
        long p = u + 1L; // HERE
    }
}
