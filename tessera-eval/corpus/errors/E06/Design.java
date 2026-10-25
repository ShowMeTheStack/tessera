import org.eninehq.tessera.core.*;
import org.eninehq.tessera.core.Module;
import static org.eninehq.tessera.core.Hw.*;
import org.eninehq.tessera.pipeline.*;

public class Design extends Module {
    public Design() {
        UInt addr = input("addr", UInt.w(8));
        UInt y = output("y", UInt.w(8));
        Mem<UInt> m = mem("m", UInt.w(8), 16);
        y.set(m.read(addr)); // MISTAKE
    }
}
