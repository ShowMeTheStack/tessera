package org.eninehq.tessera.gradle;

import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;

/**
 * The {@code tessera { }} block.
 * <pre>
 * tessera {
 *     tops.add("com.example.Designs::blinky")
 *     synthTarget.set("xc7")
 *     formalDepth.set(20)
 * }
 * </pre>
 */
public abstract class TesseraExtension {
    /** Designs: module classes with a no-argument constructor, or {@code Class::staticMethod}. */
    public abstract ListProperty<String> getTops();

    /** Yosys target for {@code tesseraSynth}: generic, xc7, ice40 or ecp5. */
    public abstract Property<String> getSynthTarget();

    /** Bound for {@code formal}. */
    public abstract Property<Integer> getFormalDepth();

    /** If set, {@code formal} also tries k-induction with this k. */
    public abstract Property<Integer> getProveDepth();

    /** Cycles for {@code tesseraSim}. */
    public abstract Property<Integer> getSimCycles();

    /** Board profile for {@code flash} (zedboard, arty-a7, icebreaker, ...). */
    public abstract Property<String> getBoard();

    /** Bitstream for {@code flash}. */
    public abstract Property<String> getBitstream();

    /**
     * If set ({@code user@host}), {@code flash} copies the bitstream there and runs openFPGALoader
     * over ssh: for a board plugged into another machine.
     */
    public abstract Property<String> getFlashHost();
}
