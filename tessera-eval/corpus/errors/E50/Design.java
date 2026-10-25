import org.eninehq.tessera.core.*;
import org.eninehq.tessera.core.Module;
import static org.eninehq.tessera.core.Hw.*;
import org.eninehq.tessera.pipeline.*;

public class Design extends Module {
    @Fn
    static UInt next(UInt x) {
        UInt r = Hw.register("r", UInt.w(8), 0L); // MISTAKE
        r.set(x);
        return r;
    }

    public Design() {
        UInt a = input("a", UInt.w(8));
        output("y", UInt.w(8)).set(next(a));
    }
}
