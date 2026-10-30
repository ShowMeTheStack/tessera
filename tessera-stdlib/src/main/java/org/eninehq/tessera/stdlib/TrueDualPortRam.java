package org.eninehq.tessera.stdlib;

import org.eninehq.tessera.core.Bool;
import org.eninehq.tessera.core.Mem;
import org.eninehq.tessera.core.Module;
import org.eninehq.tessera.core.UInt;

/**
 * A true dual-port RAM ({@link Mem#port}): ports a and b each read (read-first, data one cycle after the address)
 * and write their own address. Both ports writing one address in a cycle is undefined and stops a simulation.
 * Emitted in the vendors' template, so FPGA tools map it to one block RAM (or a cascade) with both ports in use.
 */
public final class TrueDualPortRam extends Module {
    public final UInt addrA, wdataA, rdataA, addrB, wdataB, rdataB;
    public final Bool enA, weA, enB, weB;
    public final Mem<UInt> mem;

    public TrueDualPortRam(int depth, int width) {
        int aw = Math.max(1, 32 - Integer.numberOfLeadingZeros(depth - 1));
        addrA = input("addr_a", UInt.w(aw));
        enA = input("en_a", Bool.TYPE);
        weA = input("we_a", Bool.TYPE);
        wdataA = input("wdata_a", UInt.w(width));
        rdataA = output("rdata_a", UInt.w(width));
        addrB = input("addr_b", UInt.w(aw));
        enB = input("en_b", Bool.TYPE);
        weB = input("we_b", Bool.TYPE);
        wdataB = input("wdata_b", UInt.w(width));
        rdataB = output("rdata_b", UInt.w(width));
        mem = mem("mem", UInt.w(width), depth);
        rdataA.set(mem.port(addrA, enA, weA, wdataA));
        rdataB.set(mem.port(addrB, enB, weB, wdataB));
    }
}
