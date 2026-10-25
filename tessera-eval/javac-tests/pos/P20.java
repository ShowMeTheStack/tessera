// P20: slice gives the field
// EXPECT: 10
// EXPECT: 4660
public class P20 {
    public static void main(String[] args) {
        uint u = 0xABCD_1234u;
        System.out.println("" + (u[31:28]));
        System.out.println("" + (u[15:0]));
    }
}
