package org.eninehq.tessera.riscv;

import org.eninehq.tessera.core.Bool;
import org.eninehq.tessera.core.Module;

/**
 * N copies of one core running the same program, side by side with nothing shared, as a large
 * test design for the multi-threading measurement; {@code halted} when every copy has halted.
 */
final class MultiCore extends Module {
    final Bool halted;
    final int n;

    MultiCore(int n, String coreId, int[] image) {
        this.n = n;
        halted = output("halted", Bool.TYPE);
        Bool all = null;
        for (int k = 0; k < n; k++) {
            RvCore c = instance("core" + k, () -> Cores.get(coreId).build(4096, image));
            // inputs that some configurations have: no interrupt, no debug access
            if (c.irq != null) c.irq.set(Bool.of(false));
            if (c.dbgEn != null) {
                c.dbgEn.set(Bool.of(false));
                c.dbgWe.set(Bool.of(false));
                c.dbgSelDmem.set(Bool.of(false));
                c.dbgAddr.set(org.eninehq.tessera.core.UInt.lit(0, c.dbgAddr.width()));
                c.dbgWdata.set(org.eninehq.tessera.core.UInt.lit(0, c.dbgWdata.width()));
            }
            all = all == null ? c.halted : all.and(c.halted);
        }
        halted.set(all);
    }
}
