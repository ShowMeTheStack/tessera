import org.eninehq.tessera.core.*;
import org.eninehq.tessera.core.Module;
import static org.eninehq.tessera.core.Hw.*;
import org.eninehq.tessera.pipeline.*;

public class Design extends Module {
    public Design() {
        Bool c = input("c", Bool.TYPE);
        UInt x = input("x", UInt.w(8));
        var p = Pipeline.begin("p");
        UInt a = p.input(x);
        when(c, () -> p.stage()); // MISTAKE
        output("y", UInt.w(8)).set(a);
        p.end();
    }
}
