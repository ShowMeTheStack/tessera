package org.eninehq.tessera.fpga;

import org.eninehq.tessera.sim.Simulator;

/** The SoC in the simulator, driven through its AXI-Lite port like the board. */
final class SimBus implements SocBus {
    private final AxiDriver axi;

    SimBus(Simulator sim, RvSoc soc) {
        axi = new AxiDriver(sim, soc.sAxi);
        sim.poke("aresetn", 0);
        sim.step(2);
        sim.poke("aresetn", 1);
    }

    @Override
    public void write(long offset, long value) {
        axi.write(offset, value);
    }

    @Override
    public long read(long offset) {
        return axi.read(offset);
    }
}
