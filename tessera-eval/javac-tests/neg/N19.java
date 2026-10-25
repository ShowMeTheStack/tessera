// N19: concatenating an int of unknown width
// ERROR: 
public class N19 {
    public static void main(String[] args) {
        int i = 1;
        uint<4> h = 1;
        var c = {h, i}; // HERE
    }
}
