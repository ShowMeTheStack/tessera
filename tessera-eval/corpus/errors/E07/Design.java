import org.eninehq.tessera.core.*;
import org.eninehq.tessera.core.Module;
import static org.eninehq.tessera.core.Hw.*;
import org.eninehq.tessera.pipeline.*;

public class Design extends Module {
    public Design() {
        UInt a = input("a", UInt.w(8));
        Bits b = wire("b", Bits.w(16));
        b.set(a.asBits()); // MISTAKE
        output("y", UInt.w(16)).set(b.asUInt());
    }
}
