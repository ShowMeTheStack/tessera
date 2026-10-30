package org.eninehq.tessera.pipeline;

import org.eninehq.tessera.core.Mem;
import org.eninehq.tessera.core.UInt;

/**
 * The register file a pipeline generator reads (combinationally, one port per {@link #read}) and writes (one port per
 * {@link #write}, under the enclosing when conditions). A memory ({@link #of}) or another implementation, such as a
 * live-value table built from one-write RAMs, declared by the model with
 * {@link InOrderModel#registerFile(RegisterFile, boolean)}.
 */
public interface RegisterFile {
    UInt read(UInt idx);

    void write(UInt idx, UInt value);

    int width();

    int addrWidth();

    /** The number of registers (a power of two unless the implementation says otherwise). */
    default int depth() { return 1 << addrWidth(); }

    /** Called by a generator that will declare two or more write ports: a memory is then built from registers. */
    default void severalWritePorts() {}

    /** A memory as the register file. */
    static RegisterFile of(Mem<UInt> mem) {
        return new RegisterFile() {
            @Override public UInt read(UInt idx) { return mem.read(idx); }
            @Override public void write(UInt idx, UInt value) { mem.write(idx, value); }
            @Override public int width() { return mem.def().width(); }
            @Override public int addrWidth() { return mem.addrWidth(); }
            @Override public int depth() { return mem.depth(); }
            // two write ports: no FPGA RAM has those, so the register file is built from registers
            @Override public void severalWritePorts() { mem.asRegisters(); }
        };
    }
}
