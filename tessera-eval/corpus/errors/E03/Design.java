import org.eninehq.tessera.core.*;
import org.eninehq.tessera.core.Module;
import static org.eninehq.tessera.core.Hw.*;
import org.eninehq.tessera.pipeline.*;

public class Design extends Module {
    public Design() {
        UInt a = input("a", UInt.w(8));
        UInt y = output("y", UInt.w(8));
        y.set(a.bits(9, 2)); // MISTAKE
    }
}
