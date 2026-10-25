// N03: uint plus int
// ERROR: cannot mix signed int and uint
public class N03 {
    public static void main(String[] args) {
        uint u = 3u;
        int i = 1;
        var s = u + i; // HERE
    }
}
