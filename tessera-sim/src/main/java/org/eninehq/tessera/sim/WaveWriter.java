package org.eninehq.tessera.sim;

/** A waveform output format. One sample per clock edge; each cycle is 10 time units. */
interface WaveWriter extends AutoCloseable {
    void sample(long cycle, long[] values);

    @Override void close();
}
