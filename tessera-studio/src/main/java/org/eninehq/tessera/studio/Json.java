package org.eninehq.tessera.studio;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small JSON reader and writer for the studio's API: objects become {@code LinkedHashMap}s
 * (key order kept), arrays {@code ArrayList}s, numbers {@code Long} when integral and
 * {@code Double} otherwise.
 */
public final class Json {
    private final String s;
    private int i;

    private Json(String s) {
        this.s = s;
    }

    public static Object parse(String text) {
        var p = new Json(text);
        p.ws();
        Object v = p.value();
        p.ws();
        if (p.i != p.s.length()) throw p.error("trailing characters");
        return v;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> object(String text) {
        if (!(parse(text) instanceof Map<?, ?> m)) throw new IllegalArgumentException("JSON: expected an object");
        return (Map<String, Object>) m;
    }

    private IllegalArgumentException error(String what) {
        return new IllegalArgumentException("JSON: " + what + " at offset " + i);
    }

    private void ws() {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
    }

    private Object value() {
        if (i >= s.length()) throw error("unexpected end");
        char c = s.charAt(i);
        return switch (c) {
            case '{' -> obj();
            case '[' -> arr();
            case '"' -> str();
            case 't' -> word("true", Boolean.TRUE);
            case 'f' -> word("false", Boolean.FALSE);
            case 'n' -> word("null", null);
            default -> num();
        };
    }

    private Object word(String w, Object v) {
        if (!s.startsWith(w, i)) throw error("unexpected token");
        i += w.length();
        return v;
    }

    private Map<String, Object> obj() {
        var m = new LinkedHashMap<String, Object>();
        i++;
        ws();
        if (s.charAt(i) == '}') { i++; return m; }
        while (true) {
            ws();
            String k = str();
            ws();
            if (s.charAt(i++) != ':') throw error("expected ':'");
            ws();
            m.put(k, value());
            ws();
            char c = s.charAt(i++);
            if (c == '}') return m;
            if (c != ',') throw error("expected ',' or '}'");
        }
    }

    private List<Object> arr() {
        var l = new ArrayList<Object>();
        i++;
        ws();
        if (s.charAt(i) == ']') { i++; return l; }
        while (true) {
            ws();
            l.add(value());
            ws();
            char c = s.charAt(i++);
            if (c == ']') return l;
            if (c != ',') throw error("expected ',' or ']'");
        }
    }

    private String str() {
        if (s.charAt(i) != '"') throw error("expected a string");
        var b = new StringBuilder();
        i++;
        while (true) {
            char c = s.charAt(i++);
            if (c == '"') return b.toString();
            if (c != '\\') { b.append(c); continue; }
            char e = s.charAt(i++);
            switch (e) {
                case 'n' -> b.append('\n');
                case 't' -> b.append('\t');
                case 'r' -> b.append('\r');
                case 'b' -> b.append('\b');
                case 'f' -> b.append('\f');
                case 'u' -> { b.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; }
                default -> b.append(e);
            }
        }
    }

    private Object num() {
        int st = i;
        while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
        String t = s.substring(st, i);
        if (t.isEmpty()) throw error("unexpected character '" + s.charAt(st) + "'");
        if (t.indexOf('.') < 0 && t.indexOf('e') < 0 && t.indexOf('E') < 0) {
            try { return Long.parseLong(t); } catch (NumberFormatException ignored) { /* falls through to double */ }
        }
        return Double.parseDouble(t);
    }

    // ------------------------------------------------------------ writing

    public static String write(Object v) {
        var b = new StringBuilder();
        write(b, v);
        return b.toString();
    }

    private static void write(StringBuilder b, Object v) {
        switch (v) {
            case null -> b.append("null");
            case String str -> quote(b, str);
            case Boolean bo -> b.append(bo);
            case Double d when d == Math.rint(d) && Math.abs(d) < 1e15 -> b.append(d.longValue());
            case Number n -> b.append(n);
            case Map<?, ?> m -> {
                b.append('{');
                boolean first = true;
                for (var e : m.entrySet()) {
                    if (!first) b.append(',');
                    first = false;
                    quote(b, String.valueOf(e.getKey()));
                    b.append(':');
                    write(b, e.getValue());
                }
                b.append('}');
            }
            case Iterable<?> it -> {
                b.append('[');
                boolean first = true;
                for (var x : it) {
                    if (!first) b.append(',');
                    first = false;
                    write(b, x);
                }
                b.append(']');
            }
            case Object[] arr -> write(b, List.of(arr));
            case int[] arr -> { var l = new ArrayList<Integer>(); for (int x : arr) l.add(x); write(b, l); }
            default -> quote(b, v.toString());
        }
    }

    private static void quote(StringBuilder b, String s) {
        b.append('"');
        for (int k = 0; k < s.length(); k++) {
            char c = s.charAt(k);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
                }
            }
        }
        b.append('"');
    }
}
