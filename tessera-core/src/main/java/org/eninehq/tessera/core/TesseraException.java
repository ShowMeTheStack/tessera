package org.eninehq.tessera.core;

import java.util.List;
import java.util.stream.Collectors;

/** An elaboration error, carrying one or more diagnostics that point at user source lines. */
public class TesseraException extends RuntimeException {

    /**
     * A suggested edit of the source line a diagnostic points at: replace the first match of
     * the regular expression {@code find} on that line with {@code replace} (a regex replacement).
     */
    public record Fix(String description, String find, String replace) {}

    public record Diagnostic(SourceLoc loc, String message, List<Fix> fixes) {
        public Diagnostic(SourceLoc loc, String message) {
            this(loc, message, List.of());
        }

        @Override public String toString() { return loc + ": error: " + message; }
    }

    private final List<Diagnostic> diagnostics;

    public TesseraException(List<Diagnostic> diagnostics) {
        super(diagnostics.stream().map(Diagnostic::toString).collect(Collectors.joining("\n")));
        this.diagnostics = List.copyOf(diagnostics);
    }

    public TesseraException(SourceLoc loc, String message) {
        this(List.of(new Diagnostic(loc, message)));
    }

    public TesseraException(SourceLoc loc, String message, List<Fix> fixes) {
        this(List.of(new Diagnostic(loc, message, fixes)));
    }

    /** An error located at the innermost user stack frame. */
    public static TesseraException here(String message) {
        return new TesseraException(SourceLoc.here(), message);
    }

    /** An error with suggested fixes, located at the innermost user stack frame. */
    public static TesseraException here(String message, Fix... fixes) {
        return new TesseraException(SourceLoc.here(), message, List.of(fixes));
    }

    public List<Diagnostic> diagnostics() { return diagnostics; }
}
