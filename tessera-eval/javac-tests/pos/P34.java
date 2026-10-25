// P34: uint in arrays
// EXPECT: 0
// EXPECT: 1
public class P34 {
    public static void main(String[] args) {
        uint[] a = {1u, 0xFFFF_FFFFu};
        a[1]++;
        System.out.println("" + (a[1]));
        System.out.println("" + (a[0] + a[1]));
    }
}
