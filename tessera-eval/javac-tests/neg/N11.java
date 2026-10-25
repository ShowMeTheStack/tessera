// N11: ordering on bits<N>
// ERROR: 
public class N11 {
    public static void main(String[] args) {
        bits<8> a = 1, b = 2;
        boolean c = a < b; // HERE
    }
}
