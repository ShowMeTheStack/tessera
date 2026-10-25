// P30: explicit cast int to uint
// EXPECT: 4294967295
public class P30 {
    public static void main(String[] args) {
        int i = -1;
        uint u = (uint) i;
        System.out.println("" + (u));
    }
}
