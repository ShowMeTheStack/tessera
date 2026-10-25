import org.eninehq.tessera.core.*;
import org.eninehq.tessera.core.Module;
import static org.eninehq.tessera.core.Hw.*;
import org.eninehq.tessera.pipeline.*;

public class Design extends Module {
    public Design() {
        ClockDomain fast = clockDomain("fast");
        Bool a = withClock(fast, () -> reg("a", Bool.TYPE, 0));
        a.set(a.not());
        output("y", Bool.TYPE).set(synchronize(a, 1)); // MISTAKE
    }
}
