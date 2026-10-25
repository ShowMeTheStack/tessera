import org.eninehq.tessera.core.*;
import org.eninehq.tessera.core.Module;
import static org.eninehq.tessera.core.Hw.*;
import org.eninehq.tessera.pipeline.*;

public class Design extends Module {
    public Design() {
        UInt x = input("x", UInt.w(8));
        var p = Pipeline.begin("p");
        Late<UInt> fwd = p.late(UInt.w(8)); // MISTAKE
        UInt a = p.input(x).addWrap(fwd.get());
        p.stage();
        output("y", UInt.w(8)).set(a);
        p.end();
    }
}
