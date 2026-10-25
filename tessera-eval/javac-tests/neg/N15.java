// N15: long into ulong
// ERROR: cannot convert
public class N15 {
    public static void main(String[] args) {
        long l = 1;
        ulong u = l; // HERE
    }
}
