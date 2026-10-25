package org.eninehq.tessera.stdlib;

import static org.eninehq.tessera.core.Hw.when;

import org.eninehq.tessera.core.Bool;
import org.eninehq.tessera.core.Bundle;
import org.eninehq.tessera.core.Hw;
import org.eninehq.tessera.core.Shape;
import org.eninehq.tessera.core.UInt;

/**
 * AXI4-Lite (32-bit data). As an input of a module named {@code s_axi}, the port names
 * ({@code s_axi_awaddr}, {@code s_axi_wvalid}, ...) are the ones Vivado recognises, so the
 * module drops into a block design as an AXI slave.
 */
public record AxiLite(UInt awaddr, Bool awvalid, Bool awready, UInt wdata, UInt wstrb, Bool wvalid, Bool wready,
                      UInt bresp, Bool bvalid, Bool bready, UInt araddr, Bool arvalid, Bool arready,
                      UInt rdata, UInt rresp, Bool rvalid, Bool rready) {

    /** The slave's view: master-driven signals in, slave-driven signals out. */
    public static Shape<AxiLite> slave(int addrBits) {
        Shape<Bool> out = Shape.flip(Bool.TYPE);
        return Bundle.of(AxiLite.class, UInt.w(addrBits), Bool.TYPE, out, UInt.w(32), UInt.w(4), Bool.TYPE, out,
                Shape.flip(UInt.w(2)), out, Bool.TYPE, UInt.w(addrBits), Bool.TYPE, out,
                Shape.flip(UInt.w(32)), Shape.flip(UInt.w(2)), out, Bool.TYPE);
    }

    /** A simple request interface on top of an AXI-Lite slave port. */
    public record Requests(Bool write, UInt writeAddr, UInt writeData, Bool read, UInt readAddr) {}

    /**
     * Serve {@code bus} (call inside the module that owns it). One write or read at a time;
     * {@code write} pulses for one cycle with address and data; {@code read} pulses with the
     * address, which stays in {@code readAddr} until the response; {@code readData} must be valid
     * {@code readLatency} cycles after the pulse. Responses are always OKAY.
     */
    public static Requests serve(AxiLite bus, java.util.function.Function<Requests, UInt> readData, int readLatency) {
        int aw = bus.awaddr().width();
        Bool bvalid = Hw.register("axi_bvalid", Bool.TYPE, 0L);
        Bool rvalid = Hw.register("axi_rvalid", Bool.TYPE, 0L);
        UInt rdata = Hw.register("axi_rdata", UInt.w(32), 0L);
        UInt raddr = Hw.register("axi_raddr", UInt.w(aw), 0L);
        UInt rwait = Hw.register("axi_rwait", UInt.w(4), 0L);
        Bool rbusy = Hw.register("axi_rbusy", Bool.TYPE, 0L);

        Bool takeWrite = bus.awvalid().and(bus.wvalid()).and(bvalid.not()).named("axi_take_write");
        bus.awready().set(takeWrite);
        bus.wready().set(takeWrite);
        when(takeWrite, () -> bvalid.set(true)).elseWhen(bus.bready(), () -> bvalid.set(false));
        bus.bvalid().set(bvalid);
        bus.bresp().set(0);

        Bool takeRead = bus.arvalid().and(rbusy.not()).and(rvalid.not()).and(takeWrite.not()).named("axi_take_read");
        bus.arready().set(takeRead);
        when(takeRead, () -> {
            raddr.set(bus.araddr());
            rbusy.set(true);
            rwait.set(readLatency);
        });
        var req = new Requests(takeWrite, bus.awaddr(), bus.wdata(), takeRead, Hw.mux(takeRead, bus.araddr(), raddr));
        UInt data = readData.apply(req);
        when(rbusy.and(rwait.eq(1)), () -> {
            rdata.set(data);
            rvalid.set(true);
            rbusy.set(false);
        }).elseWhen(rbusy, () -> rwait.set(rwait.subWrap(1)));
        when(rvalid.and(bus.rready()), () -> rvalid.set(false));
        bus.rvalid().set(rvalid);
        bus.rdata().set(rdata);
        bus.rresp().set(0);
        return req;
    }
}
