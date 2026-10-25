// N06: boxing a uint
// ERROR: 
public class N06 {
    public static void main(String[] args) {
        uint u = 3u;
        Object o = u; // HERE
    }
}
