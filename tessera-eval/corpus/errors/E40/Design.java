import org.eninehq.tessera.core.*;
import org.eninehq.tessera.core.Module;
import static org.eninehq.tessera.core.Hw.*;
import org.eninehq.tessera.pipeline.*;

public class Design extends Module {
    public Design() {
        ClockDomain fast = clockDomain("fast");
        UInt a = withClock(fast, () -> reg("a", UInt.w(8), 0));
        a.set(a.addWrap(1));
        UInt b = reg("b", UInt.w(8), 0);
        b.set(a); // MISTAKE
        output("y", UInt.w(8)).set(b);
    }
}
