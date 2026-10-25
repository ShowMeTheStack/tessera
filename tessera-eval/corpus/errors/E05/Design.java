import org.eninehq.tessera.core.*;
import org.eninehq.tessera.core.Module;
import static org.eninehq.tessera.core.Hw.*;
import org.eninehq.tessera.pipeline.*;

public class Design extends Module {
    public Design() {
        SInt s = input("s", SInt.w(8));
        SInt y = output("y", SInt.w(4));
        y.set(s.sext(4)); // MISTAKE
    }
}
