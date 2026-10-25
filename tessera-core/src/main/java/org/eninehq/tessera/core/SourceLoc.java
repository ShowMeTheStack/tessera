package org.eninehq.tessera.core;

import java.util.List;
import java.util.Set;

/** A location in the user's Java source, captured while elaborating. */
public record SourceLoc(String className, String method, String file, int line) {

    public static final SourceLoc UNKNOWN = new SourceLoc("?", "?", "?", -1);

    private static final StackWalker WALKER = StackWalker.getInstance();
    private static final StackWalker CLASS_WALKER = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);
    private static final List<String> INTERNAL_PREFIXES = List.of(
            "org.eninehq.tessera.core.", "org.eninehq.tessera.ir.", "org.eninehq.tessera.pipeline.",
            "org.eninehq.tessera.stdlib.",
            "java.", "jdk.", "sun.");
    private static final Set<String> extraInternal = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Treat classes with this name prefix as library-internal when locating user code. */
    public static void registerInternalPrefix(String prefix) {
        extraInternal.add(prefix);
    }

    /** The innermost stack frame that belongs to user code. */
    public static SourceLoc here() {
        return WALKER.walk(s -> s.filter(f -> !isInternal(f.getClassName()))
                .findFirst()
                .map(f -> new SourceLoc(f.getClassName(), f.getMethodName(),
                        f.getFileName() == null ? "?" : f.getFileName(), f.getLineNumber()))
                .orElse(UNKNOWN));
    }

    /** The innermost user frame outside the constructors of {@code constructed} and its superclasses: where its {@code new} is. */
    public static SourceLoc callerOfConstructor(Class<?> constructed) {
        return CLASS_WALKER.walk(s -> s.filter(f -> !isInternal(f.getClassName()))
                .filter(f -> !(f.getMethodName().equals("<init>") && f.getDeclaringClass().isAssignableFrom(constructed)))
                .findFirst()
                .map(f -> new SourceLoc(f.getClassName(), f.getMethodName(),
                        f.getFileName() == null ? "?" : f.getFileName(), f.getLineNumber()))
                .orElse(UNKNOWN));
    }

    static boolean isInternal(String cls) {
        for (var p : INTERNAL_PREFIXES) if (cls.startsWith(p)) return true;
        for (var p : extraInternal) if (cls.startsWith(p)) return true;
        return false;
    }

    @Override
    public String toString() {
        return line < 0 ? "<unknown>" : file + ":" + line;
    }
}
