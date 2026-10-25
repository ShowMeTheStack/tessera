// P31: explicit cast uint to int
// EXPECT: -1
public class P31 {
    public static void main(String[] args) {
        uint u = 0xFFFF_FFFFu;
        int i = (int) u;
        System.out.println("" + (i));
    }
}
