// P51: bit select and slice operators on a class
// EXPECT: true
// EXPECT: 3
public class P51 {
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
        V a = new V(0b1100);
        System.out.println(a[2]);
        System.out.println(a[3:2]);
    }
}
