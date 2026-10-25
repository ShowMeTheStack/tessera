// N21: uint returned as int
// ERROR: cannot convert
public class N21 {
    static int g() { uint u = 1u; return u; } // HERE

    public static void main(String[] args) {
        System.out.println(g());
    }
}
