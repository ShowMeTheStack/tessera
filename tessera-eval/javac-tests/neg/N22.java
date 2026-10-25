// N22: a uint passed to println(int) (no implicit conversion to int)
// ERROR: cannot convert uint to signed int
public class N22 {
    public static void main(String[] args) {
        uint u = 1u;
        System.out.println(u); // HERE
    }
}
