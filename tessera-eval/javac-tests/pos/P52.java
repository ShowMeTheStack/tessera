// P52: trailing blocks: when/elseWhen/otherwise
// EXPECT: two
public class P52 {
    static Chain when(boolean c, Runnable r) { if (c) r.run(); return new Chain(c); }
    record Chain(boolean done) {
        Chain elseWhen(boolean c, Runnable r) { if (!done && c) r.run(); return new Chain(done || c); }
        void otherwise(Runnable r) { if (!done) r.run(); }
    }

    public static void main(String[] args) {
        int v = 2;
        when (v == 1) {
            System.out.println("one");
        }.elseWhen (v == 2) {
            System.out.println("two");
        }.otherwise {
            System.out.println("other");
        }
    }
}
