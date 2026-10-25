// P09: ushort wraps at 2^16
// EXPECT: 0
public class P09 {
    public static void main(String[] args) {
        ushort s = (ushort) 65535;
        s++;
        System.out.println("" + (s));
    }
}
