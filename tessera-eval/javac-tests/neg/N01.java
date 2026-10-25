// N01: signed int into uint
// ERROR: cannot convert signed int to uint
public class N01 {
    public static void main(String[] args) {
        int i = 3;
        uint u = i; // HERE
    }
}
