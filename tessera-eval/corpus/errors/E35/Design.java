import org.eninehq.tessera.core.*;
import org.eninehq.tessera.core.Module;
import static org.eninehq.tessera.core.Hw.*;
import org.eninehq.tessera.pipeline.*;

public class Design extends Module {
    public Design() {
        UInt x = input("x", UInt.w(8));
        var p = Pipeline.begin("p");
        UInt a = p.input(x);
        @Stage(1) UInt late = a.addWrap(1);
        UInt early = late.addWrap(1); // MISTAKE
        p.stage();
        output("y", UInt.w(8)).set(early);
        p.end();
    }
}
