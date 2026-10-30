package org.eninehq.tessera.sim;

/** Implemented by generated classes: a contiguous range of {@link BatchSim}'s gate program. */
interface BatchChunk {
    void eval(long[] v, BatchSim sim);
}
