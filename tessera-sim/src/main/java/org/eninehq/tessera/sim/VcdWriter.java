package org.eninehq.tessera.sim;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.eninehq.tessera.ir.Signal;

/** Writes a Value Change Dump. Each cycle is 10 time units; the clock rises at 10*cycle. */
final class VcdWriter implements WaveWriter {
    private record Var(String id, int slot, int width) {}

    private final BufferedWriter w;
    private final List<Var> vars = new ArrayList<>();
    private final String clockId = "!";
    private long[] last;

    VcdWriter(Path file, Netlist net) throws IOException {
        var parent = file.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        w = Files.newBufferedWriter(file);
        w.write("$timescale 1ns $end\n");
        var root = new Scope();
        int n = 0;
        for (Map.Entry<String, Signal> e : net.signalsByPath().entrySet()) {
            Signal s = e.getValue();
            if (net.isClock(s)) continue;
            int slot = net.slotOf(s);
            if (slot < 0) continue;
            String id = ident(++n);
            vars.add(new Var(id, slot, s.width()));
            String[] parts = e.getKey().split("\\.");
            Scope cur = root;
            for (int k = 0; k < parts.length - 1; k++) cur = cur.kids.computeIfAbsent(parts[k], x -> new Scope());
            cur.sigs.add(new String[]{id, parts[parts.length - 1], Integer.toString(s.width())});
        }
        w.write("$scope module top $end\n");
        w.write("$var wire 1 " + clockId + " clock $end\n");
        writeScope(root);
        w.write("$upscope $end\n$enddefinitions $end\n");
    }

    /** A level of the module hierarchy: child scopes and variables {id, name, width}. */
    private static final class Scope {
        final Map<String, Scope> kids = new TreeMap<>();
        final List<String[]> sigs = new ArrayList<>();
    }

    private void writeScope(Scope sc) throws IOException {
        for (var s : sc.sigs) w.write("$var wire " + s[2] + " " + s[0] + " " + s[1] + " $end\n");
        for (var e : sc.kids.entrySet()) {
            w.write("$scope module " + e.getKey() + " $end\n");
            writeScope(e.getValue());
            w.write("$upscope $end\n");
        }
    }

    private static String ident(int n) {
        var sb = new StringBuilder();
        do {
            sb.append((char) ('"' + n % 90));
            n /= 90;
        } while (n > 0);
        return sb.toString();
    }

    @Override public void sample(long cycle, long[] values) {
        try {
            w.write("#" + (cycle * 10) + "\n1" + clockId + "\n");
            for (var var : vars) {
                int nw = BitVec.words(var.width());
                if (last != null && java.util.Arrays.equals(last, var.slot(), var.slot() + nw, values, var.slot(), var.slot() + nw))
                    continue;
                if (var.width() == 1) w.write((values[var.slot()] & 1) + var.id() + "\n");
                else if (nw == 1) w.write("b" + Long.toBinaryString(values[var.slot()]) + " " + var.id() + "\n");
                else w.write("b" + BitVec.toBig(values, var.slot(), var.width()).toString(2) + " " + var.id() + "\n");
            }
            if (last == null || last.length != values.length) last = values.clone();
            else System.arraycopy(values, 0, last, 0, values.length);
            w.write("#" + (cycle * 10 + 5) + "\n0" + clockId + "\n");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override public void close() {
        try {
            w.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
