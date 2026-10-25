// N04: uint compared with int
// ERROR: cannot mix
public class N04 {
    public static void main(String[] args) {
        uint u = 3u;
        int i = -1;
        boolean b = u < i; // HERE
    }
}
