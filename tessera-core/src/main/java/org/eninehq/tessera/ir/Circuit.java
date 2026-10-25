package org.eninehq.tessera.ir;

import java.util.ArrayList;
import java.util.List;

/** An elaborated design: the top module and, transitively, all instantiated modules. */
public record Circuit(ModuleDef top) {

    /** All module definitions, children before parents (post-order). */
    public List<ModuleDef> modulesBottomUp() {
        var out = new ArrayList<ModuleDef>();
        collect(top, out);
        return out;
    }

    private static void collect(ModuleDef m, List<ModuleDef> out) {
        for (var i : m.instances()) collect(i.def(), out);
        out.add(m);
    }
}
