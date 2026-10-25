package org.eninehq.tessera.studio;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * Conditions over signals, for "run until" and for searching the waves: {@code count == 10 && valid},
 * {@code core.pc >= 0x80}, {@code !(a | b)}. Names are signal paths (with dots); numbers are decimal,
 * 0x hex or 0b binary. Operators, loosest first: {@code || && | ^ & == != < <= > >= + - ! ~}, with
 * parentheses. Values are unsigned; a condition holds when its value is not zero.
 */
final class Cond {
    private sealed interface Node permits Num, Sig, Un, Bin {}
    private record Num(BigInteger v) implements Node {}
    private record Sig(String path) implements Node {}
    private record Un(String op, Node a) implements Node {}
    private record Bin(String op, Node a, Node b) implements Node {}

    private final Node root;
    private final Set<String> signals = new LinkedHashSet<>();

    private Cond(Node root) { this.root = root; collect(root); }

    /** The parsed condition, or an IllegalArgumentException whose message is a sentence. */
    static Cond parse(String text) {
        var p = new Parser(text);
        Node n = p.or();
        p.skip();
        if (p.i < text.length()) throw new IllegalArgumentException("Unexpected “" + text.substring(p.i) + "” in the condition.");
        return new Cond(n);
    }

    /** The signals it reads. */
    Set<String> signals() { return signals; }

    /** Its value, reading signals through {@code read} (which returns null for a signal that does not exist). */
    BigInteger eval(Function<String, BigInteger> read) { return eval(root, read); }

    boolean holds(Function<String, BigInteger> read) { return eval(read).signum() != 0; }

    private void collect(Node n) {
        switch (n) {
            case Sig s -> signals.add(s.path());
            case Un u -> collect(u.a());
            case Bin b -> { collect(b.a()); collect(b.b()); }
            case Num x -> { }
        }
    }

    private static BigInteger eval(Node n, Function<String, BigInteger> read) {
        return switch (n) {
            case Num x -> x.v();
            case Sig s -> {
                BigInteger v = read.apply(s.path());
                if (v == null) throw new IllegalArgumentException("There is no signal " + s.path() + ".");
                yield v;
            }
            case Un u -> {
                BigInteger a = eval(u.a(), read);
                yield u.op().equals("!") ? bool(a.signum() == 0) : a.not().and(mask(a));   // ~ within the value's own bits
            }
            case Bin b -> {
                if (b.op().equals("&&")) yield bool(eval(b.a(), read).signum() != 0 && eval(b.b(), read).signum() != 0);
                if (b.op().equals("||")) yield bool(eval(b.a(), read).signum() != 0 || eval(b.b(), read).signum() != 0);
                BigInteger x = eval(b.a(), read), y = eval(b.b(), read);
                yield switch (b.op()) {
                    case "|" -> x.or(y);
                    case "^" -> x.xor(y);
                    case "&" -> x.and(y);
                    case "==" -> bool(x.equals(y));
                    case "!=" -> bool(!x.equals(y));
                    case "<" -> bool(x.compareTo(y) < 0);
                    case "<=" -> bool(x.compareTo(y) <= 0);
                    case ">" -> bool(x.compareTo(y) > 0);
                    case ">=" -> bool(x.compareTo(y) >= 0);
                    case "+" -> x.add(y);
                    case "-" -> x.subtract(y).max(BigInteger.ZERO);
                    default -> throw new IllegalStateException(b.op());
                };
            }
        };
    }

    private static BigInteger mask(BigInteger a) { return BigInteger.ONE.shiftLeft(Math.max(1, a.bitLength())).subtract(BigInteger.ONE); }
    private static BigInteger bool(boolean b) { return b ? BigInteger.ONE : BigInteger.ZERO; }

    private static final class Parser {
        final String s;
        int i;

        Parser(String s) { this.s = s; }

        void skip() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }

        boolean eat(String t) {
            skip();
            if (!s.startsWith(t, i)) return false;
            // "&" is not the start of "&&", "|" not of "||", "<" not of "<=", "!" not of "!="
            if ((t.equals("&") || t.equals("|")) && s.startsWith(t + t, i)) return false;
            if ((t.equals("<") || t.equals(">") || t.equals("!")) && s.startsWith(t + "=", i)) return false;
            i += t.length();
            return true;
        }

        Node or() { Node a = and(); while (eat("||")) a = new Bin("||", a, and()); return a; }
        Node and() { Node a = bor(); while (eat("&&")) a = new Bin("&&", a, bor()); return a; }
        Node bor() { Node a = bxor(); while (eat("|")) a = new Bin("|", a, bxor()); return a; }
        Node bxor() { Node a = band(); while (eat("^")) a = new Bin("^", a, band()); return a; }
        Node band() { Node a = cmp(); while (eat("&")) a = new Bin("&", a, cmp()); return a; }

        Node cmp() {
            Node a = sum();
            for (;;) {
                String op = null;
                for (String t : List.of("==", "!=", "<=", ">=", "<", ">")) if (eat(t)) { op = t; break; }
                if (op == null) return a;
                a = new Bin(op, a, sum());
            }
        }

        Node sum() {
            Node a = unary();
            for (;;) {
                if (eat("+")) a = new Bin("+", a, unary());
                else if (eat("-")) a = new Bin("-", a, unary());
                else return a;
            }
        }

        Node unary() {
            if (eat("!")) return new Un("!", unary());
            if (eat("~")) return new Un("~", unary());
            if (eat("(")) {
                Node n = or();
                if (!eat(")")) throw new IllegalArgumentException("A “(” is not closed.");
                return n;
            }
            skip();
            int j = i;
            while (i < s.length() && (Character.isLetterOrDigit(s.charAt(i)) || s.charAt(i) == '_' || s.charAt(i) == '.' || s.charAt(i) == '$')) i++;
            String w = s.substring(j, i);
            if (w.isEmpty()) throw new IllegalArgumentException(i < s.length() ? "Unexpected “" + s.charAt(i) + "” in the condition." : "The condition ends too soon.");
            if (Character.isDigit(w.charAt(0))) {
                try {
                    String t = w.replace("_", "").toLowerCase();
                    return new Num(t.startsWith("0x") ? new BigInteger(t.substring(2), 16) : t.startsWith("0b") ? new BigInteger(t.substring(2), 2) : new BigInteger(t));
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("“" + w + "” is not a number.");
                }
            }
            if (w.equals("true")) return new Num(BigInteger.ONE);
            if (w.equals("false")) return new Num(BigInteger.ZERO);
            return new Sig(w);
        }
    }

    /** Every signal name the text mentions that is not in {@code known}, for a helpful message. */
    static List<String> unknown(Cond e, Set<String> known) {
        var l = new ArrayList<String>();
        for (String s : e.signals()) if (!known.contains(s)) l.add(s);
        return l;
    }
}
