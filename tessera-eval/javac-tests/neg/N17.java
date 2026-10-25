// N17: uint equals int
// ERROR: 
public class N17 {
    public static void main(String[] args) {
        uint u = 1u;
        int i = 1;
        boolean b = u == i; // HERE
    }
}
