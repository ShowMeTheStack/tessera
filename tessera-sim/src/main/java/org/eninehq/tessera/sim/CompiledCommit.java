package org.eninehq.tessera.sim;

/** Implemented by a generated class: the register update at a clock edge of every domain (see {@link AbstractSim#fastCommit}). */
public interface CompiledCommit {
    void commit(long[] v, long[] tmp);
}
