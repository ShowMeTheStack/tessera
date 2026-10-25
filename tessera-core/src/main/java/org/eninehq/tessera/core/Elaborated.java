package org.eninehq.tessera.core;

import org.eninehq.tessera.ir.Circuit;

/** The result of elaborating a design: the top module object and its IR. */
public record Elaborated<M extends Module>(M top, Circuit circuit) {}
