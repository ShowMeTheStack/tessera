// P50: operators on a class with @Operator
// EXPECT: V5
// EXPECT: V-2
public class P50 {
    static final class V {
        final int x;
        V(int x) { this.x = x; }
        @Operator("+") V plus(V o) { return new V(x + o.x); }
        @Operator("~") V inv() { return new V(-x); }
        @Operator("[]") boolean bit(int i) { return ((x >> i) & 1) != 0; }
        @Operator("[:]") int field(int hi, int lo) { return (x >> lo) & ((1 << (hi - lo + 1)) - 1); }
        public String toString() { return "V" + x; }
    }
    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.CLASS)
    @interface Operator { String value(); }

    public static void main(String[] args) {
        V a = new V(2), b = new V(3);
        System.out.println(a + b);
        System.out.println(~a);
    }
}
