package org.eninehq.tessera.stdlib;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.eninehq.tessera.core.Bool;
import org.eninehq.tessera.core.TesseraException;

/**
 * Combinational logic written as a truth table in a text block:
 * <pre>{@code
 * var adder = TruthTable.parse("""
 *     a b cin | sum cout
 *     0 0 0   | 0   0
 *     0 0 1   | 1   0
 *     ...
 *     """);
 * Map<String, Bool> out = adder.apply(Map.of("a", a, "b", b, "cin", cin));
 * }</pre>
 * Inputs may be {@code -} (don't care); rows that are not listed give 0. Overlapping rows
 * that disagree are an error pointing at both lines.
 */
public final class TruthTable {
    private record Row(int line, String[] in, String[] out) {}

    private final List<String> inputs, outputs;
    private final List<Row> rows;

    private TruthTable(List<String> inputs, List<String> outputs, List<Row> rows) {
        this.inputs = inputs;
        this.outputs = outputs;
        this.rows = rows;
    }

    public List<String> inputs() { return inputs; }
    public List<String> outputs() { return outputs; }

    public static TruthTable parse(String text) {
        var lines = text.strip().lines().map(String::strip).filter(l -> !l.isEmpty() && !l.startsWith("#")).toList();
        if (lines.isEmpty()) throw TesseraException.here("empty truth table");
        String[] head = lines.getFirst().split("\\|");
        if (head.length != 2) throw TesseraException.here("truth table header needs 'inputs | outputs'");
        var in = List.of(head[0].trim().split("\\s+"));
        var out = List.of(head[1].trim().split("\\s+"));
        var rows = new ArrayList<Row>();
        for (int i = 1; i < lines.size(); i++) {
            String[] parts = lines.get(i).split("\\|");
            if (parts.length != 2) throw TesseraException.here("truth table line " + (i + 1) + ": expected 'values | values'");
            String[] a = parts[0].trim().split("\\s+"), b = parts[1].trim().split("\\s+");
            if (a.length != in.size() || b.length != out.size())
                throw TesseraException.here("truth table line " + (i + 1) + ": expected " + in.size() + " inputs and " + out.size() + " outputs");
            for (String x : a) if (!x.equals("0") && !x.equals("1") && !x.equals("-"))
                throw TesseraException.here("truth table line " + (i + 1) + ": input values are 0, 1 or -");
            for (String x : b) if (!x.equals("0") && !x.equals("1"))
                throw TesseraException.here("truth table line " + (i + 1) + ": output values are 0 or 1");
            rows.add(new Row(i + 1, a, b));
        }
        // rows that can match the same input must agree
        for (int i = 0; i < rows.size(); i++)
            for (int j = i + 1; j < rows.size(); j++)
                if (overlap(rows.get(i).in(), rows.get(j).in()) && !java.util.Arrays.equals(rows.get(i).out(), rows.get(j).out()))
                    throw TesseraException.here("truth table lines " + rows.get(i).line() + " and " + rows.get(j).line()
                            + " match the same inputs but give different outputs");
        return new TruthTable(in, out, rows);
    }

    private static boolean overlap(String[] a, String[] b) {
        for (int k = 0; k < a.length; k++) if (!a[k].equals("-") && !b[k].equals("-") && !a[k].equals(b[k])) return false;
        return true;
    }

    /** The table as hardware: each output is an OR of the rows where it is 1. */
    public Map<String, Bool> apply(Map<String, Bool> in) {
        for (var n : inputs) if (!in.containsKey(n)) throw TesseraException.here("truth table input '" + n + "' is not connected");
        var out = new LinkedHashMap<String, Bool>();
        for (int o = 0; o < outputs.size(); o++) {
            Bool acc = Bool.of(false);
            for (var r : rows) {
                if (!r.out()[o].equals("1")) continue;
                Bool term = Bool.of(true);
                for (int k = 0; k < inputs.size(); k++) {
                    Bool x = in.get(inputs.get(k));
                    if (r.in()[k].equals("1")) term = term.and(x);
                    else if (r.in()[k].equals("0")) term = term.and(x.not());
                }
                acc = acc.or(term);
            }
            out.put(outputs.get(o), acc);
        }
        return out;
    }

    /** The table run in Java (for tests): outputs for one input assignment. */
    public Map<String, Boolean> evaluate(Map<String, Boolean> in) {
        var out = new LinkedHashMap<String, Boolean>();
        for (var n : outputs) out.put(n, false);
        for (var r : rows) {
            boolean match = true;
            for (int k = 0; k < inputs.size(); k++)
                if (!r.in()[k].equals("-") && (r.in()[k].equals("1")) != in.get(inputs.get(k))) match = false;
            if (match) for (int o = 0; o < outputs.size(); o++) if (r.out()[o].equals("1")) out.put(outputs.get(o), true);
        }
        return out;
    }
}
