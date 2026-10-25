import org.eninehq.tessera.core.*;
import org.eninehq.tessera.core.Module;
import static org.eninehq.tessera.core.Hw.*;
import org.eninehq.tessera.pipeline.*;

public class Design extends Module {
    public Design() {
        Bool c = input("c", Bool.TYPE);
        UInt a = input("a", UInt.w(8));
        UInt w = wire("w", UInt.w(8));
        when(c, () -> w.set(a)); // MISTAKE
        output("y", UInt.w(8)).set(w);
    }
}
