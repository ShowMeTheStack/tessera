// N16: ulong into long
// ERROR: cannot convert
public class N16 {
    public static void main(String[] args) {
        ulong u = 1uL;
        long l = u; // HERE
    }
}
