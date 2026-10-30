package org.eninehq.tessera.checker;

import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.UnaryTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.Plugin;
import com.sun.source.util.TaskEvent;
import com.sun.source.util.TaskListener;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;

/**
 * Compile-time checks for Tessera designs, as a javac plugin: {@code javac -Xplugin:TesseraCheck}.
 * <ul>
 *   <li><b>Widths</b>: widths that are compile-time constants ({@code UInt.w(8)},
 *       {@code @Width(8)}) are propagated through the Tessera operations with the same rules
 *       as the elaborator; a narrowing {@code set}, mismatched bitwise operands or an
 *       out-of-range slice are compile errors.</li>
 *   <li><b>Stages</b>: inside a method, values declared after the k-th {@code stage()} call
 *       (or annotated {@code @Stage(k)}) may not be used before it.</li>
 *   <li><b>{@code @Fn}</b>: such methods may not create registers, memories, instances or
 *       ports, nor assign signals.</li>
 *   <li><b>{@code @Unsigned int/long}</b>: signed and unsigned values do not mix silently, and
 *       operators with signed Java semantics on unsigned values are errors ({@link UnsignedCheck}).</li>
 * </ul>
 * Widths that are not known at compile time (constructor parameters, loops) are skipped;
 * elaboration still checks everything.
 */
public final class TesseraCheckPlugin implements Plugin {

    static final String CORE = "org.eninehq.tessera.core.";

    /** Widths of fields, shared across the compilation (child module ports are fields). */
    private final Map<Element, Integer> fieldWidths = new HashMap<>();

    @Override public String getName() { return "TesseraCheck"; }

    @Override
    public void init(JavacTask task, String... args) {
        Trees trees = Trees.instance(task);
        task.addTaskListener(new TaskListener() {
            @Override public void finished(TaskEvent e) {
                if (e.getKind() != TaskEvent.Kind.ANALYZE || e.getTypeElement() == null) return;
                TreePath path = trees.getPath(e.getTypeElement());
                if (path == null) return;
                new Checker(trees, e.getCompilationUnit()).scan(path, null);
                new UnsignedCheck(trees, e.getCompilationUnit(), UnsignedLowering.active(task)).scan(path, null);
            }
        });
    }

    private final class Checker extends TreePathScanner<Void, Void> {
        final Trees trees;
        final CompilationUnitTree cu;
        final Map<Element, Integer> widths = new HashMap<>();
        final Map<Element, Integer> stages = new HashMap<>();
        final Map<Tree, Integer> memo = new IdentityHashMap<>();
        int stage;
        int lambdaDepth;
        ExecutableElement fnMethod;

        Checker(Trees trees, CompilationUnitTree cu) {
            this.trees = trees;
            this.cu = cu;
        }

        void error(Tree t, String msg) {
            trees.printMessage(Diagnostic.Kind.ERROR, msg, t, cu);
        }

        Element element(Tree t) {
            TreePath p = TreePath.getPath(cu, t);
            return p == null ? null : trees.getElement(p);
        }

        // ------------------------------------------------------------ annotations

        Integer annotationValue(Element e, String simpleName) {
            if (e == null) return null;
            Integer v = annotationValue(e.getAnnotationMirrors(), simpleName);
            if (v == null && e.asType() != null) v = annotationValue(e.asType().getAnnotationMirrors(), simpleName);
            return v;
        }

        Integer annotationValue(List<? extends AnnotationMirror> ams, String simpleName) {
            for (AnnotationMirror am : ams) {
                var te = (TypeElement) am.getAnnotationType().asElement();
                if (!te.getQualifiedName().contentEquals(CORE + simpleName)) continue;
                for (var en : am.getElementValues().entrySet())
                    if (en.getKey().getSimpleName().contentEquals("value") && en.getValue().getValue() instanceof Integer i) return i;
            }
            return null;
        }

        boolean hasAnnotation(Element e, String simpleName) {
            for (AnnotationMirror am : e.getAnnotationMirrors())
                if (((TypeElement) am.getAnnotationType().asElement()).getQualifiedName().contentEquals(CORE + simpleName)) return true;
            return false;
        }

        // ------------------------------------------------------------ scanning

        @Override
        public Void visitMethod(MethodTree m, Void v) {
            int savedStage = stage;
            var savedFn = fnMethod;
            stage = 0;
            Element me = trees.getElement(getCurrentPath());
            fnMethod = me instanceof ExecutableElement ee && hasAnnotation(ee, "Fn") ? ee : null;
            for (var p : m.getParameters()) {
                Element pe = trees.getElement(new TreePath(getCurrentPath(), p));
                Integer w = annotationValue(pe, "Width");
                if (w != null) widths.put(pe, w);
                Integer s = annotationValue(pe, "Stage");
                if (s != null) stages.put(pe, s);
            }
            super.visitMethod(m, v);
            stage = savedStage;
            fnMethod = savedFn;
            return null;
        }

        @Override
        public Void visitLambdaExpression(LambdaExpressionTree l, Void v) {
            lambdaDepth++;
            super.visitLambdaExpression(l, v);
            lambdaDepth--;
            return null;
        }

        @Override
        public Void visitVariable(VariableTree vt, Void v) {
            super.visitVariable(vt, v);
            Element e = trees.getElement(getCurrentPath());
            if (e == null) return null;
            Integer declared = annotationValue(e, "Width");
            Integer init = vt.getInitializer() == null ? null : width(vt.getInitializer());
            if (declared != null && init != null && init > declared)
                error(vt.getInitializer(), "value is " + init + " bits wide but '" + vt.getName() + "' is declared @Width("
                        + declared + "); narrow it explicitly with .trunc(" + declared + ") or use a wrapping operation");
            Integer w = declared != null ? declared : init;
            if (w != null) {
                widths.put(e, w);
                if (e.getKind() == ElementKind.FIELD) fieldWidths.put(e, w);
            }
            Integer s = annotationValue(e, "Stage");
            stages.put(e, s != null ? s : stage);
            return null;
        }

        @Override
        public Void visitAssignment(AssignmentTree a, Void v) {
            super.visitAssignment(a, v);
            Element e = element(a.getVariable());
            if (fnMethod != null && e != null && e.getKind() == ElementKind.FIELD)
                error(a, "@Fn method '" + fnMethod.getSimpleName() + "' assigns field '" + e.getSimpleName() + "'");
            if (e == null) return null;
            Integer w = width(a.getExpression());
            Integer declared = annotationValue(e, "Width");
            if (declared != null && w != null && w > declared)
                error(a.getExpression(), "value is " + w + " bits wide but '" + e.getSimpleName() + "' is declared @Width("
                        + declared + "); narrow it explicitly with .trunc(" + declared + ")");
            if (declared == null) {
                if (w != null) widths.put(e, w);
                else widths.remove(e);
            }
            return null;
        }

        @Override
        public Void visitIdentifier(IdentifierTree id, Void v) {
            Element e = trees.getElement(getCurrentPath());
            if (e instanceof VariableElement && isHardware(e.asType())) {
                Integer s = stages.get(e);
                if (s != null && s > stage)
                    error(id, "value '" + id.getName() + "' belongs to pipeline stage " + s + " but is used in stage " + stage
                            + "; use late(...) for values from later stages");
            }
            return super.visitIdentifier(id, v);
        }

        static final Set<String> STATEFUL = Set.of("reg", "regNext", "regInit", "regNextAll", "regNoReset", "mem", "instance",
                "input", "output", "wire", "wireDefault", "set", "write", "register", "past", "synchronize", "synchronizeGray",
                "assign", "connect", "clockDomain", "readSync");

        @Override
        public Void visitMethodInvocation(MethodInvocationTree mi, Void v) {
            super.visitMethodInvocation(mi, v);
            Element m = trees.getElement(getCurrentPath());
            if (!(m instanceof ExecutableElement ee)) return null;
            String owner = ((TypeElement) ee.getEnclosingElement()).getQualifiedName().toString();
            String name = ee.getSimpleName().toString();
            if (owner.equals("org.eninehq.tessera.pipeline.Pipeline") && name.equals("stage") && lambdaDepth == 0) stage++;
            if (fnMethod != null && owner.startsWith("org.eninehq.tessera.") && STATEFUL.contains(name))
                error(mi, "@Fn method '" + fnMethod.getSimpleName() + "' must be combinational but calls " + name + "(...)");
            checkCall(mi, ee, owner, name);
            return null;
        }

        // ------------------------------------------------------------ width rules

        boolean isHardware(TypeMirror t) {
            if (!(t instanceof javax.lang.model.type.DeclaredType dt)) return false;
            String s = ((TypeElement) dt.asElement()).getQualifiedName().toString();
            return s.startsWith(CORE + "UInt") || s.startsWith(CORE + "SInt") || s.startsWith(CORE + "Bool")
                    || s.startsWith(CORE + "Bits") || s.startsWith(CORE + "HwEnum");
        }

        Integer constant(ExpressionTree t) {
            t = strip(t);
            if (t instanceof LiteralTree l && l.getValue() instanceof Number n) return n.intValue();
            if (t instanceof UnaryTree u && u.getKind() == Tree.Kind.UNARY_MINUS) {
                Integer x = constant(u.getExpression());
                return x == null ? null : -x;
            }
            Element e = element(t);
            if (e instanceof VariableElement ve && ve.getConstantValue() instanceof Number n) return n.intValue();
            return null;
        }

        Long constantLong(ExpressionTree t) {
            t = strip(t);
            if (t instanceof LiteralTree l && l.getValue() instanceof Number n) return n.longValue();
            Element e = element(t);
            if (e instanceof VariableElement ve && ve.getConstantValue() instanceof Number n) return n.longValue();
            return null;
        }

        static ExpressionTree strip(ExpressionTree t) {
            while (t instanceof ParenthesizedTree p) t = p.getExpression();
            return t;
        }

        /** Width of a hardware type expression ({@code UInt.w(8)}, {@code Bool.TYPE}), or null. */
        Integer typeWidth(ExpressionTree t) {
            t = strip(t);
            if (t instanceof MethodInvocationTree mi && element(mi) instanceof ExecutableElement ee) {
                String owner = ((TypeElement) ee.getEnclosingElement()).getQualifiedName().toString();
                if ((owner.equals(CORE + "UInt") || owner.equals(CORE + "SInt") || owner.equals(CORE + "Bits"))
                        && ee.getSimpleName().contentEquals("w") && mi.getArguments().size() == 1)
                    return constant(mi.getArguments().getFirst());
            }
            if (t instanceof MemberSelectTree ms && ms.getIdentifier().contentEquals("TYPE")
                    && ms.getExpression().toString().endsWith("Bool")) return 1;
            Element e = element(t);
            if (e instanceof VariableElement ve && ve.getConstantValue() == null && widths.containsKey(e) && !isHardware(ve.asType()))
                return widths.get(e);
            return null;
        }

        /** Width of a hardware value expression, when it is a compile-time constant. */
        Integer width(ExpressionTree t) {
            t = strip(t);
            if (memo.containsKey(t)) return memo.get(t);
            Integer w = computeWidth(t);
            memo.put(t, w);
            return w;
        }

        Integer computeWidth(ExpressionTree t) {
            // operators on hardware values (the Tessera javac): a + b is a.add(b), and so on
            if (t instanceof com.sun.source.tree.BinaryTree b) {
                Integer l = width(b.getLeftOperand());
                Integer r = argWidth(b.getRightOperand());
                if (l == null) return null;
                return switch (b.getKind()) {
                    case PLUS, MINUS -> r == null ? null : Math.max(l, r) + 1;
                    case MULTIPLY -> r == null ? null : l + r;
                    case AND, OR, XOR -> {
                        if (r != null && !r.equals(l) && constantLong(b.getRightOperand()) == null)
                            error(b, b.getKind().name().toLowerCase() + ": operand widths differ (" + l + " vs " + r
                                    + "); extend with .zext(n) or narrow with .trunc(n) explicitly");
                        yield l;
                    }
                    case LEFT_SHIFT -> {
                        Integer n = constant(b.getRightOperand());
                        yield n == null ? l : l + n;
                    }
                    case RIGHT_SHIFT, UNSIGNED_RIGHT_SHIFT -> l;
                    case LESS_THAN, LESS_THAN_EQUAL, GREATER_THAN, GREATER_THAN_EQUAL -> 1;
                    default -> null;
                };
            }
            if (t instanceof UnaryTree u && u.getKind() == Tree.Kind.BITWISE_COMPLEMENT) return width(u.getExpression());
            if (t instanceof com.sun.source.tree.ArrayAccessTree aa && isHardware(trees.getTypeMirror(TreePath.getPath(cu, aa.getExpression()))))
                return 1;
            Element e = element(t);
            if ((t instanceof IdentifierTree || t instanceof MemberSelectTree) && e instanceof VariableElement) {
                Integer w = widths.get(e);
                if (w == null) w = fieldWidths.get(e);
                if (w == null) w = annotationValue(e, "Width");
                return w;
            }
            if (!(t instanceof MethodInvocationTree mi) || !(e instanceof ExecutableElement ee)) return null;
            String owner = ((TypeElement) ee.getEnclosingElement()).getQualifiedName().toString();
            String name = ee.getSimpleName().toString();
            var args = mi.getArguments();
            ExpressionTree recv = mi.getMethodSelect() instanceof MemberSelectTree ms ? ms.getExpression() : null;
            if (owner.equals(CORE + "Module") || owner.endsWith(".Module")) {
                // input/output/wire/reg(name?, type, init?): the HwType argument
                for (int i = args.size() - 1; i >= 0; i--) {
                    Integer tw = typeWidth(args.get(i));
                    if (tw != null) return tw;
                }
                if (name.equals("regNext") && !args.isEmpty()) return width(args.getFirst());
                return null;
            }
            if ((owner.equals(CORE + "UInt") || owner.equals(CORE + "Hw") || owner.equals(CORE + "SInt") || owner.equals(CORE + "Bits"))
                    && name.equals("lit")) {
                if (args.size() == 2) return constant(args.get(1));
                Long v = constantLong(args.getFirst());
                return v == null ? null : Math.max(1, 64 - Long.numberOfLeadingZeros(v));
            }
            if (owner.equals(CORE + "Hw") && name.equals("mux") && args.size() == 3) {
                Integer a = width(args.get(1)), b = width(args.get(2));
                return a == null || b == null ? null : Math.max(a, b);
            }
            if (!(owner.equals(CORE + "UInt") || owner.equals(CORE + "Bool") || owner.equals(CORE + "SInt") || owner.equals(CORE + "Bits"))
                    || recv == null) return null;
            Integer r = width(recv);
            if (r == null) {
                return switch (name) {
                    case "eq", "ne", "lt", "le", "gt", "ge", "bit", "orR", "andR", "xorR" -> 1;
                    case "trunc", "zext", "sext" -> constant(args.getFirst());
                    case "bits" -> {
                        Integer hi = constant(args.get(0)), lo = constant(args.get(1));
                        yield hi == null || lo == null ? null : hi - lo + 1;
                    }
                    default -> null;
                };
            }
            Integer a = args.isEmpty() ? null : argWidth(args.getFirst());
            return switch (name) {
                case "add", "sub" -> a == null ? null : Math.max(r, a) + 1;
                case "addWrap", "subWrap" -> a == null ? r : Math.max(r, a);
                case "mul" -> a == null ? null : r + a;
                case "and", "or", "xor", "not", "dshl", "dshr", "dsra", "shr", "sra", "asSInt", "asUInt", "asBits", "named", "neg" ->
                        name.equals("neg") ? r + 1 : r;
                case "shl" -> {
                    Integer n = constant(args.getFirst());
                    yield n == null ? null : r + n;
                }
                case "eq", "ne", "lt", "le", "gt", "ge", "bit", "orR", "andR", "xorR" -> 1;
                case "bits" -> {
                    Integer hi = constant(args.get(0)), lo = constant(args.get(1));
                    yield hi == null || lo == null ? null : hi - lo + 1;
                }
                case "trunc", "zext", "sext" -> constant(args.getFirst());
                case "toSInt" -> r + 1;
                case "cat" -> {
                    int sum = r;
                    for (var x : args) {
                        Integer xw = width(x);
                        if (xw == null) yield null;
                        sum += xw;
                    }
                    yield sum;
                }
                default -> null;
            };
        }

        /** Width of an operand: a hardware value, or the minimal width of a long constant. */
        Integer argWidth(ExpressionTree t) {
            Integer w = width(t);
            if (w != null) return w;
            Long v = constantLong(t);
            return v == null ? null : Math.max(1, 64 - Long.numberOfLeadingZeros(v));
        }

        void checkCall(MethodInvocationTree mi, ExecutableElement ee, String owner, String name) {
            if (!(owner.equals(CORE + "UInt") || owner.equals(CORE + "Bool") || owner.equals(CORE + "SInt") || owner.equals(CORE + "Bits")))
                return;
            if (!(mi.getMethodSelect() instanceof MemberSelectTree ms)) return;
            Integer r = width(ms.getExpression());
            if (r == null) return;
            var args = mi.getArguments();
            switch (name) {
                case "set" -> {
                    if (args.size() != 1) return;
                    Integer w = width(args.getFirst());
                    if (w != null && w > r)
                        error(args.getFirst(), "value is " + w + " bits wide but '" + ms.getExpression() + "' is " + r
                                + " bits; narrow it explicitly with .trunc(" + r + ") or use a wrapping operation");
                }
                case "and", "or", "xor" -> {
                    if (args.size() != 1) return;
                    Integer w = width(args.getFirst());
                    if (w != null && !w.equals(r) && !(constantLong(args.getFirst()) != null))
                        error(mi, name + ": operand widths differ (" + r + " vs " + w
                                + "); extend with .zext(n) or narrow with .trunc(n) explicitly");
                }
                case "bits" -> {
                    Integer hi = constant(args.get(0)), lo = constant(args.get(1));
                    if (hi != null && lo != null && (lo < 0 || hi >= r || hi < lo))
                        error(mi, "bits(" + hi + ", " + lo + ") out of range for width " + r);
                }
                case "bit" -> {
                    Integer i = constant(args.getFirst());
                    if (i != null && (i < 0 || i >= r)) error(mi, "bit " + i + " out of range for width " + r);
                }
                case "trunc" -> {
                    Integer n = constant(args.getFirst());
                    if (n != null && (n < 1 || n > r)) error(mi, "trunc(" + n + ") invalid for width " + r);
                }
                case "zext", "sext" -> {
                    Integer n = constant(args.getFirst());
                    if (n != null && n < r) error(mi, name + "(" + n + ") would narrow a " + r + "-bit value; use trunc");
                }
                default -> {}
            }
        }
    }
}
