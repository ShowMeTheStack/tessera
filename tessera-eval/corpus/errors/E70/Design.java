import org.eninehq.tessera.core.*;
import org.eninehq.tessera.core.Module;
import static org.eninehq.tessera.core.Hw.*;
import org.eninehq.tessera.pipeline.*;

public class Design extends Module {
    public Design() {
        UInt r = reg("r", UInt.w(4), 0);
        r.set(mux(r.eq(10), UInt.lit(0, 4), r.addWrap(1))); // MISTAKE
        output("y", UInt.w(4)).set(r);
    }
}
