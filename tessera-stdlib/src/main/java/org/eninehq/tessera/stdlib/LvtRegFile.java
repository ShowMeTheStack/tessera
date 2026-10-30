package org.eninehq.tessera.stdlib;

import static org.eninehq.tessera.core.Hw.when;

import java.util.ArrayList;
import java.util.List;
import org.eninehq.tessera.core.Bool;
import org.eninehq.tessera.core.Module;
import org.eninehq.tessera.core.UInt;

/**
 * {@link RegFile}'s ports (combinational reads, several write ports, the later write port winning) built as a
 * live-value table ({@link LiveValueTable}): one-write LUT RAMs instead of registers. No same-cycle bypass.
 */
public final class LvtRegFile extends Module {
    public final List<RegFile.ReadPort> read = new ArrayList<>();
    public final List<RegFile.WritePort> write = new ArrayList<>();
    /** The table and its banks (for proofs that look inside). */
    public final LiveValueTable lvt;

    public LvtRegFile(int count, int width, int reads, int writes, boolean zeroReg) {
        int aw = Math.max(1, 32 - Integer.numberOfLeadingZeros(count - 1));
        for (int i = 0; i < writes; i++)
            write.add(new RegFile.WritePort(input("w" + i + "_en", Bool.TYPE), input("w" + i + "_addr", UInt.w(aw)),
                    input("w" + i + "_data", UInt.w(width))));
        for (int i = 0; i < reads; i++)
            read.add(new RegFile.ReadPort(input("r" + i + "_addr", UInt.w(aw)), output("r" + i + "_data", UInt.w(width))));
        lvt = new LiveValueTable("regs", count, width, zeroReg);
        for (var w : write) when(w.en(), () -> lvt.write(w.addr(), w.data()));
        for (var r : read) r.data().set(lvt.read(r.addr()));
        lvt.close();
    }
}
