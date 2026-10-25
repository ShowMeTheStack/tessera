import org.eninehq.tessera.core.*;
import org.eninehq.tessera.core.Module;
import static org.eninehq.tessera.core.Hw.*;
import org.eninehq.tessera.pipeline.*;

public class Design extends Module {
    public Design() {
        UInt a = input("a", UInt.w(8));
        UInt r = regInit("r", a); // MISTAKE
        r.set(r.addWrap(1));
        output("y", UInt.w(8)).set(r);
    }
}
