// N18: constant too large for uint
// ERROR: possible lossy conversion from long to int
public class N18 {
    @java.lang.annotation.Target(java.lang.annotation.ElementType.TYPE_USE)
    @interface Unsigned {}

    public static void main(String[] args) {
        @Unsigned int u = 4294967296L; // HERE
    }
}
