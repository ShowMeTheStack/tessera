// P53: a trailing block captures locals
// EXPECT: 7
public class P53 {
    static Chain when(boolean c, Runnable r) { if (c) r.run(); return new Chain(c); }
    record Chain(boolean done) {
        Chain elseWhen(boolean c, Runnable r) { if (!done && c) r.run(); return new Chain(done || c); }
        void otherwise(Runnable r) { if (!done) r.run(); }
    }

    public static void main(String[] args) {
        int[] n = {0};
        when (true) {
            n[0] = 7;
        }
        System.out.println(n[0]);
    }
}
