// N02: uint into signed int
// ERROR: cannot convert uint to signed int
public class N02 {
    public static void main(String[] args) {
        uint u = 3u;
        int i = u; // HERE
    }
}
