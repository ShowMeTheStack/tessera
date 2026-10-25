// N13: slice with hi below lo
// ERROR: 
public class N13 {
    public static void main(String[] args) {
        uint u = 1u;
        var s = u[3:7]; // HERE
    }
}
