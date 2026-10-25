public class Design {
    static int twice(int x) { return 2 * x; }

    public static void main(String[] args) {
        uint u = 7u;
        System.out.println(twice(u)); // MISTAKE
    }
}
