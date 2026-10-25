package org.eninehq.tessera.studio;

import com.sun.source.tree.*;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.Trees;
import java.math.BigInteger;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;

/**
 * Java back to a drawing. The Java the studio writes is a small, regular dialect of Tessera: every value
 * is one call of the builder API, named after its wire. This reader parses a file with javac's own
 * syntax trees and turns each statement of that dialect back into its part, with its wires; parts keep
 * their place when the previous drawing had them (matched by name). Statements outside the dialect (a
 * loop, a helper method, anything Java can say) are not guessed at: each run of them becomes a Java code
 * part, a module class holding those statements verbatim, with the values they read as its inputs and the
 * values later statements read as its outputs.
 */
final class JavaReader {
    /** The drawing, and sentences about what became of the Java. */
    record Read(Map<String, Object> doc, List<String> report) {}

    /** Thrown for a statement or expression outside the dialect. */
    private static final class Unrec extends RuntimeException {
        Unrec(String why) { super(why, null, false, false); }
    }

    private final String src;
    private final CompilationUnitTree cu;
    private final SourcePositions pos;
    private final Modules modules;
    private final Map<String, Object> previous;
    private final List<String> report = new ArrayList<>();
    private final List<String> imports = new ArrayList<>();
    private final List<String> staticImports = new ArrayList<>();
    private final Map<String, List<String>> enums = new LinkedHashMap<>();
    private final Map<String, ClassTree> nested = new LinkedHashMap<>();
    private final Set<String> blockClasses = new LinkedHashSet<>();
    private final Map<String, Map<String, Integer>> widths;   // module -> signal name -> width, from elaborating the Java
    private int nextId = 1;

    private JavaReader(String src, Map<String, Object> previous, Modules modules, Map<String, Map<String, Integer>> widths) {
        this.src = src;
        this.previous = previous == null ? Map.of() : previous;
        this.modules = modules;
        this.widths = widths;
        var jc = ToolProvider.getSystemJavaCompiler();
        JavaFileObject fo = new SimpleJavaFileObject(URI.create("string:///Design.java"), JavaFileObject.Kind.SOURCE) {
            @Override public CharSequence getCharContent(boolean ignore) { return src; }
        };
        var task = (JavacTask) jc.getTask(null, null, d -> { }, List.of("-proc:none"), null, List.of(fo));
        try {
            var it = task.parse().iterator();
            cu = it.next();
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("The Java could not be read: " + e.getMessage());
        }
        pos = Trees.instance(task).getSourcePositions();
    }

    /**
     * Read {@code java} into a drawing. {@code widths} are the signal widths of the design (from elaborating
     * it), used for the widths of code parts' ports and to tell a condition's {@code x.bit(0)} from a slice.
     */
    static Read read(String java, Map<String, Object> previous, Modules modules, Map<String, Map<String, Integer>> widths) {
        var r = new JavaReader(java, previous, modules, widths);
        return r.run();
    }

    /** Read Java into a drawing: compile and elaborate it for its signals' widths, then read its statements. */
    static Read readSource(String src, Map<String, Object> previous, Elaborator elaborator, Modules modules) {
        var cm = Pattern.compile("public\\s+(?:final\\s+)?class\\s+(\\w+)\\s+extends\\s+Module").matcher(src);
        if (!cm.find()) throw new IllegalArgumentException("There is no public class extending Module in this file.");
        var pm = Pattern.compile("^\\s*package\\s+([\\w.]+)\\s*;", Pattern.MULTILINE).matcher(src);
        String pkg = pm.find() ? pm.group(1) : "";
        if (pkg.isEmpty()) throw new IllegalArgumentException("The file has no package; Studio compiles designs in a package (add package studio;).");
        var gen = new CodeGen.Result(cm.group(1), src, Map.of(), List.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of());
        var out = elaborator.elaborate(gen, pkg);
        if (out.design() == null)
            throw new IllegalArgumentException("The Java does not elaborate, so it cannot be drawn yet: "
                    + out.problems().stream().map(CodeGen.Problem::message).findFirst().orElse("no reason given"));
        var widths = new LinkedHashMap<String, Map<String, Integer>>();
        for (var md : out.design().circuit().modulesBottomUp()) {
            var w = widths.computeIfAbsent(md.name(), k -> new LinkedHashMap<>());
            for (var sg : md.signals()) w.putIfAbsent(sg.name(), sg.width());
            for (var sg : md.ports()) w.putIfAbsent(sg.name(), sg.width());
        }
        return read(src, previous, modules, widths);
    }

    private String text(Tree t) { return src.substring((int) pos.getStartPosition(cu, t), (int) pos.getEndPosition(cu, t)); }
    private int line(Tree t) { return (int) cu.getLineMap().getLineNumber(pos.getStartPosition(cu, t)); }

    private Read run() {
        for (var im : cu.getImports()) {
            String q = im.getQualifiedIdentifier().toString();
            if (im.isStatic()) staticImports.add(q); else imports.add(q);
        }
        ClassTree top = null;
        for (var t : cu.getTypeDecls())
            if (t instanceof ClassTree c && c.getExtendsClause() != null && c.getExtendsClause().toString().equals("Module") && c.getModifiers().getFlags().contains(javax.lang.model.element.Modifier.PUBLIC)) top = c;
        if (top == null) throw new IllegalArgumentException("There is no public class extending Module in this file.");
        for (var m : top.getMembers()) {
            if (m instanceof ClassTree c) {
                if (c.getKind() == Tree.Kind.ENUM) {
                    var names = new ArrayList<String>();
                    for (var e : c.getMembers()) if (e instanceof VariableTree v) names.add(v.getName().toString());
                    enums.put(c.getSimpleName().toString(), names);
                } else if (c.getExtendsClause() != null && c.getExtendsClause().toString().equals("Module")) {
                    nested.put(c.getSimpleName().toString(), c);
                    // the studio marks its blocks; a nested module without the mark is a code part
                    int start = (int) pos.getStartPosition(cu, c);
                    String before = src.substring(Math.max(0, src.lastIndexOf("/**", start)), start);
                    if (before.contains("(a block of the drawing)")) blockClasses.add(c.getSimpleName().toString());
                }
            }
        }
        var doc = new LinkedHashMap<String, Object>();
        String cls = top.getSimpleName().toString();
        doc.put("tessera_studio", 1);
        doc.put("name", cls);
        doc.put("title", javadocTitle(top, cls));
        doc.put("package", cu.getPackageName() == null ? "studio" : cu.getPackageName().toString());
        doc.put("notes", previous.getOrDefault("notes", List.of()));
        if (previous.get("bench") != null) doc.put("bench", previous.get("bench"));   // the testbench has no Java in the design
        var blocks = new LinkedHashMap<String, Object>();
        @SuppressWarnings("unchecked")
        var prevBlocks = previous.get("blocks") instanceof Map<?, ?> pb ? (Map<String, Object>) pb : Map.<String, Object>of();
        for (String b : blockClasses) {
            var bm = new ModuleReader(nested.get(b), CodeGen.map(prevBlocks.get(b)), b + ": ", true);
            var bd = bm.read();
            bd.put("name", b);
            bd.put("title", javadocTitle(nested.get(b), b).replace(" (a block of the drawing)", ""));
            blocks.put(b, bd);
        }
        var mr = new ModuleReader(top, previous, "", false);
        var d = mr.read();
        doc.putAll(d);
        if (!blocks.isEmpty()) doc.put("blocks", blocks);
        doc.put("next", nextId + 1000);
        return new Read(doc, report);
    }

    private String javadocTitle(ClassTree c, String cls) {
        int start = (int) pos.getStartPosition(cu, c);
        int j = src.lastIndexOf("/**", start), e = j < 0 ? -1 : src.indexOf("*/", j);
        if (j < 0 || e < 0 || e > start) return "";
        String t = src.substring(j + 3, e).replaceAll("\\s*\\*\\s*", " ").trim();
        t = t.replace(" (drawn in Tessera Studio).", "").replace("(drawn in Tessera Studio).", "").replace(" (a block of the drawing).", "");
        if (t.endsWith(".")) t = t.substring(0, t.length() - 1);
        return t.equals(cls) ? "" : t;
    }

    // ---------------------------------------------------------------- one module's constructor

    private static final Pattern JAVA_VALUE = Pattern.compile("int|long|short|byte|boolean|char|double|float|String|BigInteger|int\\[\\]|long\\[\\]");

    /** A value: the pin of a part that drives it. */
    private record Pin(String part, String pin) {}

    private final class ModuleReader {
        final ClassTree cls;
        final Map<String, Object> prev;
        final String where;
        final boolean isBlock;
        final Map<String, Map<String, Object>> parts = new LinkedHashMap<>();
        final List<Object[]> conns = new ArrayList<>();                 // {partId, pin, Pin source}
        final Map<String, Pin> locals = new LinkedHashMap<>();
        final Map<String, String> netNames = new HashMap<>();             // "part.pin" of a source -> the local it was named
        final Map<String, String> regs = new HashMap<>(), mems = new HashMap<>(), insts = new HashMap<>(), instKind = new HashMap<>();
        final Map<String, String> fsmVar = new HashMap<>();               // fsm register -> part
        final Map<String, String[]> fsmState = new HashMap<>();           // ctl_IDLE -> {fsm part, IDLE}
        final Map<String, String[]> fsmIn = new HashMap<>();              // ctl_go -> {fsm part, go}
        final Map<String, String> syncAddr = new HashMap<>();             // m_raddr -> mem part
        final Map<String, String> outField = new HashMap<>();             // a block's output field -> out part
        final Map<String, String> counters = new HashMap<>(), priencs = new HashMap<>(), tables = new HashMap<>();
        final Map<String, String> declType = new HashMap<>();
        final Map<String, StatementTree> regDecl = new HashMap<>();           // a register's declaration
        final Map<String, List<StatementTree>> regSets = new HashMap<>();     // the statements that set it
        StatementTree cur;
        final Map<String, String> domains = new HashMap<>();                 // a ClockDomain local -> the domain's name
        final Map<String, StatementTree> javaValues = new LinkedHashMap<>();
        final Map<String, List<StatementTree>> codeRuns = new HashMap<>();   // a code part -> its statements
        final Map<String, Map<String, String>> codePorts = new HashMap<>();  // a code part -> local -> its port
        // what the previous drawing had, by name: geometry and ids are kept
        final Map<String, Map<String, Object>> prevByKey = new HashMap<>();
        final Set<String> usedIds = new HashSet<>();
        final Map<String, Integer> widths;

        ModuleReader(ClassTree cls, Map<String, Object> prev, String where, boolean isBlock) {
            this.cls = cls;
            this.widths = JavaReader.this.widths.getOrDefault(cls.getSimpleName().toString(), Map.of());
            this.prev = prev == null ? Map.of() : prev;
            this.where = where;
            this.isBlock = isBlock;
            if (!this.prev.isEmpty() && this.prev.get("parts") != null) {
                Map<String, String> vars = Map.of();
                try { vars = CodeGen.generate(withBlocks(this.prev)).partVars(); } catch (RuntimeException ignored) { /* an old drawing that no longer generates */ }
                for (var p : CodeGen.list(this.prev.get("parts"))) {
                    String k = keyOf(String.valueOf(p.get("type")), CodeGen.map(p.get("params")), vars.get(String.valueOf(p.get("id"))));
                    if (k != null) prevByKey.putIfAbsent(k, p);
                }
            }
        }

        Map<String, Object> withBlocks(Map<String, Object> d) {
            if (d.containsKey("blocks") || previous.get("blocks") == null) return d;
            var m = new LinkedHashMap<>(d);
            m.put("blocks", previous.get("blocks"));
            return m;
        }

        String keyOf(String type, Map<String, Object> params, String var) {
            return switch (type) {
                case "in", "out", "reg", "mem", "fsm", "assert", "assume", "cover", "block", "module", "code", "domain" -> type + ":" + params.get("name");
                default -> var == null ? null : type + ":" + var;
            };
        }

        String add(String type, Map<String, Object> params, String var) {
            var p = new LinkedHashMap<String, Object>();
            var old = prevByKey.get(keyOf(type, params, var));
            String id = old != null && !usedIds.contains(String.valueOf(old.get("id"))) ? String.valueOf(old.get("id")) : null;
            if (id == null) { do { id = "j" + nextId++; } while (usedIds.contains(id)); }
            usedIds.add(id);
            p.put("id", id);
            p.put("type", type);
            if (old != null) { p.put("x", old.get("x")); p.put("y", old.get("y")); p.put("rot", old.getOrDefault("rot", 0)); p.put("mir", old.getOrDefault("mir", false)); }
            else { p.put("x", null); p.put("y", null); p.put("rot", 0); p.put("mir", false); }
            // parameters the Java does not say (a module's bundle choice, a part's label) come from before
            if (old != null) {
                var op = CodeGen.map(old.get("params"));
                for (String k : List.of("bundle", "unbundle", "title")) if (op.containsKey(k) && !params.containsKey(k)) params.put(k, op.get(k));
            }
            p.put("params", params);
            parts.put(id, p);
            return id;
        }

        void inDomain(String id, String domain) {
            var p = parts.get(id);
            if (p != null && Parts.SEQUENTIAL.contains(String.valueOf(p.get("type")))) CodeGen.map(p.get("params")).put("domain", domain);
        }

        void wire(String part, String pin, Pin from) {
            for (var c : conns) if (c[0].equals(part) && c[1].equals(pin)) {
                if (!from.equals(c[2])) throw new Unrec("a second value for one pin (Java's last set wins; a drawing has one wire)");
                return;   // a memory's address, written once for the read and once for the write
            }
            conns.add(new Object[]{part, pin, from});
        }

        Map<String, Object> read() {
            MethodTree ctor = null;
            for (var m : cls.getMembers()) if (m instanceof MethodTree mt && mt.getName().contentEquals("<init>")) ctor = mt;
            if (ctor == null) throw new IllegalArgumentException(where + "the class has no constructor.");
            for (var p : ctor.getParameters())
                if (!p.getType().toString().equals("Clock"))
                    throw new IllegalArgumentException(where + "the constructor takes " + p.getType() + " " + p.getName()
                            + "; a drawing has no parameters. Use the class as a module part instead.");
            // ports declared as fields (public final Bool en = input("en", Bool.TYPE)) come first, as in Java
            var stmts = new ArrayList<StatementTree>();
            for (var m : cls.getMembers()) if (m instanceof VariableTree v && v.getInitializer() != null) stmts.add(v);
            stmts.addAll(ctor.getBody().getStatements());
            // in order: a statement of the dialect becomes parts; a run of other statements becomes a code part
            // as soon as it ends, so that the statements after it read the values it gives
            List<StatementTree> run = new ArrayList<>();
            int k = 0;
            for (var st : stmts) {
                if (!run.isEmpty() && dialect(st)) { codePart(run, stmts, ++k); run = new ArrayList<>(); }
                cur = st;
                // a Java value of elaboration (int n = 4): not hardware; a code part that reads it gets a copy
                if (st instanceof VariableTree v && v.getType() != null && JAVA_VALUE.matcher(v.getType().toString()).matches()) { javaValues.put(v.getName().toString(), st); continue; }
                int parts0 = parts.size(), conns0 = conns.size(), next0 = nextId;
                var locals0 = new LinkedHashMap<>(locals);
                try { statement(st); }
                catch (Unrec u) {
                    // undo what the statement did before it turned out not to be the dialect
                    var ids = new ArrayList<>(parts.keySet()).subList(parts0, parts.size());
                    for (String id : List.copyOf(ids)) { parts.remove(id); usedIds.remove(id); }
                    conns.subList(conns0, conns.size()).clear();
                    nextId = next0;
                    locals.clear(); locals.putAll(locals0);
                    run.add(st);
                }
            }
            if (!run.isEmpty()) codePart(run, stmts, ++k);
            for (var old : CodeGen.list(prev.get("parts")))
                if ("domain".equals(old.get("type")) && "clock".equals(CodeGen.map(old.get("params")).get("name")))
                    add("domain", new LinkedHashMap<>(CodeGen.map(old.get("params"))), null);
            // wires: a source pin and the pins it drives
            var bySource = new LinkedHashMap<Pin, List<String[]>>();
            for (var c : conns) bySource.computeIfAbsent((Pin) c[2], x -> new ArrayList<>()).add(new String[]{(String) c[0], (String) c[1]});
            var nets = new ArrayList<Object>();
            int n = 1;
            for (var e : bySource.entrySet()) {
                if (e.getKey() == null) continue;
                var net = new LinkedHashMap<String, Object>();
                net.put("id", "n" + n++);
                var pins = new ArrayList<Object>();
                pins.add(List.of(e.getKey().part(), e.getKey().pin()));
                for (var r : e.getValue()) pins.add(List.of(r[0], r[1]));
                net.put("pins", pins);
                String name = netNames.get(e.getKey().part() + "." + e.getKey().pin());
                if (name != null) net.put("name", name);
                nets.add(net);
            }
            var d = new LinkedHashMap<String, Object>();
            d.put("parts", new ArrayList<>(parts.values()));
            d.put("nets", nets);
            return d;
        }

        // ---------------------------------------------------------------- statements

        Map<String, List<StatementTree>> copySets() {
            var m = new HashMap<String, List<StatementTree>>();
            for (var e : regSets.entrySet()) m.put(e.getKey(), new ArrayList<>(e.getValue()));
            return m;
        }

        /** Whether a statement is the dialect's, tried on a copy so that nothing is changed. */
        boolean dialect(StatementTree st) {
            int parts0 = parts.size(), conns0 = conns.size(), next0 = nextId;
            var saved = new Object[]{new LinkedHashMap<>(locals), new HashMap<>(netNames), new HashMap<>(regs), new HashMap<>(mems), new HashMap<>(insts),
                    new HashMap<>(instKind), new HashMap<>(fsmVar), new HashMap<>(fsmState), new HashMap<>(fsmIn), new HashMap<>(syncAddr), new HashMap<>(outField),
                    new HashMap<>(counters), new HashMap<>(priencs), new HashMap<>(tables), new HashMap<>(declType), new HashMap<>(regDecl), copySets(), new HashMap<>(domains)};
            var paramsCopy = new HashMap<String, Map<String, Object>>();
            for (var e : parts.entrySet()) paramsCopy.put(e.getKey(), new LinkedHashMap<>(CodeGen.map(e.getValue().get("params"))));
            boolean ok;
            try { statement(st); ok = true; } catch (Unrec u) { ok = false; } catch (RuntimeException e) { ok = true; }
            var ids = new ArrayList<>(parts.keySet()).subList(parts0, parts.size());
            for (String id : List.copyOf(ids)) { parts.remove(id); usedIds.remove(id); }
            conns.subList(conns0, conns.size()).clear();
            nextId = next0;
            @SuppressWarnings({"unchecked", "rawtypes"})
            Map[] targets = {locals, netNames, regs, mems, insts, instKind, fsmVar, fsmState, fsmIn, syncAddr, outField, counters, priencs, tables, declType, regDecl, regSets, domains};
            for (int i = 0; i < targets.length; i++) { @SuppressWarnings("unchecked") Map<Object, Object> t = targets[i]; t.clear(); t.putAll((Map<?, ?>) saved[i]); }
            for (var e : paramsCopy.entrySet()) { var pm = CodeGen.map(parts.get(e.getKey()).get("params")); pm.clear(); pm.putAll(e.getValue()); }
            return ok;
        }

        void statement(StatementTree st) {
            if (st instanceof VariableTree v) {
                if (v.getInitializer() == null) throw new Unrec("a declaration without a value");
                declare(v.getName().toString(), v.getType() == null ? "var" : v.getType().toString(), v.getInitializer());
                return;
            }
            if (st instanceof ExpressionStatementTree es) {
                var e = es.getExpression();
                if (e instanceof AssignmentTree a && a.getVariable() instanceof IdentifierTree id) { declare(id.getName().toString(), "field", a.getExpression()); return; }
                if (e instanceof MethodInvocationTree mi) { invocation(mi); return; }
            }
            throw new Unrec("not a statement of the dialect");
        }

        void declare(String name, String type, ExpressionTree init) {
            declType.put(name, type);
            var call = call(init);
            // T x = withClock(d, () -> EXPR): EXPR's registers run in the domain d
            if (call != null && call.on() == null && call.receiver() == null && call.fn().equals("withClock") && call.args().size() == 2
                    && call.args().get(0) instanceof IdentifierTree dv && domains.containsKey(dv.getName().toString())
                    && call.args().get(1) instanceof LambdaExpressionTree lam && lam.getBody() instanceof ExpressionTree body) {
                var before = new HashSet<>(parts.keySet());
                declare(name, type, body);
                var touched = new LinkedHashSet<String>();
                for (String id : parts.keySet()) if (!before.contains(id)) touched.add(id);
                if (locals.get(name) != null) touched.add(locals.get(name).part());
                for (String id : touched) inDomain(id, domains.get(dv.getName().toString()));
                return;
            }
            if (call != null && call.on() == null && call.receiver() == null && call.fn().equals("clockDomain") && call.args().size() == 1) {
                String dn = str(call.args().getFirst());
                var prm = params("name", dn, "period", 2, "phase", 0);
                var old = prevByKey.get("domain:" + dn);
                if (old != null) { var op = CodeGen.map(old.get("params")); prm.put("period", op.getOrDefault("period", 2)); prm.put("phase", op.getOrDefault("phase", 0)); }
                add("domain", prm, null);
                domains.put(name, dn);
                return;
            }
            if (call != null) {
                String fn = call.fn();
                if (call.on() == null) {
                    switch (fn) {
                        case "input" -> {
                            String pn = str(call.args().get(0));
                            String id = add("in", params("name", pn, "width", width(call.args().get(1))), null);
                            locals.put(name, new Pin(id, "y"));
                            return;
                        }
                        case "output" -> {   // a block's output field
                            String pn = str(call.args().get(0));
                            String id = add("out", params("name", pn, "width", width(call.args().get(1))), null);
                            outField.put(name, id);
                            return;
                        }
                        case "reg" -> {
                            String rn = str(call.args().get(0));
                            String t = text(call.args().get(1));
                            var em = Pattern.compile("HwEnum\\.of\\((\\w+)\\.class\\)").matcher(t);
                            if (em.matches()) {
                                var states = enums.get(em.group(1));
                                if (states == null) throw new Unrec("an enum this file does not declare");
                                String id = add("fsm", params("name", rn, "states", String.join(" ", states), "inputs", "", "transitions", "", "outputs", ""), null);
                                fsmVar.put(name, id);
                                locals.put(name, new Pin(id, "state"));
                                return;
                            }
                            String id = add("reg", params("name", rn, "width", width(call.args().get(1)), "init", call.args().size() > 2 ? text(call.args().get(2)).replaceAll("[Ll]$", "") : "0", "enable", false), null);
                            regs.put(name, id);
                            regDecl.put(name, cur);
                            locals.put(name, new Pin(id, "q"));
                            return;
                        }
                        case "wire" -> {                       // a sync memory's read address (m_raddr)
                            syncAddr.put(name, null);
                            return;
                        }
                        case "instance" -> { instance(name, type, call); return; }
                        default -> { }
                    }
                }
                // mem("m", UInt.w(8), 16)[.init(...)]
                var mc = memCall(init);
                if (mc != null) { mems.put(name, mc); return; }
                if (call.on() != null && call.on().equals("Util") && fn.equals("counter")) {
                    String id = add("counter", params("max", intArg(call.args().get(0))), name);
                    counters.put(name, id);
                    wire(id, "inc", ref(call.args().get(1), true));
                    return;
                }
                if (call.on() != null && call.on().equals("Util") && fn.equals("priorityEncoder")) {
                    String id = add("prienc", params(), name);
                    priencs.put(name, id);
                    wire(id, "a", ref(call.args().get(0), false));
                    return;
                }
                if (fn.equals("apply") && init instanceof MethodInvocationTree mi && mi.getMethodSelect() instanceof MemberSelectTree ms
                        && call(ms.getExpression()) instanceof Call pc && pc.fn().equals("parse") && "TruthTable".equals(pc.on())) {
                    table(name, pc, call);
                    return;
                }
            }
            // T x = EXPR.named("x")  (or without the name)
            ExpressionTree base = init;
            String named = null;
            var c2 = call(init);
            if (c2 != null && c2.fn().equals("named") && c2.receiver() != null) { base = c2.receiver(); named = str(c2.args().get(0)); }
            // the studio's own: a state machine's state, output and input Bools; a sync read; library outputs
            var bc = call(base);
            if (bc != null && bc.fn().equals("is") && bc.receiver() instanceof IdentifierTree r && fsmVar.containsKey(r.getName().toString())) {
                String st = bc.args().get(0).toString();
                fsmState.put(name, new String[]{fsmVar.get(r.getName().toString()), st.substring(st.lastIndexOf('.') + 1)});
                return;
            }
            if (fsmOutput(name, base)) return;
            if (bc != null && bc.fn().equals("readSync") && bc.receiver() instanceof IdentifierTree mr && mems.containsKey(mr.getName().toString())
                    && bc.args().getFirst() instanceof IdentifierTree ra && syncAddr.containsKey(ra.getName().toString())) {
                String mid = mems.get(mr.getName().toString());
                CodeGen.map(parts.get(mid).get("params")).put("read", "sync");
                syncAddr.put(ra.getName().toString(), mid);
                locals.put(name, new Pin(mid, "rdata"));
                return;
            }
            if (bc != null && bc.receiver() instanceof IdentifierTree rr) {
                String rv = rr.getName().toString();
                if (counters.containsKey(rv) && (bc.fn().equals("value") || bc.fn().equals("wrap"))) { source(name, new Pin(counters.get(rv), bc.fn())); return; }
                if (priencs.containsKey(rv) && (bc.fn().equals("bits") || bc.fn().equals("valid"))) { source(name, new Pin(priencs.get(rv), bc.fn().equals("bits") ? "index" : "valid")); return; }
                if (tables.containsKey(rv) && bc.fn().equals("get")) { source(name, new Pin(tables.get(rv), str(bc.args().getFirst()))); return; }
            }
            // a plain alias (an fsm's input, fsm_go = go): the same wire
            if (base instanceof IdentifierTree || isBitZero(base)) {
                Pin p = ref(base, true);
                locals.put(name, p);
                if (named != null && name.contains("_")) fsmIn.put(name, new String[]{null, name.substring(name.indexOf('_') + 1)});
                return;
            }
            Pin out = expression(base, name);
            source(name, out);
        }

        void source(String name, Pin p) {
            locals.put(name, p);
            netNames.put(p.part() + "." + p.pin(), name);
        }

        boolean fsmOutput(String name, ExpressionTree base) {
            // ctl_busy = ctl_RUN.or(ctl_DRAIN), or Bool.of(false)
            var ops = new ArrayList<String>();
            if (!flatten(base, "or", ops)) return false;
            String fsm = null;
            var states = new ArrayList<String>();
            for (String o : ops) {
                var s = fsmState.get(o);
                if (s == null) return false;
                if (fsm != null && !fsm.equals(s[0])) return false;
                fsm = s[0];
                states.add(s[1]);
            }
            if (fsm == null) return false;
            var prm = CodeGen.map(parts.get(fsm).get("params"));
            String var = fsmVarName(fsm);
            String outName = name.startsWith(var + "_") ? name.substring(var.length() + 1) : name;
            String lines = String.valueOf(prm.get("outputs"));
            prm.put("outputs", (lines.isEmpty() ? "" : lines + "\n") + outName + " : " + String.join(" ", states));
            locals.put(name, new Pin(fsm, outName));
            return true;
        }

        String fsmVarName(String part) { for (var e : fsmVar.entrySet()) if (e.getValue().equals(part)) return e.getKey(); return "fsm"; }

        boolean flatten(ExpressionTree e, String op, List<String> out) {
            if (e instanceof IdentifierTree id) { out.add(id.getName().toString()); return true; }
            var c = call(e);
            if (c != null && c.fn().equals(op) && c.receiver() != null && c.args().size() == 1) return flatten(c.receiver(), op, out) && flatten(c.args().get(0), op, out);
            return false;
        }

        void instance(String name, String type, Call call) {
            String in = str(call.args().get(0));
            if (!(call.args().get(1) instanceof LambdaExpressionTree lam) || !(lam.getBody() instanceof ExpressionTree body)) throw new Unrec("an instance that is not made by an expression");
            String bodyText = text(body);
            var nm = Pattern.compile("new (\\w+)\\((clk)?\\)").matcher(bodyText);
            if (nm.matches() && blockClasses.contains(nm.group(1))) {
                String id = add("block", params("block", nm.group(1), "name", in), null);
                insts.put(name, id); instKind.put(name, "block");
                return;
            }
            if (nm.matches() && nested.containsKey(nm.group(1))) {
                String c = nm.group(1);
                String source = text(nested.get(c));
                var pr = modules.probeSource(c, source);
                if (pr.get("error") != null) throw new IllegalArgumentException(where + "the class " + c + " does not compile on its own: " + pr.get("error"));
                String id = add("code", params("name", in, "cls", c, "source", source, "pins", pr.get("pins")), null);
                insts.put(name, id); instKind.put(name, "code");
                return;
            }
            // a module from Java: probe it for its pins
            var imps = new ArrayList<String>();
            for (String im : imports) {
                String simple = im.substring(im.lastIndexOf('.') + 1);
                if (!im.startsWith("org.eninehq.tessera.core.") && Pattern.compile("\\b" + Pattern.quote(simple) + "\\b").matcher(bodyText).find()) imps.add(im);
            }
            var pr = modules.probe(bodyText, imps);
            if (pr.get("error") != null) throw new IllegalArgumentException(where + "the module " + bodyText + ": " + pr.get("error"));
            String id = add("module", params("name", in, "module", pr.get("module"), "expr", bodyText, "imports", imps, "pins", pr.get("pins"), "clock", pr.get("clock")), null);
            insts.put(name, id); instKind.put(name, "module");
        }

        void table(String name, Call parse, Call apply) {
            String body = parse.args().getFirst() instanceof LiteralTree lt ? String.valueOf(lt.getValue()) : null;
            if (body == null) throw new Unrec("a truth table that is not a literal");
            var lines = body.strip().lines().map(String::strip).filter(l -> !l.isEmpty()).toList();
            String[] head = lines.getFirst().split("\\|");
            String ins = head[0].trim(), outs = head[1].trim();
            String id = add("table", params("inputs", ins, "outputs", outs, "rows", String.join("\n", lines.subList(1, lines.size()))), name);
            tables.put(name, id);
            var m = call(apply.args().getFirst());
            if (m == null) throw new Unrec("a truth table's inputs that are not Map.of");
            var a = m.args();
            for (int i = 0; i + 1 < a.size(); i += 2) wire(id, str(a.get(i)), ref(a.get(i + 1), true));
        }

        void invocation(MethodInvocationTree mi) {
            var c = call(mi);
            if (c == null) throw new Unrec("a call the dialect does not use");
            // output("n", T).set(v)
            if (c.fn().equals("set") && c.receiver() instanceof MethodInvocationTree om && call(om) instanceof Call oc && oc.fn().equals("output") && oc.on() == null) {
                String id = add("out", params("name", str(oc.args().get(0)), "width", width(oc.args().get(1))), null);
                wire(id, "a", ref(c.args().getFirst(), false));
                return;
            }
            if (c.fn().equals("set") && c.receiver() != null) {
                String target = text(c.receiver());
                ExpressionTree v = c.args().getFirst();
                if (regs.containsKey(target)) { wire(regs.get(target), "d", ref(v, false)); regSets.computeIfAbsent(target, x -> new ArrayList<>()).add(cur); return; }
                if (syncAddr.containsKey(target) && syncAddr.get(target) != null) { wire(syncAddr.get(target), "addr", ref(v, false)); return; }
                if (target.startsWith("this.") && outField.containsKey(target.substring(5))) { wire(outField.get(target.substring(5)), "a", ref(v, false)); return; }
                if (outField.containsKey(target)) { wire(outField.get(target), "a", ref(v, false)); return; }
                // an instance's input: u.a (a block or a code part's field), u.in.valid() (a module's path)
                int dot = target.indexOf('.');
                if (dot > 0 && insts.containsKey(target.substring(0, dot))) {
                    String inst = target.substring(0, dot), path = target.substring(dot);
                    String pid = insts.get(inst), pin = pinOfPath(pid, path, "in");
                    if (pin == null) throw new Unrec("an input the instance does not have");
                    // a stream wire between two modules: q2.in.valid().set(q1.out.valid())
                    wire(pid, pin, ref(v, false));
                    return;
                }
                throw new Unrec("a set of something the dialect does not know");
            }
            // Hw.when(en, () -> r.set(d)) and the state machines' chains
            if (c.fn().equals("when") && ("Hw".equals(c.on()) || c.on() == null && c.receiver() == null) || c.fn().equals("elseWhen")) { whenChain(mi); return; }
            if (c.fn().equals("write") && c.receiver() instanceof IdentifierTree mr && mems.containsKey(mr.getName().toString())) {
                String mid = mems.get(mr.getName().toString());
                CodeGen.map(parts.get(mid).get("params")).put("write", true);
                wire(mid, "addr", ref(c.args().get(0), false));
                wire(mid, "wdata", ref(stripZext(c.args().get(1)), false));
                wire(mid, "we", ref(c.args().get(2), true));
                return;
            }
            // withClock(d, () -> m.write(...)): a memory written in the domain d
            if (c.fn().equals("withClock") && c.on() == null && c.receiver() == null && c.args().size() == 2 && c.args().get(0) instanceof IdentifierTree dv
                    && domains.containsKey(dv.getName().toString()) && c.args().get(1) instanceof LambdaExpressionTree lam && lam.getBody() instanceof MethodInvocationTree body
                    && call(body) instanceof Call wc && wc.fn().equals("write") && wc.receiver() instanceof IdentifierTree mr && mems.containsKey(mr.getName().toString())) {
                invocation(body);
                inDomain(mems.get(mr.getName().toString()), domains.get(dv.getName().toString()));
                return;
            }
            if ("Hw".equals(c.on()) && Set.of("assertThat", "assume", "cover").contains(c.fn())) {
                String type = c.fn().equals("assertThat") ? "assert" : c.fn();
                String id = add(type, params("name", str(c.args().get(1))), null);
                wire(id, "cond", ref(c.args().get(0), true));
                return;
            }
            throw new Unrec("a call the dialect does not use");
        }

        /** The pin of an instance reached by {@code path} (".y" for a block, ".in.valid()" for a module). */
        String pinOfPath(String pid, String path, String dir) {
            var p = parts.get(pid);
            String type = String.valueOf(p.get("type"));
            var prm = CodeGen.map(p.get("params"));
            if (type.equals("block")) return path.substring(1);
            for (var q : CodeGen.list(prm.get("pins"))) if (path.equals(String.valueOf(q.get("path"))) && (dir == null || dir.equals(q.get("dir")))) return String.valueOf(q.get("name"));
            return null;
        }

        void whenChain(MethodInvocationTree mi) {
            // collect the links of the chain, outermost last
            var links = new ArrayList<Call>();
            ExpressionTree e = mi;
            while (true) {
                var c = call(e);
                if (c == null) throw new Unrec("a chain the dialect does not use");
                links.addFirst(c);
                if (c.fn().equals("when") && ("Hw".equals(c.on()) || c.on() == null && c.receiver() == null)) break;
                if (!c.fn().equals("elseWhen") || c.receiver() == null) throw new Unrec("a chain the dialect does not use");
                e = c.receiver();
            }
            // each link: (cond, () -> x.set(v))
            String fsm = null;
            var trans = new ArrayList<String>();
            for (var c : links) {
                if (!(c.args().get(1) instanceof LambdaExpressionTree lam) || !(lam.getBody() instanceof MethodInvocationTree body)) throw new Unrec("a when with a block");
                var sc = call(body);
                if (sc == null || !sc.fn().equals("set") || !(sc.receiver() instanceof IdentifierTree tv)) throw new Unrec("a when that does more than one set");
                String target = tv.getName().toString();
                if (links.size() == 1 && regs.containsKey(target)) {        // a register with an enable
                    String rid = regs.get(target);
                    CodeGen.map(parts.get(rid).get("params")).put("enable", true);
                    wire(rid, "en", ref(c.args().get(0), true));
                    wire(rid, "d", ref(sc.args().getFirst(), false));
                    regSets.computeIfAbsent(target, x -> new ArrayList<>()).add(cur);
                    return;
                }
                if (!fsmVar.containsKey(target)) throw new Unrec("a when the dialect does not use");
                fsm = fsmVar.get(target);
                // cond: ctl_FROM[.and(COND)]; value: HwEnum.lit(E.TO)
                String to = sc.args().getFirst().toString().replaceAll(".*\\.(\\w+)\\)$", "$1");
                ExpressionTree cond = c.args().get(0);
                String from, ctext;
                if (cond instanceof IdentifierTree ci && fsmState.containsKey(ci.getName().toString())) { from = fsmState.get(ci.getName().toString())[1]; ctext = "1"; }
                else {
                    var cc = call(cond);
                    if (cc == null || !cc.fn().equals("and") || !(cc.receiver() instanceof IdentifierTree fr) || !fsmState.containsKey(fr.getName().toString())) throw new Unrec("a state machine condition the dialect does not use");
                    from = fsmState.get(fr.getName().toString())[1];
                    ctext = condText(cc.args().getFirst(), fsm, 0);
                }
                trans.add(from + " -> " + to + " : " + ctext);
            }
            CodeGen.map(parts.get(fsm).get("params")).put("transitions", String.join("\n", trans));
        }

        /** A condition back as text: names of the fsm's inputs, ! & ^ |, with parentheses only where needed. */
        String condText(ExpressionTree e, String fsm, int prec) {
            if (e instanceof IdentifierTree id) {
                String n = id.getName().toString();
                var in = fsmIn.get(n);
                if (in == null && locals.containsKey(n)) in = new String[]{null, n};   // a file written before inputs had names of their own
                if (in == null) throw new Unrec("a condition on something that is not one of the state machine's inputs");
                var prm = CodeGen.map(parts.get(fsm).get("params"));
                var ins = new LinkedHashSet<>(List.of(String.valueOf(prm.get("inputs")).trim().split("\\s+")));
                ins.remove("");
                if (ins.add(in[1])) { prm.put("inputs", String.join(" ", ins)); wire(fsm, in[1], locals.get(n)); }
                return in[1];
            }
            var c = call(e);
            if (c != null && "Bool".equals(c.on()) && c.fn().equals("of")) return c.args().getFirst().toString().equals("true") ? "1" : "0";
            if (c != null && c.fn().equals("not") && c.receiver() != null && c.args().isEmpty()) return "!" + condText(c.receiver(), fsm, 9);
            int p = c == null ? -1 : switch (c.fn()) { case "or" -> 1; case "xor" -> 2; case "and" -> 3; default -> -1; };
            if (p < 0 || c.receiver() == null || c.args().size() != 1) throw new Unrec("a condition the dialect does not use");
            String sym = p == 1 ? " | " : p == 2 ? " ^ " : " & ";
            String s = condText(c.receiver(), fsm, p) + sym + condText(c.args().getFirst(), fsm, p + 1);
            return p < prec ? "(" + s + ")" : s;
        }

        // ---------------------------------------------------------------- expressions

        /** The pin a value comes from: a local, an instance's output, or an expression made into parts. */
        Pin ref(ExpressionTree e, boolean condition) {
            e = stripParens(e);
            if (condition && isBitZero(e)) e = ((MemberSelectTree) ((MethodInvocationTree) e).getMethodSelect()).getExpression();
            if (e instanceof IdentifierTree id) {
                String n = id.getName().toString();
                if (locals.containsKey(n)) return locals.get(n);
                throw new Unrec("a value the dialect did not define: " + n);
            }
            String t = text(e).replaceAll("\\s+", "");
            int dot = t.indexOf('.');
            if (dot > 0 && insts.containsKey(t.substring(0, dot))) {
                String pid = insts.get(t.substring(0, dot)), pin = pinOfPath(pid, t.substring(dot), "out");
                if (pin != null) return new Pin(pid, pin);
            }
            if (dot > 0 && fsmVar.containsKey(t.substring(0, dot)) && t.substring(dot).equals(".asUInt()")) return new Pin(fsmVar.get(t.substring(0, dot)), "state");
            if (e instanceof LiteralTree lt && lt.getValue() instanceof Number num) {   // a.add(1): a constant as small as it can be
                String id = add("const", params("value", String.valueOf(num), "width", 0), null);
                return new Pin(id, "y");
            }
            return expression(e, null);
        }

        boolean isBitZero(ExpressionTree e) {
            var c = call(e);
            if (c == null || !c.fn().equals("bit") || c.receiver() == null || c.args().size() != 1 || !c.args().getFirst().toString().equals("0")) return false;
            // x.bit(0) of a one-bit value is how the studio makes a Bool of it; of a wider value it is a slice
            String r = c.receiver().toString();
            Integer w = widths.get(r);
            return w != null && w == 1;
        }

        ExpressionTree stripParens(ExpressionTree e) { while (e instanceof ParenthesizedTree p) e = p.getExpression(); return e; }

        ExpressionTree stripZext(ExpressionTree e) {
            var c = call(e);
            return c != null && c.fn().equals("zext") && c.receiver() != null ? c.receiver() : e;
        }

        /** One builder call (or a chain of one gate) as a part; returns its output. */
        Pin expression(ExpressionTree e, String var) {
            e = stripParens(e);
            var c = call(e);
            if (c == null) throw new Unrec("not a call");
            String fn = c.fn();
            ExpressionTree r = c.receiver();
            var a = c.args();
            if ("UInt".equals(c.on()) && fn.equals("lit")) {
                String v = text(a.get(0)).replaceAll("[Ll]$", "");
                return out(add("const", params("value", v, "width", Integer.parseInt(text(a.get(1)))), var), "y");
            }
            if ("Bool".equals(c.on()) && fn.equals("of")) return out(add("const", params("value", text(a.get(0)).equals("true") ? "1" : "0", "width", 1), var), "y");
            if (Set.of("and", "or", "xor").contains(fn) && r != null && a.size() == 1) return gate(e, fn, false, var);
            if (fn.equals("not") && r != null && a.isEmpty()) {
                var inner = call(stripParens(r));
                if (inner != null && Set.of("and", "or", "xor").contains(inner.fn()) && inner.receiver() != null) return gate(stripParens(r), inner.fn(), true, var);
                String id = add("not", params(), var);
                wire(id, "a", ref(r, false));
                return out(id, "y");
            }
            if (Set.of("orR", "andR", "xorR").contains(fn) && r != null) {
                String id = add("reduce", params("op", fn.substring(0, fn.length() - 1)), var);
                wire(id, "a", ref(r, false));
                return out(id, "y");
            }
            if (Set.of("add", "addWrap", "sub", "subWrap").contains(fn) && r != null && a.size() == 1) {
                String id = add(fn.startsWith("add") ? "add" : "sub", params("mode", fn.endsWith("Wrap") ? "wrap" : "widen"), var);
                wire(id, "a", ref(r, false)); wire(id, "b", ref(a.getFirst(), false));
                return out(id, "y");
            }
            if (fn.equals("mul") && r != null && a.size() == 1) {
                String id = add("mul", params(), var);
                wire(id, "a", ref(r, false)); wire(id, "b", ref(a.getFirst(), false));
                return out(id, "y");
            }
            if (Set.of("eq", "ne", "lt", "le", "gt", "ge").contains(fn) && r != null && a.size() == 1) {
                String id = add("cmp", params("op", fn), var);
                wire(id, "a", ref(r, false)); wire(id, "b", ref(a.getFirst(), false));
                return out(id, "y");
            }
            if ((fn.equals("shl") || fn.equals("shr")) && r != null && a.size() == 1) {
                if (a.getFirst() instanceof LiteralTree lt) {
                    String id = add("shift", params("dir", fn.equals("shl") ? "left" : "right", "amount", ((Number) lt.getValue()).intValue()), var);
                    wire(id, "a", ref(r, false));
                    return out(id, "y");
                }
            }
            if ((fn.equals("dshl") || fn.equals("dshr")) && r != null && a.size() == 1) {
                String id = add("dshift", params("dir", fn.equals("dshl") ? "left" : "right"), var);
                wire(id, "a", ref(r, false)); wire(id, "n", ref(a.getFirst(), false));
                return out(id, "y");
            }
            if ("Hw".equals(c.on()) && fn.equals("mux") && a.size() == 3) {
                String id = add("mux", params("inputs", 2), var);
                wire(id, "sel", ref(a.get(0), true)); wire(id, "in1", ref(a.get(1), false)); wire(id, "in0", ref(a.get(2), false));
                return out(id, "y");
            }
            if ("Hw".equals(c.on()) && fn.equals("index") && a.size() == 2 && call(a.get(1)) instanceof Call lc && lc.fn().equals("of")) {
                String id = add("mux", params("inputs", lc.args().size()), var);
                wire(id, "sel", ref(a.get(0), false));
                for (int i = 0; i < lc.args().size(); i++) wire(id, "in" + i, ref(stripZext(lc.args().get(i)), false));
                return out(id, "y");
            }
            if ((fn.equals("bits") || fn.equals("bit")) && r != null) {
                int hi = intArg(a.get(0)), lo = fn.equals("bit") ? hi : intArg(a.get(1));
                String id = add("slice", params("hi", hi, "lo", lo), var);
                wire(id, "a", ref(r, false));
                return out(id, "y");
            }
            if ("Hw".equals(c.on()) && fn.equals("synchronize") && a.size() == 2) {
                String id = add("sync", params("stages", intArg(a.get(1))), var);
                wire(id, "a", ref(a.getFirst(), true));
                return out(id, "y");
            }
            if ("Hw".equals(c.on()) && fn.equals("cat")) {
                String id = add("concat", params("inputs", a.size()), var);
                for (int i = 0; i < a.size(); i++) wire(id, "in" + i, ref(a.get(i), false));
                return out(id, "y");
            }
            if ((fn.equals("zext") || fn.equals("trunc")) && r != null && a.size() == 1) {
                String id = add("resize", params("width", intArg(a.getFirst())), var);
                wire(id, "a", ref(r, false));
                return out(id, "y");
            }
            if ("Timing".equals(c.on()) && (fn.equals("blink") || fn.equals("every")) && a.size() == 2) {
                var d = call(a.get(1));
                if (d == null || !d.fn().equals("ofMillis")) throw new Unrec("a time the dialect does not write");
                double s = intArg(d.args().getFirst()) / 1000.0;
                return out(add(fn, params("seconds", s), var), "y");
            }
            if (fn.equals("read") && r instanceof IdentifierTree mr && mems.containsKey(mr.getName().toString())) {
                String mid = mems.get(mr.getName().toString());
                wire(mid, "addr", ref(a.getFirst(), false));
                return out(mid, "rdata");
            }
            if ("Util".equals(c.on())) {
                switch (fn) {
                    case "risingEdge", "fallingEdge" -> { String id = add("edge", params("kind", fn.startsWith("rising") ? "rising" : "falling"), var); wire(id, "a", ref(a.getFirst(), true)); return out(id, "y"); }
                    case "debounce" -> { String id = add("debounce", params("cycles", intArg(a.get(1))), var); wire(id, "a", ref(a.getFirst(), true)); return out(id, "y"); }
                    case "delay" -> { String id = add("delay", params("cycles", intArg(a.get(1))), var); wire(id, "a", ref(a.getFirst(), false)); return out(id, "y"); }
                    case "lfsr" -> { String id = add("lfsr", params("width", intArg(a.get(0))), var); wire(id, "step", ref(a.get(1), true)); return out(id, "y"); }
                    case "popCount", "reverse", "toGray", "fromGray" -> { String id = add("bitop", params("op", fn), var); wire(id, "a", ref(a.getFirst(), false)); return out(id, "y"); }
                    case "oneHot" -> { String id = add("onehot", params("n", intArg(a.get(1))), var); wire(id, "idx", ref(a.getFirst(), false)); return out(id, "y"); }
                    default -> { }
                }
            }
            throw new Unrec("a call the dialect does not use: " + fn);
        }

        Pin out(String id, String pin) { return new Pin(id, pin); }

        Pin gate(ExpressionTree e, String op, boolean inverted, String var) {
            var ops = new ArrayList<ExpressionTree>();
            collect(e, op, ops);
            String type = inverted ? Map.of("and", "nand", "or", "nor", "xor", "xnor").get(op) : op;
            String id = add(type, params("inputs", ops.size()), var);
            for (int i = 0; i < ops.size(); i++) wire(id, "in" + i, ref(ops.get(i), false));
            return out(id, "y");
        }

        void collect(ExpressionTree e, String op, List<ExpressionTree> out) {
            var c = call(stripParens(e));
            if (c != null && c.fn().equals(op) && c.receiver() != null && c.args().size() == 1) { collect(c.receiver(), op, out); out.add(c.args().getFirst()); }
            else out.add(e);
        }

        String memCall(ExpressionTree init) {
            String initWords = "";
            var c = call(init);
            if (c != null && c.fn().equals("init") && c.receiver() != null) {
                var ws = new ArrayList<String>();
                for (var a : c.args()) ws.add(text(a).replaceAll("[Ll]$", ""));
                initWords = String.join(" ", ws);
                c = call(c.receiver());
            }
            if (c == null || !c.fn().equals("mem") || c.on() != null || c.args().size() != 3) return null;
            return add("mem", params("name", str(c.args().get(0)), "width", width(c.args().get(1)), "depth", intArg(c.args().get(2)), "read", "async", "write", false, "init", initWords), null);
        }

        // ---------------------------------------------------------------- code parts

        /**
         * Statements outside the dialect as one Java code part: a module class with the statements as they
         * were written, its inputs the values they read, its outputs the values read after them.
         */
        void codePart(List<StatementTree> runIn, List<? extends StatementTree> all, int k) {
            // registers and outputs the run sets come into the code part: a register with its declaration and
            // every other statement that sets it, an output as a port of the code part that the out part shows
            var setTargets = new LinkedHashSet<String>();
            for (var st : runIn)
                new com.sun.source.util.TreeScanner<Void, Void>() {
                    @Override public Void visitMethodInvocation(MethodInvocationTree mi, Void x) {
                        if (mi.getMethodSelect() instanceof MemberSelectTree ms && ms.getIdentifier().contentEquals("set")) {
                            var r = ms.getExpression();
                            if (r instanceof MemberSelectTree th && th.getExpression().toString().equals("this")) setTargets.add(th.getIdentifier().toString());
                            if (r instanceof IdentifierTree id) setTargets.add(id.getName().toString());
                        }
                        return super.visitMethodInvocation(mi, x);
                    }
                }.scan(st, null);
            var moved = new ArrayList<StatementTree>();
            var regOut = new LinkedHashMap<String, Pin>();     // a register moved in -> the pin it was read from
            var outsSet = new ArrayList<String>();              // outputs the run sets
            var merged = new LinkedHashMap<Pin, String>();      // a merged code part's output -> the local it gave
            for (String t : setTargets) {
                Pin from = locals.get(t);
                if (from != null && codeRuns.containsKey(from.part()) && parts.containsKey(from.part())) {
                    // set here, made by an earlier code part: the two become one
                    String cid = from.part();
                    for (var st : codeRuns.get(cid)) if (!moved.contains(st)) moved.add(st);
                    for (var e : codePorts.get(cid).entrySet()) merged.put(new Pin(cid, e.getValue()), e.getKey());
                    String ccls = String.valueOf(CodeGen.map(parts.get(cid).get("params")).get("cls"));
                    report.removeIf(r -> r.contains("code part " + ccls + " ") || r.endsWith("code part " + ccls + "."));
                    parts.remove(cid);
                    conns.removeIf(c -> c[0].equals(cid));
                    continue;
                }
                if (regs.containsKey(t)) {
                    String rid = regs.remove(t);
                    moved.add(regDecl.get(t));
                    for (var st : regSets.getOrDefault(t, List.of())) if (!moved.contains(st)) moved.add(st);
                    parts.remove(rid);
                    conns.removeIf(c -> c[0].equals(rid));
                    regOut.put(t, new Pin(rid, "q"));
                } else if (outField.containsKey(t)) outsSet.add(t);
            }
            // in source order
            var run = new ArrayList<StatementTree>(moved);
            run.addAll(runIn);
            run.sort(java.util.Comparator.comparingLong(st -> pos.getStartPosition(cu, st)));
            // copies of the Java values the statements read, transitively, in source order
            var need = new LinkedHashSet<StatementTree>();
            boolean grew = true;
            while (grew) {
                grew = false;
                var names = new HashSet<String>();
                for (var st : concat(run, need))
                    new com.sun.source.util.TreeScanner<Void, Void>() {
                        @Override public Void visitIdentifier(IdentifierTree id, Void x) { names.add(id.getName().toString()); return null; }
                    }.scan(st, null);
                for (var e : javaValues.entrySet()) if (names.contains(e.getKey()) && need.add(e.getValue())) grew = true;
            }
            for (var st : need) if (!run.contains(st)) run.add(st);
            run.sort(java.util.Comparator.comparingLong(st -> pos.getStartPosition(cu, st)));
            var uses = new LinkedHashSet<String>();
            var defs = new LinkedHashSet<String>();
            for (var st : run) {
                if (st instanceof VariableTree v) defs.add(v.getName().toString());
                new com.sun.source.util.TreeScanner<Void, Void>() {
                    @Override public Void visitIdentifier(IdentifierTree id, Void x) { uses.add(id.getName().toString()); return null; }
                }.scan(st, null);
            }
            // locals the run assigns again (p = p.xor(...)): their old value goes in, their new one comes out
            var reassigned = new LinkedHashSet<String>();
            for (var st : run)
                new com.sun.source.util.TreeScanner<Void, Void>() {
                    @Override public Void visitAssignment(AssignmentTree a, Void x) { if (a.getVariable() instanceof IdentifierTree id) reassigned.add(id.getName().toString()); return super.visitAssignment(a, x); }
                    @Override public Void visitCompoundAssignment(CompoundAssignmentTree a, Void x) { if (a.getVariable() instanceof IdentifierTree id) reassigned.add(id.getName().toString()); return super.visitCompoundAssignment(a, x); }
                }.scan(st, null);
            uses.retainAll(locals.keySet());
            uses.removeAll(defs);
            reassigned.retainAll(uses);
            // values defined in the run that later statements read
            var later = new LinkedHashSet<String>();
            int last = all.indexOf(run.getLast());
            for (int i = last + 1; i < all.size(); i++)
                new com.sun.source.util.TreeScanner<Void, Void>() {
                    @Override public Void visitIdentifier(IdentifierTree id, Void x) { later.add(id.getName().toString()); return null; }
                }.scan(all.get(i), null);
            var outs = new ArrayList<String>();
            for (String d : defs) if (!javaValues.containsKey(d) && (later.contains(d) || regOut.containsKey(d) || merged.containsValue(d))) outs.add(d);
            uses.removeAll(outsSet);
            var outPort = new LinkedHashMap<String, String>();
            for (String o : outs) outPort.put(o, o);
            for (String r : reassigned) if (later.contains(r)) { outs.add(r); outPort.put(r, r + "_next"); }
            boolean clock = run.stream().anyMatch(st -> Pattern.compile("\\bclk\\b").matcher(text(st)).find());
            String cn = "Code" + k + (isBlock ? where.replaceAll("\\W", "") : "");
            var body = new StringBuilder();
            body.append("public static class ").append(cn).append(" extends Module {\n");
            // the ports are public fields; the statements read and assign locals of the same names
            for (String u : uses) body.append("    public final ").append(jtype(u)).append(' ').append(u).append(";\n");
            for (String o : outs) body.append("    public final ").append(jtype(o)).append(' ').append(outPort.get(o)).append(";\n");
            for (String o : outsSet) body.append("    public final ").append(jtype(o)).append(' ').append(o).append(";\n");
            body.append("\n    public ").append(cn).append("(").append(clock ? "Clock clk" : "").append(") {\n");
            for (String u : uses) body.append("        this.").append(u).append(" = input(\"").append(u).append("\", ").append(typeExpr(u)).append(");\n");
            for (String o : outs) body.append("        this.").append(outPort.get(o)).append(" = output(\"").append(outPort.get(o)).append("\", ").append(typeExpr(o)).append(");\n");
            for (String o : outsSet) body.append("        this.").append(o).append(" = output(\"").append(o).append("\", ").append(typeExpr(o)).append(");\n");
            for (String r : reassigned) body.append("        ").append(jtype(r)).append(' ').append(r).append(" = this.").append(r).append(";\n");
            for (var st : run) for (String l : unstatic(text(st)).split("\n", -1)) body.append("        ").append(l.strip()).append('\n');
            for (String o : outs) body.append("        this.").append(outPort.get(o)).append(".set(").append(o).append(");\n");
            body.append("    }\n}");
            String source = body.toString();
            var pr = modules.probeSource(cn, source);
            if (pr.get("error") != null)
                throw new IllegalArgumentException(where + "lines " + line(run.getFirst()) + "–" + line(run.getLast()) + " cannot be a code part of their own: " + pr.get("error")
                        + " (they may set a register or a port declared elsewhere; move those into the same lines).");
            String id = add("code", params("name", cn.toLowerCase(), "cls", cn, "source", source, "pins", pr.get("pins"), "clock", clock), null);
            for (String u : uses) wire(id, u, locals.get(u));
            for (String o : outs) source(o, new Pin(id, outPort.get(o)));
            codeRuns.put(id, run);
            codePorts.put(id, new LinkedHashMap<>(outPort));
            for (var e : merged.entrySet()) {
                var np = new Pin(id, outPort.get(e.getValue()));
                for (int i = 0; i < conns.size(); i++) if (e.getKey().equals(conns.get(i)[2])) conns.set(i, new Object[]{conns.get(i)[0], conns.get(i)[1], np});
            }
            // what read a moved register now reads the code part's port
            for (var e : regOut.entrySet()) {
                var np = new Pin(id, e.getKey());
                for (int i = 0; i < conns.size(); i++) if (e.getValue().equals(conns.get(i)[2])) conns.set(i, new Object[]{conns.get(i)[0], conns.get(i)[1], np});
            }
            for (String o : outsSet) { wire(outField.get(o), "a", new Pin(id, o)); outField.remove(o); }
            report.add(where + "lines " + line(run.getFirst()) + "–" + line(run.getLast()) + " are outside the drawing's dialect and became the Java code part " + cn
                    + (uses.isEmpty() ? "" : " (it reads " + String.join(", ", uses) + ")") + (outs.isEmpty() ? "" : " (it gives " + String.join(", ", outs) + ")") + ".");
        }

        String jtype(String local) {
            Integer w = widths.get(local);
            String t = declType.getOrDefault(local, "");
            return t.equals("Bool") || (w != null && w == 1 && !t.equals("UInt")) ? "Bool" : "UInt";
        }

        String typeExpr(String local) {
            if (declType.getOrDefault(local, "").equals("Bool")) return "Bool.TYPE";
            Integer w = widths.get(local);
            if (w == null) throw new IllegalArgumentException(where + "the width of " + local + " is not known (it has no name in the hardware); give it one with .named(\"" + local + "\").");
            return jtype(local).equals("Bool") ? "Bool.TYPE" : "UInt.w(" + w + ")";
        }
    }

    // ---------------------------------------------------------------- helpers

    /** A method call: what it is called on (a class name such as Hw, or null), the receiver, the name and arguments. */
    private record Call(String on, ExpressionTree receiver, String fn, List<? extends ExpressionTree> args) {}

    private Call call(ExpressionTree e) {
        while (e instanceof ParenthesizedTree p) e = p.getExpression();
        if (!(e instanceof MethodInvocationTree mi)) return null;
        var sel = mi.getMethodSelect();
        if (sel instanceof IdentifierTree id) return new Call(null, null, id.getName().toString(), mi.getArguments());
        if (sel instanceof MemberSelectTree ms) {
            String on = ms.getExpression() instanceof IdentifierTree id && Character.isUpperCase(id.getName().charAt(0)) ? id.getName().toString()
                    : ms.getExpression().toString().equals("java.util.List") ? "List" : null;
            return new Call(on, on == null ? ms.getExpression() : null, ms.getIdentifier().toString(), mi.getArguments());
        }
        return null;
    }

    private static String str(ExpressionTree e) {
        if (e instanceof LiteralTree lt && lt.getValue() instanceof String s) return s;
        throw new Unrec("not a string");
    }

    private int intArg(ExpressionTree e) {
        if (e instanceof LiteralTree lt && lt.getValue() instanceof Number n) return n.intValue();
        throw new Unrec("not a whole number");
    }

    private int width(ExpressionTree t) {
        String s = text(t).replaceAll("\\s+", "");
        if (s.equals("Bool.TYPE")) return 1;
        var m = Pattern.compile("UInt\\.w\\((\\d+)\\)").matcher(s);
        if (m.matches()) return Integer.parseInt(m.group(1));
        throw new Unrec("a type the dialect does not write");
    }

    /** A code part is nested in another file: calls through the file's static imports (when(...)) are written in full (Hw.when(...)). */
    private String unstatic(String code) {
        for (String q : staticImports) {
            int dot = q.lastIndexOf('.');
            String member = q.substring(dot + 1), owner = q.substring(0, dot);
            String cls = owner.substring(owner.lastIndexOf('.') + 1);
            if (member.equals("*")) continue;
            code = code.replaceAll("(?<![.\\w])" + Pattern.quote(member) + "\\s*\\(", cls + "." + member + "(");
        }
        return code;
    }

    private static List<StatementTree> concat(List<StatementTree> a, java.util.Collection<StatementTree> b) {
        var l = new ArrayList<StatementTree>(a);
        l.addAll(b);
        return l;
    }

    private static Map<String, Object> params(Object... kv) {
        var m = new LinkedHashMap<String, Object>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }

    @SuppressWarnings("unused")
    private static BigInteger unused() { return BigInteger.ZERO; }
}
