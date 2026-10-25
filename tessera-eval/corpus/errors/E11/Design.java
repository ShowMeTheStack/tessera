import org.eninehq.tessera.core.*;
import org.eninehq.tessera.core.Module;
import static org.eninehq.tessera.core.Hw.*;
import org.eninehq.tessera.pipeline.*;

sealed interface Op permits Op.Add, Op.Neg, Op.Nop {
    record Add(UInt a, UInt b) implements Op {}
    record Neg(UInt a) implements Op {}
    record Nop() implements Op {}
}

public class Design extends Module {
    public Design() {
        var t = HwUnion.of(Op.class, HwUnion.variant(Op.Add.class, UInt.w(8), UInt.w(8)),
                HwUnion.variant(Op.Neg.class, UInt.w(8)), HwUnion.variant(Op.Nop.class));
        HwUnion<Op> op = input("op", t);
        UInt y = output("y", UInt.w(8));
        y.set(op.match(o -> switch (o) { // MISTAKE
            case Op.Add a -> a.a().addWrap(a.b());
            case Op.Neg n -> UInt.lit(0, 8).subWrap(n.a());
        }));
    }
}
