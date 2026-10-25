import org.eninehq.tessera.core.*;
import org.eninehq.tessera.core.Module;
import static org.eninehq.tessera.core.Hw.*;
import org.eninehq.tessera.pipeline.*;

public class Design extends Module {
    public Design() {
        UInt r = reg("r", UInt.w(8), 0);
        r.set(r.addWrap(1));
        useReset(org.eninehq.tessera.ir.ClockDomainDef.ResetKind.ASYNC_HIGH); // MISTAKE
        output("y", UInt.w(8)).set(r);
    }
}
