import org.eninehq.tessera.core.*;
import org.eninehq.tessera.core.Module;
import static org.eninehq.tessera.core.Hw.*;
import org.eninehq.tessera.pipeline.*;

public class Design extends Module {
    public Design() {
        SInt s = input("s", SInt.w(8));
        UInt u = input("u", UInt.w(8));
        Bool y = output("y", Bool.TYPE);
        y.set(s.lt(u)); // MISTAKE
    }
}
