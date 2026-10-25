import org.eninehq.tessera.core.*;
import org.eninehq.tessera.core.Module;
import static org.eninehq.tessera.core.Hw.*;
import org.eninehq.tessera.pipeline.*;

public class Design extends Module {
    public Design() {
        UInt x = input("x", UInt.w(8));
        var p = Pipeline.begin("p");
        UInt a = p.input(x).addWrap(1);
        p.stage();
        UInt b = a.addWrap(1);
        p.end();
        output("y", UInt.w(8)).set(b.addWrap(1)); // MISTAKE
    }
}
