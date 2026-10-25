package org.eninehq.tessera.notation;

import org.eninehq.tessera.core.Module;
import org.eninehq.tessera.core.UInt;
import org.eninehq.tessera.pipeline.Pipeline;

/** Tessera-javac notation: y = a*b + c in two pipeline stages. */
public class MulAdd extends Module {
    public MulAdd() {
        UInt a = input("a", UInt.w(16)), b = input("b", UInt.w(16)), c = input("c", UInt.w(32));
        var p = Pipeline.begin("mac");
        UInt prod = p.input(a) * p.input(b);
        UInt cc = p.input(c);
        p.stage();
        UInt sum = prod.addWrap(cc);
        p.stage();
        output("y", UInt.w(32)).set(sum);
        p.end();
    }
}
