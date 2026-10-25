import org.eninehq.tessera.core.*;
import org.eninehq.tessera.core.Module;
import static org.eninehq.tessera.core.Hw.*;
import org.eninehq.tessera.pipeline.*;

class Child extends Module {
    Child() { output("y", UInt.w(8)).set(UInt.lit(1, 8)); }
}

public class Design extends Module {
    public Design() {
        Child c = new Child(); // MISTAKE
        output("y", UInt.w(8)).set(UInt.lit(0, 8));
    }
}
