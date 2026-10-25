// N20: unsigned value into a signed short
// ERROR: 
public class N20 {
    public static void main(String[] args) {
        ushort s = (ushort) 1;
        short t = s; // HERE
    }
}
