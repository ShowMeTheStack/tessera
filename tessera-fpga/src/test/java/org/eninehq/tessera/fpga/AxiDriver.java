package org.eninehq.tessera.fpga;

import org.eninehq.tessera.sim.Simulator;
import org.eninehq.tessera.stdlib.AxiLite;

/** Drives an AXI-Lite slave in simulation, one transaction at a time, like the Zynq's GP0 port. */
final class AxiDriver {
    private final Simulator sim;
    private final AxiLite bus;

    AxiDriver(Simulator sim, AxiLite bus) {
        this.sim = sim;
        this.bus = bus;
        sim.poke(bus.awvalid(), 0);
        sim.poke(bus.wvalid(), 0);
        sim.poke(bus.arvalid(), 0);
        sim.poke(bus.bready(), 1);
        sim.poke(bus.rready(), 1);
        sim.poke(bus.wstrb(), 0xF);
    }

    void write(long addr, long data) {
        sim.poke(bus.awaddr(), addr);
        sim.poke(bus.wdata(), data);
        sim.poke(bus.awvalid(), 1);
        sim.poke(bus.wvalid(), 1);
        for (int i = 0; sim.peek(bus.awready()) == 0; i++) {
            if (i > 100) throw new AssertionError("no awready");
            sim.step();
        }
        sim.step();
        sim.poke(bus.awvalid(), 0);
        sim.poke(bus.wvalid(), 0);
        for (int i = 0; sim.peek(bus.bvalid()) == 0; i++) {
            if (i > 100) throw new AssertionError("no bvalid");
            sim.step();
        }
        sim.step();
    }

    long read(long addr) {
        sim.poke(bus.araddr(), addr);
        sim.poke(bus.arvalid(), 1);
        for (int i = 0; sim.peek(bus.arready()) == 0; i++) {
            if (i > 100) throw new AssertionError("no arready");
            sim.step();
        }
        sim.step();
        sim.poke(bus.arvalid(), 0);
        for (int i = 0; sim.peek(bus.rvalid()) == 0; i++) {
            if (i > 100) throw new AssertionError("no rvalid");
            sim.step();
        }
        long v = sim.peek(bus.rdata());
        sim.step();
        return v;
    }
}
