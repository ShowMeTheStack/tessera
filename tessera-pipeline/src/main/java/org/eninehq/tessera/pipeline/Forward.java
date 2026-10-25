package org.eninehq.tessera.pipeline;

/**
 * Where a result point ({@link InOrderModel#resultAfter(String, Key, Key, Forward)}) is forwarded from.
 */
public enum Forward {
    /** From the stage whose step computes it: no extra stall, but the step's logic is on the forwarding path. */
    SAME_STAGE,
    /**
     * Only from the pipeline register after that stage: dependent instructions may stall a cycle longer, and the
     * step's logic stays off the forwarding path. In the last stage the result is not forwarded at all, and
     * dependent instructions wait until it is in the register file.
     */
    NEXT_STAGE
}
