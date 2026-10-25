// N10: arithmetic on bits<N>
// ERROR: not defined for bit vectors
public class N10 {
    public static void main(String[] args) {
        bits<8> b = 1;
        var c = b + 1; // HERE
    }
}
