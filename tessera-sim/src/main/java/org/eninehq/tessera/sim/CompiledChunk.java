package org.eninehq.tessera.sim;

/** Implemented by generated classes: evaluates a contiguous range of netlist instructions. */
public interface CompiledChunk {
    void eval(long[] v, long[][] mems);
}
