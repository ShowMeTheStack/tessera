package org.eninehq.tessera.clitests;

import org.eninehq.tessera.core.Module;
import org.eninehq.tessera.core.UInt;

/** A design with a width error, for the CLI's error reporting. */
public class Broken extends Module {
    public Broken() {
        UInt a = input("a", UInt.w(8));
        output("y", UInt.w(8)).set(a.add(a));
    }
}
