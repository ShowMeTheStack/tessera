package org.eninehq.tessera.core;

import org.eninehq.tessera.ir.Expr;

/**
 * Internal SPI used by the pipeline library: while installed on a module, every read of a
 * value goes through {@link #adapt}, and every new value is tagged with {@link #currentStage}.
 */
public interface StageHook {

    /** Adapt a read of {@code d}, already resolved to {@code e} in the current module. */
    Expr adapt(Data d, Expr e);

    int currentStage();

    Object domain();
}
