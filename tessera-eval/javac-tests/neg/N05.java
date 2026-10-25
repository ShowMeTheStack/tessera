// N05: int argument for a uint parameter
// ERROR: cannot convert
public class N05 {
    static void f(uint x) {}

    public static void main(String[] args) {
        int i = 3;
        f(i); // HERE
    }
}
