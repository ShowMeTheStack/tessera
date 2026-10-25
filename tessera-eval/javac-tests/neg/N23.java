// N23: a uint quotient passed to println(int): the error is on its line
// ERROR: cannot convert uint to signed int
public class N23 {
    public static void main(String[] args) {
        uint u = 4u;
        System.out.println(u / 2u); // HERE
    }
}
