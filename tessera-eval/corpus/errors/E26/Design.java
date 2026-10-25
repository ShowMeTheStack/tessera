import org.eninehq.tessera.core.*;
import org.eninehq.tessera.core.Module;
import static org.eninehq.tessera.core.Hw.*;
import org.eninehq.tessera.pipeline.*;

public class Design extends Module {
    public Design() {
        Bool c = input("c", Bool.TYPE);
        UInt y = output("y", UInt.w(8));
        var w = when(c, () -> y.set(1));
        y.set(2);
        w.otherwise(() -> y.set(3)); // MISTAKE
    }
}
