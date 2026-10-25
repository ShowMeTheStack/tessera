import org.eninehq.tessera.core.*;
import org.eninehq.tessera.core.Module;
import static org.eninehq.tessera.core.Hw.*;
import org.eninehq.tessera.pipeline.*;

public class Design extends Module {
    public Design() {
        UInt w = wire("w", UInt.w(8));
        w.set(w.addWrap(1)); // MISTAKE
        output("y", UInt.w(8)).set(w);
    }
}
