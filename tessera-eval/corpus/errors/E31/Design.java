import org.eninehq.tessera.core.*;
import org.eninehq.tessera.core.Module;
import static org.eninehq.tessera.core.Hw.*;
import org.eninehq.tessera.pipeline.*;

@Latency(2)
class Mul2 extends Module {
    final UInt a = input("a", UInt.w(8)), b = input("b", UInt.w(8));
    final UInt out = output("out", UInt.w(16));
    Mul2() {
        var p = Pipeline.begin("m");
        UInt prod = p.input(a).mul(p.input(b));
        p.stage();
        p.stage();
        out.set(prod);
        p.end();
    }
}

public class Design extends Module {
    public Design() {
        UInt x = input("x", UInt.w(8));
        UInt y = output("y", UInt.w(16));
        var p = Pipeline.begin("outer");
        UInt xs = p.input(x);
        Mul2 m = p.inst(3, Mul2::new); // MISTAKE
        m.a.set(xs);
        m.b.set(xs);
        p.stage();
        p.stage();
        p.stage();
        y.set(m.out);
        p.end();
    }
}
