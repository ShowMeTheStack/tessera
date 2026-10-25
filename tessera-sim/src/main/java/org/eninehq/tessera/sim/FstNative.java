package org.eninehq.tessera.sim;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Bindings (Java FFM API) to GTKWave's reference FST library, {@code fstapi.c}. Tessera does
 * not ship a binary: on first use it compiles the library from the copy bundled with
 * Verilator into {@code ~/.cache/tessera}, or loads the file named by
 * {@code -Dtessera.fstlib}.
 */
public final class FstNative {
    private FstNative() {}

    static final int ST_VCD_MODULE = 0;
    static final int VT_VCD_WIRE = 16;
    static final int VT_SV_ENUM = 28;
    static final int VD_IMPLICIT = 0, VD_INPUT = 1, VD_OUTPUT = 2;

    private static volatile Lib lib;
    private static volatile String failure;

    /** Method handles of the functions Tessera uses. */
    record Lib(MethodHandle writerCreate, MethodHandle setTimescale, MethodHandle setScope, MethodHandle setUpscope,
               MethodHandle createVar, MethodHandle emitTime, MethodHandle emitValue, MethodHandle writerClose,
               MethodHandle setSourceStem, MethodHandle createEnumTable, MethodHandle emitEnumTableRef,
               MethodHandle setComment,
               MethodHandle readerOpen, MethodHandle readerClose, MethodHandle readerVarCount,
               MethodHandle readerValueAt, MethodHandle readerMaskAll, MethodHandle readerEndTime) {}

    /** True when the library is (or can be made) available. */
    public static boolean available() {
        try {
            lib();
            return true;
        } catch (IllegalStateException e) {
            return false;
        }
    }

    static Lib lib() {
        if (lib != null) return lib;
        synchronized (FstNative.class) {
            if (lib != null) return lib;
            if (failure != null) throw new IllegalStateException(failure);
            try {
                lib = bind(locate());
                return lib;
            } catch (IOException | InterruptedException | RuntimeException e) {
                failure = "FST support unavailable: " + e.getMessage();
                throw new IllegalStateException(failure, e);
            }
        }
    }

    private static Path locate() throws IOException, InterruptedException {
        String prop = System.getProperty("tessera.fstlib");
        if (prop != null) return Path.of(prop);
        boolean mac = System.getProperty("os.name").toLowerCase().contains("mac");
        Path cache = Path.of(System.getProperty("user.home"), ".cache", "tessera");
        Path out = cache.resolve(mac ? "libtesserafst.dylib" : "libtesserafst.so");
        if (Files.exists(out)) return out;
        Path src = verilatorFstSources();
        Files.createDirectories(cache);
        Path tmp = cache.resolve(out.getFileName() + ".tmp" + ProcessHandle.current().pid());
        var cmd = List.of("cc", "-O2", "-shared", "-fPIC", "-w", "-DFST_CONFIG_INCLUDE=\"fst_config.h\"",
                "-I" + src, src.resolve("fstapi.c").toString(), src.resolve("lz4.c").toString(),
                src.resolve("fastlz.c").toString(), "-lz", "-o", tmp.toString());
        var p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String log = new String(p.getInputStream().readAllBytes());
        if (!p.waitFor(120, TimeUnit.SECONDS) || p.exitValue() != 0) throw new IOException("compiling fstapi failed:\n" + log);
        Files.move(tmp, out, java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        return out;
    }

    private static Path verilatorFstSources() throws IOException, InterruptedException {
        var p = new ProcessBuilder("verilator", "--getenv", "VERILATOR_ROOT").redirectErrorStream(true).start();
        String root = new String(p.getInputStream().readAllBytes()).trim();
        p.waitFor(30, TimeUnit.SECONDS);
        Path src = Path.of(root, "include", "gtkwave");
        if (!Files.exists(src.resolve("fstapi.c")))
            throw new IOException("fstapi.c not found (looked in " + src + "); install Verilator or set -Dtessera.fstlib");
        return src;
    }

    @SuppressWarnings("restricted")
    private static Lib bind(Path file) {
        var lookup = SymbolLookup.libraryLookup(file, Arena.global());
        var l = Linker.nativeLinker();
        java.util.function.BiFunction<String, FunctionDescriptor, MethodHandle> f =
                (name, fd) -> l.downcallHandle(lookup.find(name).orElseThrow(() -> new IllegalStateException("missing " + name)), fd);
        return new Lib(
                f.apply("fstWriterCreate", FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT)),
                f.apply("fstWriterSetTimescale", FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT)),
                f.apply("fstWriterSetScope", FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, ADDRESS, ADDRESS)),
                f.apply("fstWriterSetUpscope", FunctionDescriptor.ofVoid(ADDRESS)),
                f.apply("fstWriterCreateVar", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT)),
                f.apply("fstWriterEmitTimeChange", FunctionDescriptor.ofVoid(ADDRESS, JAVA_LONG)),
                f.apply("fstWriterEmitValueChange", FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, ADDRESS)),
                f.apply("fstWriterClose", FunctionDescriptor.ofVoid(ADDRESS)),
                f.apply("fstWriterSetSourceStem", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT, JAVA_INT)),
                f.apply("fstWriterCreateEnumTable", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS)),
                f.apply("fstWriterEmitEnumTableRef", FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT)),
                f.apply("fstWriterSetComment", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS)),
                f.apply("fstReaderOpen", FunctionDescriptor.of(ADDRESS, ADDRESS)),
                f.apply("fstReaderClose", FunctionDescriptor.ofVoid(ADDRESS)),
                f.apply("fstReaderGetVarCount", FunctionDescriptor.of(JAVA_LONG, ADDRESS)),
                f.apply("fstReaderGetValueFromHandleAtTime", FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_LONG, JAVA_INT, ADDRESS)),
                f.apply("fstReaderSetFacProcessMaskAll", FunctionDescriptor.ofVoid(ADDRESS)),
                f.apply("fstReaderGetEndTime", FunctionDescriptor.of(JAVA_LONG, ADDRESS)));
    }

    /** A minimal FST reader, mainly for tests: values of a variable handle at a time. */
    public static final class Reader implements AutoCloseable {
        private final Arena arena = Arena.ofConfined();
        private final MemorySegment ctx;
        private final MemorySegment buf;

        public Reader(Path file) {
            var l = lib();
            try {
                ctx = (MemorySegment) l.readerOpen().invokeExact(arena.allocateFrom(file.toString()));
                if (ctx.equals(MemorySegment.NULL)) throw new IllegalArgumentException("cannot open " + file);
                l.readerMaskAll().invokeExact(ctx);
            } catch (RuntimeException e) {
                throw e;
            } catch (Throwable t) {
                throw new IllegalStateException(t);
            }
            buf = arena.allocate(1 << 16);
        }

        public long varCount() {
            try {
                return (long) lib().readerVarCount().invokeExact(ctx);
            } catch (Throwable t) {
                throw new IllegalStateException(t);
            }
        }

        public long endTime() {
            try {
                return (long) lib().readerEndTime().invokeExact(ctx);
            } catch (Throwable t) {
                throw new IllegalStateException(t);
            }
        }

        /** The value of variable {@code handle} (1-based, in creation order) at {@code time}, as a bit string. */
        public String valueAt(long time, int handle) {
            try {
                var r = (MemorySegment) lib().readerValueAt().invokeExact(ctx, time, handle, buf);
                return r.equals(MemorySegment.NULL) ? null : buf.getString(0);
            } catch (Throwable t) {
                throw new IllegalStateException(t);
            }
        }

        @Override public void close() {
            try {
                lib().readerClose().invokeExact(ctx);
            } catch (Throwable t) {
                throw new IllegalStateException(t);
            } finally {
                arena.close();
            }
        }
    }
}
