package org.eninehq.tessera.stdlib;

import static org.eninehq.tessera.core.Hw.when;

import org.eninehq.tessera.core.Bool;
import org.eninehq.tessera.core.Mem;
import org.eninehq.tessera.core.Module;
import org.eninehq.tessera.core.UInt;

/**
 * Block-RAM style memories with synchronous reads (data one cycle after the address), the
 * shape FPGA tools map to BRAM. {@link #singlePort} is read-first; {@link #simpleDualPort}
 * has one write and one read port.
 */
public final class Ram extends Module {
    public final UInt raddr, rdata;
    public final UInt waddr, wdata;
    public final Bool we, re;
    /** Byte-write enables when {@code byteWrites}; otherwise null. */
    public final UInt wstrb;

    private Ram(int depth, int width, boolean dual, boolean byteWrites, long[] init) {
        int aw = Math.max(1, 32 - Integer.numberOfLeadingZeros(depth - 1));
        raddr = input(dual ? "raddr" : "addr", UInt.w(aw));
        waddr = dual ? input("waddr", UInt.w(aw)) : raddr;
        we = input("we", Bool.TYPE);
        re = input("re", Bool.TYPE);
        wdata = input("wdata", UInt.w(width));
        wstrb = byteWrites ? input("wstrb", UInt.w(width / 8)) : null;
        rdata = output("rdata", UInt.w(width));
        if (byteWrites) {
            if (width % 8 != 0) throw new IllegalArgumentException("byte writes need a multiple of 8 bits");
            int lanes = width / 8;
            UInt[] lanesOut = new UInt[lanes];
            for (int l = 0; l < lanes; l++) {
                final int lane = l;
                Mem<UInt> m = mem("lane" + l, UInt.w(8), depth);
                if (init != null) {
                    long[] li = new long[init.length];
                    for (int k = 0; k < init.length; k++) li[k] = init[k] >>> (8 * lane) & 0xFF;
                    m.init(li);
                }
                when(we.and(wstrb.bit(lane)), () -> m.write(waddr, wdata.bits(8 * lane + 7, 8 * lane)));
                lanesOut[l] = m.readSync(raddr, re);
            }
            UInt v = lanesOut[lanes - 1];
            for (int l = lanes - 2; l >= 0; l--) v = v.cat(lanesOut[l]);
            rdata.set(v);
        } else {
            Mem<UInt> m = mem("mem", UInt.w(width), depth);
            if (init != null) m.init(init);
            when(we, () -> m.write(waddr, wdata));
            rdata.set(m.readSync(raddr, re));
        }
    }

    public static Ram singlePort(int depth, int width) {
        return new Ram(depth, width, false, false, null);
    }

    public static Ram simpleDualPort(int depth, int width) {
        return new Ram(depth, width, true, false, null);
    }

    /** Dual-port RAM with per-byte write enables and optional initial contents (words). */
    public static Ram simpleDualPortBytes(int depth, int width, long[] init) {
        return new Ram(depth, width, true, true, init);
    }
}
