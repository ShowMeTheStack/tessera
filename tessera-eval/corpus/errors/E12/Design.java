import org.eninehq.tessera.core.*;
import org.eninehq.tessera.core.Module;
import static org.eninehq.tessera.core.Hw.*;
import org.eninehq.tessera.pipeline.*;

public class Design extends Module {
    public Design() {
        Vec<UInt> v = input("v", Vec.of(4, UInt.w(8)));
        output("y", UInt.w(8)).set(v.get(5)); // MISTAKE
    }
}
