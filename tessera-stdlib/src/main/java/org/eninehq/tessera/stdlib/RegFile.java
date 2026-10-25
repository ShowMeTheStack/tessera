package org.eninehq.tessera.stdlib;

import static org.eninehq.tessera.core.Hw.when;

import java.util.ArrayList;
import java.util.List;
import org.eninehq.tessera.core.Bool;
import org.eninehq.tessera.core.Hw;
import org.eninehq.tessera.core.Mem;
import org.eninehq.tessera.core.Module;
import org.eninehq.tessera.core.UInt;

/**
 * A register file with {@code reads} combinational read ports and {@code writes} write ports.
 * With {@code zeroReg}, register 0 always reads as zero (RISC-V {@code x0}). With
 * {@code bypass}, a read of a register written in the same cycle returns the new value.
 * Later write ports win when two write the same register.
 */
public final class RegFile extends Module {
    public record ReadPort(UInt addr, UInt data) {}
    public record WritePort(Bool en, UInt addr, UInt data) {}

    public final List<ReadPort> read = new ArrayList<>();
    public final List<WritePort> write = new ArrayList<>();

    public RegFile(int count, int width, int reads, int writes, boolean zeroReg, boolean bypass) {
        int aw = Math.max(1, 32 - Integer.numberOfLeadingZeros(count - 1));
        for (int i = 0; i < writes; i++)
            write.add(new WritePort(input("w" + i + "_en", Bool.TYPE), input("w" + i + "_addr", UInt.w(aw)),
                    input("w" + i + "_data", UInt.w(width))));
        for (int i = 0; i < reads; i++)
            read.add(new ReadPort(input("r" + i + "_addr", UInt.w(aw)), output("r" + i + "_data", UInt.w(width))));
        Mem<UInt> regs = mem("regs", UInt.w(width), count);
        for (var w : write) {
            Bool en = zeroReg ? w.en().and(w.addr().ne(0)) : w.en();
            when(en, () -> regs.write(w.addr(), w.data()));
        }
        for (var r : read) {
            UInt v = regs.read(r.addr());
            if (bypass)
                for (var w : write) v = Hw.mux(w.en().and(w.addr().eq(r.addr())), w.data(), v);
            if (zeroReg) v = Hw.mux(r.addr().eq(0), UInt.lit(0, width), v);
            r.data().set(v);
        }
    }
}
