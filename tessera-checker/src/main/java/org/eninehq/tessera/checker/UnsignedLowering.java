package org.eninehq.tessera.checker;

import com.sun.source.util.JavacTask;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Which compilations also run the optional {@code -Xplugin:TesseraSyntaxLite} plugin, which lowers
 * the operators whose Java meaning is signed ({@code / % < <= > >= >>}, string concatenation,
 * widening to {@code long}) on {@code @Unsigned} values. With it, the {@link UnsignedCheck} does not
 * report those operators. Both plugins are loaded by the same class loader from the processor path,
 * so this class is shared; the set is keyed by the compilation, so the order in which javac runs the
 * two plugins does not matter.
 */
public final class UnsignedLowering {
    private static final Set<JavacTask> TASKS = Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

    private UnsignedLowering() {}

    /** Called by the syntax-lite plugin when javac initialises it for {@code task}. */
    public static void register(JavacTask task) { TASKS.add(task); }

    /** True if the syntax-lite plugin runs in {@code task}. */
    public static boolean active(JavacTask task) { return TASKS.contains(task); }
}
