public class Design {
    public static void main(String[] args) {
        uint u = 0xFFFF_FFFFu;
        int x = u; // MISTAKE
        System.out.println(x);
    }
}
