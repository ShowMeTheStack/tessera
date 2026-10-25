package org.eninehq.tessera.core;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.eninehq.tessera.ir.Expr;

/**
 * Where in the Java each operation was written, recorded only when asked for (the studio's timing view
 * and the emitter's name tables use it). Off by default: elaboration then pays one thread-local read per
 * operation and records nothing.
 */
public final class OpLocations {
    private OpLocations() {}

    private record Rec(Map<Expr, SourceLoc> into, List<String> skip) {}

    private static final ThreadLocal<Rec> ON = new ThreadLocal<>();
    private static final StackWalker WALKER = StackWalker.getInstance();

    /**
     * Runs {@code body} (an elaboration) and records, in {@code into}, the location of every operation it
     * creates: the innermost user frame, skipping frames of classes whose names start with one of {@code
     * skip} (helpers such as a domain adapter that every operation goes through).
     */
    public static <T> T record(Map<Expr, SourceLoc> into, List<String> skip, Supplier<T> body) {
        var old = ON.get();
        ON.set(new Rec(into, List.copyOf(skip)));
        try {
            return body.get();
        } finally {
            if (old == null) ON.remove();
            else ON.set(old);
        }
    }

    static void note(Expr e) {
        var r = ON.get();
        if (r == null) return;
        r.into().put(e, WALKER.walk(s -> s.filter(f -> !SourceLoc.isInternal(f.getClassName()))
                .filter(f -> r.skip().stream().noneMatch(p -> f.getClassName().startsWith(p)))
                .findFirst()
                .map(f -> new SourceLoc(f.getClassName(), f.getMethodName(), f.getFileName() == null ? "?" : f.getFileName(), f.getLineNumber()))
                .orElse(SourceLoc.UNKNOWN)));
    }
}
