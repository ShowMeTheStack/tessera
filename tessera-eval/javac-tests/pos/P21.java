// P21: bit select is a boolean
// EXPECT: true
// EXPECT: false
public class P21 {
    public static void main(String[] args) {
        uint u = 0b1010u;
        System.out.println("" + (u[1]));
        System.out.println("" + (u[0]));
    }
}
