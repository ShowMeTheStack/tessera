// P38: string concatenation shows the unsigned value
// EXPECT: u=4294967295
public class P38 {
    public static void main(String[] args) {
        uint u = 0xFFFF_FFFFu;
        System.out.println("u=" + u);
    }
}
