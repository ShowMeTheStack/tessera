import org.eninehq.tessera.core.*;
import org.eninehq.tessera.core.Module;
import static org.eninehq.tessera.core.Hw.*;
import org.eninehq.tessera.pipeline.*;

public class Design extends Module {
    public Design() {
        Bool c = input("c", Bool.TYPE);
        SInt s = input("s", SInt.w(8));
        UInt u = input("u", UInt.w(8));
        Data y = mux(c, (Data) s, (Data) u); // MISTAKE
        output("y", UInt.w(8)).set(u);
    }
}
