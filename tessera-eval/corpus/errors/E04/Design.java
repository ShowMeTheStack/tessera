import org.eninehq.tessera.core.*;
import org.eninehq.tessera.core.Module;
import static org.eninehq.tessera.core.Hw.*;
import org.eninehq.tessera.pipeline.*;

public class Design extends Module {
    public Design() {
        UInt y = output("y", UInt.w(8));
        y.set(UInt.lit(300, 8)); // MISTAKE
    }
}
