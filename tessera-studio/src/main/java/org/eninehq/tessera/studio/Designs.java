package org.eninehq.tessera.studio;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import org.eninehq.tessera.core.Module;

/**
 * Java designs the studio can open and watch: those a project registers through a class with a
 * static {@code studioDesigns()} method returning {@code Map<String, Supplier<? extends Module>>}
 * ({@code --designs com.example.MyDesigns}), and, when tessera-riscv is on the class path, the
 * validation cores, each running a long loop.
 */
public final class Designs {
    private Designs() {}

    /** The cores, by name, each built for a program image (null: the long benchmark loop). */
    static final Map<String, java.util.function.Function<int[], ? extends Module>> CORES = new LinkedHashMap<>();

    /**
     * Classes every operation of the RISC-V cores goes through (the hardware domain of their shared RV32IM
     * model), so not where an operation was written: skipped when recording operation locations.
     */
    static final java.util.List<String> LOC_SKIP = java.util.List.of("org.eninehq.tessera.riscv.HwDom", "org.eninehq.tessera.riscv.Dom");

    /**
     * Programs a core can run besides its benchmark loop: the riscv-tests binaries of tessera-riscv, when the
     * studio runs inside the repository (they are test resources, not part of a release).
     */
    static Map<String, java.nio.file.Path> programs() {
        var m = new java.util.TreeMap<String, java.nio.file.Path>();
        for (var d = java.nio.file.Path.of("").toAbsolutePath(); d != null; d = d.getParent()) {
            var dir = d.resolve("tessera-riscv/src/test/resources/riscv-tests");
            if (java.nio.file.Files.isDirectory(dir)) {
                try (var files = java.nio.file.Files.list(dir)) {
                    files.filter(f -> f.toString().endsWith(".bin")).forEach(f -> m.put(f.getFileName().toString().replace(".bin", ""), f));
                } catch (java.io.IOException ignored) { /* none, then */ }
                break;
            }
        }
        return m;
    }

    /** A riscv-test as the word image the cores load. */
    static int[] image(java.nio.file.Path bin) throws java.io.IOException {
        byte[] b = java.nio.file.Files.readAllBytes(bin);
        int[] w = new int[(b.length + 3) / 4];
        for (int i = 0; i < b.length; i++) w[i / 4] |= (b[i] & 0xff) << (8 * (i % 4));
        return w;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Supplier<? extends Module>> available(java.util.List<String> registries) {
        var m = new LinkedHashMap<String, Supplier<? extends Module>>();
        try {
            Class<?> cores = Class.forName("org.eninehq.tessera.riscv.Cores");
            Class<?> bench = Class.forName("org.eninehq.tessera.riscv.Benchmarks");
            int[] prog = (int[]) bench.getMethod("loop", int.class).invoke(null, 1_000_000);
            for (Object spec : (java.util.List<?>) cores.getField("ALL").get(null)) {
                var rec = spec.getClass();
                String id = (String) rec.getMethod("id").invoke(spec), name = (String) rec.getMethod("name").invoke(spec);
                var build = rec.getMethod("build", int.class, int[].class);
                m.put(id + " " + name, () -> {
                    try { return (Module) build.invoke(spec, 4096, prog); } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
                });
                CORES.put(id + " " + name, image -> {
                    try { return (Module) build.invoke(spec, 4096, image == null ? prog : image); } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
                });
            }
        } catch (ClassNotFoundException e) {
            // tessera-riscv is not on the class path
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot list the RISC-V cores: " + e);
        }
        for (String r : registries) {
            try {
                var got = (Map<String, Supplier<? extends Module>>) Class.forName(r).getMethod("studioDesigns").invoke(null);
                m.putAll(got);
            } catch (ReflectiveOperationException e) {
                throw new IllegalArgumentException("--designs " + r + ": needs a static studioDesigns() method (" + e + ")");
            }
        }
        return m;
    }
}
