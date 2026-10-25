package org.eninehq.tessera.ir;

import org.eninehq.tessera.core.SourceLoc;

/**
 * A formal property checked on every clock edge of {@code domain} while {@code enable} holds
 * (and reset is not active). Simulators check asserts too; formal backends check all three kinds.
 */
public record Assertion(Kind kind, String name, Expr cond, Expr enable, ClockDomainDef domain, SourceLoc loc) {
    public enum Kind { ASSERT, ASSUME, COVER }
}
