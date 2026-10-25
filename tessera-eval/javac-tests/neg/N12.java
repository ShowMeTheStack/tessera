// N12: slice above the top bit
// ERROR: 
public class N12 {
    public static void main(String[] args) {
        uint u = 1u;
        var s = u[32:0]; // HERE
    }
}
