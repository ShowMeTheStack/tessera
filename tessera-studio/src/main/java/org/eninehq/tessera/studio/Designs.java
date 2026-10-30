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

    /**
     * The groups of cores besides {@code Cores.ALL}: the field of {@code Cores} that lists them, and the heading the
     * studio shows. A field the tessera-riscv on the class path does not have is skipped.
     */
    static final java.util.List<String[]> VARIANT_GROUPS = java.util.List.of(
            new String[]{"OOO_VARIANTS", "Out-of-order variants of C11"},
            new String[]{"GENERATED", "Generated from one model (out-of-order and in-order generators)"},
            new String[]{"SYMMETRIC_VARIANTS", "Dual issue, memory in both lanes (true dual-port memory)"},
            new String[]{"LVT_VARIANTS", "Dual issue, live-value-table register file"},
            new String[]{"PREDICT_VARIANTS", "Branch predictors (BTB, gshare, return-address stack)"},
            new String[]{"GSHARE_SWEEP", "gshare history sweep"},
            new String[]{"MUL_VARIANTS", "Where the multiply finishes"},
            new String[]{"BEFORE", "Earlier schedules of C5 and C6"});

    /** The heading of the main cores ({@code Cores.ALL}) in {@link #GROUPS}. */
    static final String MAIN_GROUP = "Main cores";

    /** The heading of the designs registered with {@code --designs} in {@link #GROUPS}. */
    static final String REGISTERED_GROUP = "Registered designs";

    /** The designs of the last {@link #available} by group, in order: the main cores, registered designs, then each variant group. */
    static final Map<String, java.util.List<String>> GROUPS = new LinkedHashMap<>();

    /**
     * The groups as the {@code designGroups} API sends them: [{group, flat, designs: [name, ...]}], empty groups left
     * out; {@code flat} (the main cores and registered designs) are listed in the designs menu itself, the others
     * in a submenu each.
     */
    static java.util.List<Object> groupsJson() {
        var out = new java.util.ArrayList<Object>();
        GROUPS.forEach((g, l) -> {
            if (!l.isEmpty()) out.add(Map.of("group", g, "flat", g.equals(MAIN_GROUP) || g.equals(REGISTERED_GROUP), "designs", java.util.List.copyOf(l)));
        });
        return out;
    }

    /**
     * The designs: the main cores ({@code Cores.ALL}) in their order, then the designs of the registries (the
     * order these two always had), then the variant cores of {@link #VARIANT_GROUPS}, group by group.
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Supplier<? extends Module>> available(java.util.List<String> registries) {
        var m = new LinkedHashMap<String, Supplier<? extends Module>>();
        GROUPS.clear();
        var variants = new LinkedHashMap<String, java.util.List<?>>();
        int[] prog = null;
        java.lang.reflect.Method idOf = null, nameOf = null, buildOf = null;
        try {
            Class<?> cores = Class.forName("org.eninehq.tessera.riscv.Cores");
            Class<?> bench = Class.forName("org.eninehq.tessera.riscv.Benchmarks");
            prog = (int[]) bench.getMethod("loop", int.class).invoke(null, 1_000_000);
            Class<?> spec = Class.forName("org.eninehq.tessera.riscv.Cores$Spec");
            idOf = spec.getMethod("id");
            nameOf = spec.getMethod("name");
            buildOf = spec.getMethod("build", int.class, int[].class);
            GROUPS.put(MAIN_GROUP, addCores(m, (java.util.List<?>) cores.getField("ALL").get(null), prog, idOf, nameOf, buildOf));
            for (String[] g : VARIANT_GROUPS) {
                try { variants.put(g[1], (java.util.List<?>) cores.getField(g[0]).get(null)); }
                catch (NoSuchFieldException e) { /* an older tessera-riscv */ }
            }
        } catch (ClassNotFoundException e) {
            // tessera-riscv is not on the class path
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot list the RISC-V cores: " + e);
        }
        var registered = new java.util.ArrayList<String>();
        for (String r : registries) {
            try {
                var got = (Map<String, Supplier<? extends Module>>) Class.forName(r).getMethod("studioDesigns").invoke(null);
                m.putAll(got);
                registered.addAll(got.keySet());
            } catch (ReflectiveOperationException e) {
                throw new IllegalArgumentException("--designs " + r + ": needs a static studioDesigns() method (" + e + ")");
            }
        }
        GROUPS.put(REGISTERED_GROUP, registered);
        try {
            for (var g : variants.entrySet()) GROUPS.put(g.getKey(), addCores(m, g.getValue(), prog, idOf, nameOf, buildOf));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot list the RISC-V core variants: " + e);
        }
        return m;
    }

    /** The cores of a {@code Cores} list, into {@code m} and {@link #CORES} (a name already there is not added again); their names. */
    private static java.util.List<String> addCores(Map<String, Supplier<? extends Module>> m, java.util.List<?> specs, int[] prog,
            java.lang.reflect.Method idOf, java.lang.reflect.Method nameOf, java.lang.reflect.Method build) throws ReflectiveOperationException {
        var names = new java.util.ArrayList<String>();
        for (Object spec : specs) {
            String key = idOf.invoke(spec) + " " + nameOf.invoke(spec);
            if (m.containsKey(key)) continue;
            names.add(key);
            m.put(key, () -> {
                try { return (Module) build.invoke(spec, 4096, prog); } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
            });
            CORES.put(key, image -> {
                try { return (Module) build.invoke(spec, 4096, image == null ? prog : image); } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
            });
        }
        return names;
    }
}
