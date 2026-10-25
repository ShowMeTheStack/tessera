import org.eninehq.tessera.core.*;
import org.eninehq.tessera.core.Module;
import static org.eninehq.tessera.core.Hw.*;
import org.eninehq.tessera.pipeline.*;

public class Design extends Module {
    public Design() {
        ClockDomain fast = clockDomain("fast");
        UInt a = withClock(fast, () -> reg("a", UInt.w(4), 0));
        a.set(a.addWrap(1));
        Bool b0 = synchronize(a.bit(0), 2), b1 = synchronize(a.bit(1), 2); // MISTAKE
        output("y", UInt.w(2)).set(cat(b1, b0));
    }
}
