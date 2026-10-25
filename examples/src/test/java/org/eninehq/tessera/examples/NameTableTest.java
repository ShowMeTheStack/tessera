package org.eninehq.tessera.examples;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.eninehq.tessera.core.Module;
import org.eninehq.tessera.core.Tessera;
import org.eninehq.tessera.emit.SvEmitter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** The emitter's name table: one entry per name the SystemVerilog declares, and the SystemVerilog unchanged. */
class NameTableTest {
    static final Pattern MODULE = Pattern.compile("^module (\\w+) \\(", Pattern.MULTILINE);
    static final Pattern DECL = Pattern.compile("^  (?:input  logic |output logic |logic |wire )(?:\\[\\d+:0\\] )?(\\w+)", Pattern.MULTILINE);
    static final Pattern INST = Pattern.compile("^  (\\w+) (\\w+) \\($", Pattern.MULTILINE);

    static java.util.List<org.junit.jupiter.params.provider.Arguments> designs() {
        return GoldenAndSynthTest.designs();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("designs")
    void everyDeclaredNameIsInTheTable(String name, Supplier<Module> design) throws Exception {
        var c = Tessera.elaborate(design).circuit();
        String sv = SvEmitter.emit(c);
        Set<String> declared = new TreeSet<>(), tabled = new TreeSet<>();
        var mods = MODULE.matcher(sv);
        var starts = new java.util.ArrayList<int[]>();
        var modNames = new java.util.ArrayList<String>();
        while (mods.find()) { starts.add(new int[]{mods.start()}); modNames.add(mods.group(1)); }
        for (int k = 0; k < modNames.size(); k++) {
            String body = sv.substring(starts.get(k)[0], k + 1 < starts.size() ? starts.get(k + 1)[0] : sv.length());
            for (var m = DECL.matcher(body); m.find(); ) declared.add(modNames.get(k) + "." + m.group(1));
            for (var m = INST.matcher(body); m.find(); ) declared.add(modNames.get(k) + "." + m.group(2));
        }
        var names = SvEmitter.names(c);
        var kinds = new HashSet<String>();
        for (var n : names) {
            assertTrue(tabled.add(n.module() + "." + n.name()), "listed twice: " + n);
            kinds.add(n.kind());
            if (n.kind().equals("temp")) assertFalse(n.signal().isEmpty(), "a temp feeds something: " + n);
            if (n.kind().equals("inst")) assertTrue(modNames.contains(n.child()), "the child is an emitted module: " + n);
        }
        assertEquals(declared, tabled);
        assertTrue(kinds.contains("port"), kinds.toString());
        // asking for the table changes nothing in the SystemVerilog
        assertEquals(sv, SvEmitter.emit(c));
    }

    @Test
    void writeWithNamesWritesTheSameSvAndATableBesideIt() throws Exception {
        var c = Tessera.elaborate(() -> new Counter(8)).circuit();
        var dir = Files.createTempDirectory("names");
        var f = dir.resolve("counter.sv");
        SvEmitter.writeWithNames(c, f);
        assertEquals(SvEmitter.emit(c), Files.readString(f));
        var lines = Files.readAllLines(dir.resolve("counter.sv.names.tsv"));
        assertTrue(lines.getFirst().startsWith("# module\tname\tkind"), lines.getFirst());
        // every entry of Counter's own code knows where in the Java it was made
        var own = lines.stream().skip(1).map(l -> l.split("\t", -1)).filter(p -> !p[2].equals("port")).toList();
        assertFalse(own.isEmpty());
        for (var p : own) assertTrue(p[7].equals("Counter.java") && Integer.parseInt(p[8]) > 0, String.join(" | ", p));
    }
}
