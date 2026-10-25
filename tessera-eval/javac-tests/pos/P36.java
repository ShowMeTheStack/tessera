// P36: compound assignment below zero
// EXPECT: 4294967295
public class P36 {
    public static void main(String[] args) {
        uint u = 0u;
        u -= 1u;
        System.out.println("" + (u));
    }
}
