public class Design {
    public static void main(String[] args) {
        bits<8> b = 0x0F;
        var c = b + 1; // MISTAKE
        System.out.println(c);
    }
}
