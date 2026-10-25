// P10: ubyte arithmetic wraps at 2^8
// EXPECT: 44
public class P10 {
    public static void main(String[] args) {
        ubyte b = (ubyte) 200;
        b += (ubyte) 100;
        System.out.println("" + (b));
    }
}
