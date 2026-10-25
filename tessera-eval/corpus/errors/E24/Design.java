import org.eninehq.tessera.core.*;
import org.eninehq.tessera.core.Module;
import static org.eninehq.tessera.core.Hw.*;
import org.eninehq.tessera.pipeline.*;

class Child extends Module {
    Child(UInt fromParent) {
        output("y", UInt.w(8)).set(fromParent); // MISTAKE
    }
}

public class Design extends Module {
    public Design() {
        UInt a = input("a", UInt.w(8));
        Child c = instance("c", () -> new Child(a));
        output("y", UInt.w(8)).set(UInt.lit(0, 8));
    }
}
