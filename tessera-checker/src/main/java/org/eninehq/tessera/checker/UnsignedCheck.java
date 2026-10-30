package org.eninehq.tessera.checker;

import com.sun.source.tree.AnnotatedTypeTree;
import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.ArrayAccessTree;
import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.BinaryTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.CompoundAssignmentTree;
import com.sun.source.tree.ConditionalExpressionTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.ReturnTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TypeCastTree;
import com.sun.source.tree.UnaryTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import javax.lang.model.AnnotatedConstruct;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;

/**
 * Checks of {@code @Unsigned int} and {@code @Unsigned long} values, with the public compiler API
 * only (part of {@code -Xplugin:TesseraCheck}). Any annotation whose simple name is {@code Unsigned}
 * counts ({@code org.eninehq.tessera.core.Unsigned}, or a project's own), on a declaration or on
 * the type ({@code (@Unsigned int) x} is the explicit conversion).
 * <ul>
 *   <li>Signed and unsigned values do not mix silently: assignments, initialisers, arguments, returns,
 *       arithmetic, comparisons and {@code ?:} that combine an {@code @Unsigned} value with a signed
 *       one are errors that suggest a cast. Non-negative constants and hexadecimal, octal or binary
 *       literals (bit patterns) fit both.</li>
 *   <li>Operations whose Java meaning is signed are errors on {@code @Unsigned} values:
 *       {@code / % < <= > >= >>}, string concatenation, widening to {@code long}, conversion to
 *       floating point and boxing. The message names the JDK method to use. With
 *       {@code -Xplugin:TesseraSyntaxLite} the first five, string concatenation and widening to
 *       {@code long} are rewritten to those methods instead (see {@link UnsignedLowering}).</li>
 *   <li>Arguments of the JDK's unsigned and bit-pattern methods of {@code Integer} and {@code Long}
 *       ({@code divideUnsigned}, {@code toUnsignedString}, {@code toHexString}, {@code rotateLeft}, ...)
 *       may be {@code @Unsigned}; {@code divideUnsigned} and {@code remainderUnsigned} of an
 *       {@code @Unsigned} argument are {@code @Unsigned}.</li>
 * </ul>
 * Only values that are annotated (or derived from annotated ones) are unsigned, so code that does
 * not use the annotation gets no new errors.
 * <ul>
 * </ul>
 */
final class UnsignedCheck extends TreePathScanner<Void, Void> {

    /** The signedness of an int or long expression. NEUTRAL: a constant that fits both. */
    enum K { NONE, SIGNED, UNSIGNED, NEUTRAL }

    static final Set<String> UNSIGNED_ARGS = Set.of("toUnsignedString", "toUnsignedLong", "compareUnsigned", "divideUnsigned",
            "remainderUnsigned", "toHexString", "toOctalString", "toBinaryString", "bitCount", "numberOfLeadingZeros",
            "numberOfTrailingZeros", "highestOneBit", "lowestOneBit", "rotateLeft", "rotateRight", "reverse", "reverseBytes",
            "compress", "expand");
    static final Set<String> UNSIGNED_RESULT = Set.of("divideUnsigned", "remainderUnsigned");
    static final Set<String> BIT_RESULT = Set.of("highestOneBit", "lowestOneBit", "rotateLeft", "rotateRight", "reverse",
            "reverseBytes", "compress", "expand");
    static final Set<String> COUNT_RESULT = Set.of("bitCount", "numberOfLeadingZeros", "numberOfTrailingZeros");

    final Trees trees;
    final CompilationUnitTree cu;
    final boolean lowered;
    CharSequence source;
    /** Locals declared with {@code var} whose initialiser is unsigned. */
    final Set<Element> inferredUnsigned = new java.util.HashSet<>();
    /** The method whose return statements are checked; null inside lambdas. */
    ExecutableElement method;

    UnsignedCheck(Trees trees, CompilationUnitTree cu, boolean lowered) {
        this.trees = trees;
        this.cu = cu;
        this.lowered = lowered;
    }

    void error(Tree t, String msg) {
        trees.printMessage(Diagnostic.Kind.ERROR, msg, t, cu);
    }

    // ------------------------------------------------------------ classification

    static boolean unsignedAnnotation(List<? extends AnnotationMirror> ams) {
        for (AnnotationMirror am : ams)
            if (((TypeElement) am.getAnnotationType().asElement()).getSimpleName().contentEquals("Unsigned")) return true;
        return false;
    }

    static boolean annotated(AnnotatedConstruct c) {
        return c != null && unsignedAnnotation(c.getAnnotationMirrors());
    }

    /** A variable, parameter or method (its result) declared {@code @Unsigned}. */
    static boolean unsignedElement(Element e) {
        if (e == null) return false;
        if (annotated(e)) return true;
        if (e instanceof ExecutableElement ee) return annotated(ee.getReturnType());
        return annotated(e.asType());
    }

    static TypeKind primitive(TypeMirror t) {
        return t == null ? TypeKind.NONE : t.getKind();
    }

    static String name(TypeKind k) {
        return k == TypeKind.LONG ? "long" : "int";
    }

    static String box(TypeKind k) {
        return k == TypeKind.LONG ? "Long" : "Integer";
    }

    TypeKind typeOf(TreePath p) {
        return primitive(trees.getTypeMirror(p));
    }

    static TreePath child(TreePath p, Tree t) {
        return new TreePath(p, t);
    }

    static boolean castIsUnsigned(TypeCastTree c) {
        if (!(c.getType() instanceof AnnotatedTypeTree at)) return false;
        for (AnnotationTree a : at.getAnnotations()) {
            String n = a.getAnnotationType().toString();
            if (n.equals("Unsigned") || n.endsWith(".Unsigned")) return true;
        }
        return false;
    }

    K kind(TreePath p) {
        TypeKind tk = typeOf(p);
        if (tk != TypeKind.INT && tk != TypeKind.LONG) return K.NONE;
        Tree t = p.getLeaf();
        if (annotated(trees.getTypeMirror(p)) && !(t instanceof TypeCastTree)) return K.UNSIGNED;
        switch (t) {
            case ParenthesizedTree par -> { return kind(child(p, par.getExpression())); }
            case LiteralTree lit -> { return literalKind(lit); }
            case IdentifierTree id -> { return variableKind(trees.getElement(p)); }
            case com.sun.source.tree.MemberSelectTree ms -> { return variableKind(trees.getElement(p)); }
            case MethodInvocationTree mi -> {
                Element e = trees.getElement(p);
                if (!(e instanceof ExecutableElement ee)) return K.SIGNED;
                if (unsignedElement(ee)) return K.UNSIGNED;
                if (jdkIntegerMethod(ee)) {
                    String n = ee.getSimpleName().toString();
                    // only when an argument is @Unsigned: code without the annotation is never affected
                    if (UNSIGNED_RESULT.contains(n))
                        for (var a : mi.getArguments()) if (kind(child(p, a)) == K.UNSIGNED) return K.UNSIGNED;
                    if (COUNT_RESULT.contains(n)) return K.NEUTRAL;
                    if (BIT_RESULT.contains(n) && !mi.getArguments().isEmpty())
                        return kind(child(p, mi.getArguments().getFirst())) == K.UNSIGNED ? K.UNSIGNED : K.SIGNED;
                }
                return K.SIGNED;
            }
            case TypeCastTree c -> {
                if (castIsUnsigned(c)) return K.UNSIGNED;
                K inner = kind(child(p, c.getExpression()));
                return inner == K.NEUTRAL ? K.NEUTRAL : K.SIGNED;
            }
            case BinaryTree b -> {
                TreePath lp = child(p, b.getLeftOperand()), rp = child(p, b.getRightOperand());
                K l = kind(lp);
                return switch (b.getKind()) {
                    case LEFT_SHIFT, RIGHT_SHIFT, UNSIGNED_RIGHT_SHIFT -> l;
                    default -> {
                        K r = kind(rp);
                        TypeKind lt = typeOf(lp), rt = typeOf(rp);
                        // a promotion with sign extension is reported where it happens; do not cascade
                        yield promotes(l, lt, rt) || promotes(r, rt, lt) ? K.NONE : combine(l, r);
                    }
                };
            }
            case UnaryTree u -> {
                K k = kind(child(p, u.getExpression()));
                if (u.getKind() == Tree.Kind.UNARY_MINUS) return k == K.UNSIGNED ? K.UNSIGNED : K.SIGNED;
                return k;
            }
            case ConditionalExpressionTree c -> {
                TreePath tp = child(p, c.getTrueExpression()), fp = child(p, c.getFalseExpression());
                K kt = kind(tp), kf = kind(fp);
                return promotes(kt, typeOf(tp), typeOf(fp)) || promotes(kf, typeOf(fp), typeOf(tp)) ? K.NONE : combine(kt, kf);
            }
            case AssignmentTree a -> { return kind(child(p, a.getVariable())); }
            case CompoundAssignmentTree a -> { return kind(child(p, a.getVariable())); }
            case ArrayAccessTree aa -> {
                TypeMirror at = trees.getTypeMirror(child(p, aa.getExpression()));
                if (at instanceof ArrayType arr && annotated(arr.getComponentType())) return K.UNSIGNED;
                Element e = trees.getElement(child(p, aa.getExpression()));
                if (e instanceof VariableElement ve && ve.asType() instanceof ArrayType arr && annotated(arr.getComponentType()))
                    return K.UNSIGNED;
                return K.SIGNED;
            }
            default -> { return K.SIGNED; }
        }
    }

    /** The signedness of {@code a op b}; NONE after a mix (already reported), so errors do not cascade. */
    static K combine(K a, K b) {
        if (a == K.UNSIGNED && b == K.SIGNED || a == K.SIGNED && b == K.UNSIGNED) return K.NONE;
        if (a == K.UNSIGNED || b == K.UNSIGNED) return K.UNSIGNED;
        if (a == K.NEUTRAL && b == K.NEUTRAL) return K.NEUTRAL;
        return K.SIGNED;
    }

    K variableKind(Element e) {
        if (!(e instanceof VariableElement ve)) return K.SIGNED;
        if (unsignedElement(ve) || inferredUnsigned.contains(ve)) return K.UNSIGNED;
        if (ve.getConstantValue() instanceof Number n) return n.longValue() >= 0 ? K.NEUTRAL : K.SIGNED;
        return K.SIGNED;
    }

    /** Non-negative literals and bit patterns ({@code 0xFFFF_FFFF}) fit both; {@code -1} is signed. */
    K literalKind(LiteralTree lit) {
        if (!(lit.getValue() instanceof Number n)) return K.SIGNED;
        if (n.longValue() >= 0) return K.NEUTRAL;
        long start = trees.getSourcePositions().getStartPosition(cu, lit);
        CharSequence src = source();
        if (src != null && start >= 0 && start < src.length() && src.charAt((int) start) == '-') return K.SIGNED;
        return K.NEUTRAL;
    }

    CharSequence source() {
        if (source == null) {
            try {
                source = cu.getSourceFile().getCharContent(true);
            } catch (IOException e) {
                source = "";
            }
        }
        return source;
    }

    static boolean jdkIntegerMethod(ExecutableElement ee) {
        String owner = ((TypeElement) ee.getEnclosingElement()).getQualifiedName().toString();
        return owner.equals("java.lang.Integer") || owner.equals("java.lang.Long");
    }

    // ------------------------------------------------------------ conversions

    /** An expression of signedness {@code k} flows into a variable, parameter or result. */
    void checkAssign(TreePath expr, TypeMirror target, boolean targetUnsigned, String what) {
        checkAssign(expr, target, targetUnsigned, what, true);
    }

    /** {@code lowerable}: TesseraSyntaxLite rewrites a widening to long in this position. */
    void checkAssign(TreePath expr, TypeMirror target, boolean targetUnsigned, String what, boolean lowerable) {
        K k = kind(expr);
        TypeKind src = typeOf(expr);
        TypeKind dst = primitive(target);
        if (k == K.NONE || target == null) return;
        if (targetUnsigned) {
            if (k == K.SIGNED)
                error(expr.getLeaf(), "cannot convert signed " + name(src) + " to @Unsigned " + name(dst) + " implicitly" + what
                        + "; use a cast (@Unsigned " + name(dst) + ")");
            else if (k == K.UNSIGNED && src == TypeKind.INT && dst == TypeKind.LONG) widening(expr.getLeaf(), lowerable);
            return;
        }
        if (k != K.UNSIGNED) return;
        switch (dst) {
            case INT, LONG -> {
                if (src == dst)
                    error(expr.getLeaf(), "cannot convert @Unsigned " + name(src) + " to signed " + name(dst) + " implicitly" + what
                            + "; use a cast (" + name(dst) + ")");
                else if (src == TypeKind.INT) widening(expr.getLeaf(), lowerable);
            }
            case FLOAT, DOUBLE -> floating(expr.getLeaf(), src, dst);
            case DECLARED, TYPEVAR, INTERSECTION, UNION, WILDCARD ->
                    error(expr.getLeaf(), "@Unsigned " + name(src) + " cannot be boxed: the box holds the signed value; use "
                            + box(src) + ".toUnsignedString(x)" + (src == TypeKind.INT ? " or Integer.toUnsignedLong(x)" : ""));
            default -> {}
        }
    }

    void widening(Tree t, boolean lowerable) {
        if (lowered && lowerable) return;
        error(t, "widening @Unsigned int to long sign-extends; use Integer.toUnsignedLong(x)"
                + " or compile with -Xplugin:TesseraSyntaxLite");
    }

    void floating(Tree t, TypeKind src, TypeKind dst) {
        error(t, "converting @Unsigned " + name(src) + " to " + dst.toString().toLowerCase() + " uses the signed value; "
                + (src == TypeKind.INT ? "convert with Integer.toUnsignedLong(x) first" : "convert it explicitly"));
    }

    // ------------------------------------------------------------ scanning

    @Override
    public Void visitMethod(MethodTree m, Void v) {
        var saved = method;
        method = trees.getElement(getCurrentPath()) instanceof ExecutableElement ee ? ee : null;
        super.visitMethod(m, v);
        method = saved;
        return null;
    }

    @Override
    public Void visitLambdaExpression(LambdaExpressionTree l, Void v) {
        var saved = method;
        method = null;
        super.visitLambdaExpression(l, v);
        method = saved;
        return null;
    }

    @Override
    public Void visitVariable(VariableTree vt, Void v) {
        super.visitVariable(vt, v);
        if (vt.getInitializer() == null) return null;
        Element e = trees.getElement(getCurrentPath());
        if (e == null) return null;
        TreePath init = child(getCurrentPath(), vt.getInitializer());
        if (declaredWithVar(vt)) {
            if (kind(init) == K.UNSIGNED) inferredUnsigned.add(e);
            return null;
        }
        checkAssign(init, e.asType(), unsignedElement(e), "");
        return null;
    }

    /** {@code var x = ...}: the type tree is synthetic (no source position) or reads "var". */
    boolean declaredWithVar(VariableTree vt) {
        if (vt.getType() == null) return true;
        long start = trees.getSourcePositions().getStartPosition(cu, vt.getType());
        CharSequence src = source();
        if (start < 0 || src == null) return start < 0;
        int st = (int) start;
        return st + 3 <= src.length() && src.subSequence(st, st + 3).toString().equals("var")
                && (st + 3 == src.length() || !Character.isJavaIdentifierPart(src.charAt(st + 3)));
    }

    @Override
    public Void visitConditionalExpression(ConditionalExpressionTree c, Void v) {
        super.visitConditionalExpression(c, v);
        TreePath tp = child(getCurrentPath(), c.getTrueExpression()), fp = child(getCurrentPath(), c.getFalseExpression());
        K t = kind(tp), f = kind(fp);
        TypeKind tt = typeOf(tp), ft = typeOf(fp);
        if (promotes(t, tt, ft) || promotes(f, ft, tt))
            error(c, "the @Unsigned operand of ?: is promoted with sign extension; convert it with Integer.toUnsignedLong(x) first");
        else if (t == K.UNSIGNED && f == K.SIGNED || t == K.SIGNED && f == K.UNSIGNED)
            error(c, "cannot mix signed " + name(typeOf(tp)) + " and @Unsigned " + name(typeOf(tp)) + " in ?:; cast one of them");
        return null;
    }

    @Override
    public Void visitNewArray(com.sun.source.tree.NewArrayTree na, Void v) {
        super.visitNewArray(na, v);
        if (na.getInitializers() == null) return null;
        TypeMirror at = trees.getTypeMirror(getCurrentPath());
        if (getCurrentPath().getParentPath().getLeaf() instanceof VariableTree
                && trees.getElement(getCurrentPath().getParentPath()) instanceof VariableElement ve) at = ve.asType();
        if (!(at instanceof ArrayType arr)) return null;
        for (var init : na.getInitializers())
            checkAssign(child(getCurrentPath(), init), arr.getComponentType(), annotated(arr.getComponentType()),
                    " in an array initializer", false);
        return null;
    }

    @Override
    public Void visitAssignment(AssignmentTree a, Void v) {
        super.visitAssignment(a, v);
        TreePath var = child(getCurrentPath(), a.getVariable());
        checkAssign(child(getCurrentPath(), a.getExpression()), trees.getTypeMirror(var), kind(var) == K.UNSIGNED, "");
        return null;
    }

    @Override
    public Void visitReturn(ReturnTree r, Void v) {
        super.visitReturn(r, v);
        if (r.getExpression() != null && method != null)
            checkAssign(child(getCurrentPath(), r.getExpression()), method.getReturnType(), unsignedElement(method), " in a return");
        return null;
    }

    @Override
    public Void visitMethodInvocation(MethodInvocationTree mi, Void v) {
        super.visitMethodInvocation(mi, v);
        if (trees.getElement(getCurrentPath()) instanceof ExecutableElement ee)
            checkArguments(ee, mi.getArguments());
        return null;
    }

    @Override
    public Void visitNewClass(NewClassTree nc, Void v) {
        super.visitNewClass(nc, v);
        if (trees.getElement(getCurrentPath()) instanceof ExecutableElement ee)
            checkArguments(ee, nc.getArguments());
        return null;
    }

    void checkArguments(ExecutableElement ee, List<? extends ExpressionTree> args) {
        if (jdkIntegerMethod(ee) && UNSIGNED_ARGS.contains(ee.getSimpleName().toString())) return;
        var params = ee.getParameters();
        for (int i = 0; i < args.size() && i < params.size(); i++) {
            if (ee.isVarArgs() && i >= params.size() - 1) break;
            VariableElement p = params.get(i);
            checkAssign(child(getCurrentPath(), args.get(i)), p.asType(), unsignedElement(p),
                    " (argument for parameter '" + p.getSimpleName() + "' of " + ee.getSimpleName() + ")");
        }
    }

    @Override
    public Void visitTypeCast(TypeCastTree c, Void v) {
        super.visitTypeCast(c, v);
        TreePath inner = child(getCurrentPath(), c.getExpression());
        if (kind(inner) != K.UNSIGNED) return null;
        TypeKind src = typeOf(inner), dst = typeOf(getCurrentPath());
        if (src == TypeKind.INT && dst == TypeKind.LONG) widening(c, true);
        else if (castIsUnsigned(c)) return null;
        else if (dst == TypeKind.FLOAT || dst == TypeKind.DOUBLE) floating(c, src, dst);
        else if (dst == TypeKind.DECLARED) error(c, "@Unsigned " + name(src) + " cannot be boxed: the box holds the signed value");
        return null;
    }

    @Override
    public Void visitBinary(BinaryTree b, Void v) {
        super.visitBinary(b, v);
        TreePath lp = child(getCurrentPath(), b.getLeftOperand()), rp = child(getCurrentPath(), b.getRightOperand());
        K l = kind(lp), r = kind(rp);
        boolean shift = switch (b.getKind()) {
            case LEFT_SHIFT, RIGHT_SHIFT, UNSIGNED_RIGHT_SHIFT -> true;
            default -> false;
        };
        // string concatenation
        if (b.getKind() == Tree.Kind.PLUS && primitive(trees.getTypeMirror(getCurrentPath())) == TypeKind.DECLARED) {
            if (!lowered)
                for (TreePath p : List.of(lp, rp))
                    if (kind(p) == K.UNSIGNED)
                        error(p.getLeaf(), "string concatenation shows the signed value of an @Unsigned " + name(typeOf(p))
                                + "; use " + box(typeOf(p)) + ".toUnsignedString(x) or compile with -Xplugin:TesseraSyntaxLite");
            return null;
        }
        if (l == K.NONE && r == K.NONE) return null;
        // numeric promotion of an unsigned operand to a wider type
        if (!shift) {
            TypeKind lt = primitive(trees.getTypeMirror(lp)), rt = primitive(trees.getTypeMirror(rp));
            if (promotes(l, lt, rt) || promotes(r, rt, lt)) {
                error(b, "the @Unsigned operand is promoted with sign extension; convert it with Integer.toUnsignedLong(x) first");
                return null;
            }
        }
        if (!shift && (l == K.UNSIGNED && r == K.SIGNED || l == K.SIGNED && r == K.UNSIGNED)) {
            TypeKind t = l == K.UNSIGNED ? typeOf(lp) : typeOf(rp);
            error(b, "cannot mix signed " + name(t) + " and @Unsigned " + name(t) + "; cast one of them");
            return null;
        }
        signedOperator(b, b.getKind(), l, r);
        return null;
    }

    static boolean promotes(K k, TypeKind self, TypeKind other) {
        if (k != K.UNSIGNED) return false;
        if (self == TypeKind.INT) return other == TypeKind.LONG || other == TypeKind.FLOAT || other == TypeKind.DOUBLE;
        return other == TypeKind.FLOAT || other == TypeKind.DOUBLE;
    }

    @Override
    public Void visitCompoundAssignment(CompoundAssignmentTree a, Void v) {
        super.visitCompoundAssignment(a, v);
        TreePath vp = child(getCurrentPath(), a.getVariable()), ep = child(getCurrentPath(), a.getExpression());
        K l = kind(vp), r = kind(ep);
        boolean shift = switch (a.getKind()) {
            case LEFT_SHIFT_ASSIGNMENT, RIGHT_SHIFT_ASSIGNMENT, UNSIGNED_RIGHT_SHIFT_ASSIGNMENT -> true;
            default -> false;
        };
        if (!shift) {
            TypeKind lt = typeOf(vp), rt = typeOf(ep);
            if (promotes(l, lt, rt) || promotes(r, rt, lt)) {
                error(a, "the @Unsigned operand is promoted with sign extension; convert it with Integer.toUnsignedLong(x) first");
                return null;
            }
        }
        if (!shift && (l == K.UNSIGNED && r == K.SIGNED || l == K.SIGNED && r == K.UNSIGNED)) {
            TypeKind t = l == K.UNSIGNED ? typeOf(vp) : typeOf(ep);
            error(a, "cannot mix signed " + name(t) + " and @Unsigned " + name(t) + "; cast one of them");
            return null;
        }
        Tree.Kind op = switch (a.getKind()) {
            case DIVIDE_ASSIGNMENT -> Tree.Kind.DIVIDE;
            case REMAINDER_ASSIGNMENT -> Tree.Kind.REMAINDER;
            case RIGHT_SHIFT_ASSIGNMENT -> Tree.Kind.RIGHT_SHIFT;
            default -> null;
        };
        if (op == null) return null;
        // TesseraSyntaxLite lowers these only on a plain variable (no side effects to duplicate)
        boolean lowerable = lowered && a.getVariable() instanceof IdentifierTree;
        if (!lowerable) signedOperator0(a, op, l, r, false);
        return null;
    }

    void signedOperator(Tree t, Tree.Kind op, K l, K r) {
        signedOperator0(t, op, l, r, lowered);
    }

    void signedOperator0(Tree t, Tree.Kind op, K l, K r, boolean lowered) {
        if (lowered) return;
        String sym;
        String fix;
        switch (op) {
            case DIVIDE -> { sym = "/"; fix = "divideUnsigned(a, b)"; }
            case REMAINDER -> { sym = "%"; fix = "remainderUnsigned(a, b)"; }
            case LESS_THAN -> { sym = "<"; fix = "compareUnsigned(a, b) < 0"; }
            case LESS_THAN_EQUAL -> { sym = "<="; fix = "compareUnsigned(a, b) <= 0"; }
            case GREATER_THAN -> { sym = ">"; fix = "compareUnsigned(a, b) > 0"; }
            case GREATER_THAN_EQUAL -> { sym = ">="; fix = "compareUnsigned(a, b) >= 0"; }
            case RIGHT_SHIFT -> { sym = ">>"; fix = null; }
            default -> { return; }
        }
        if (op == Tree.Kind.RIGHT_SHIFT) {
            if (l != K.UNSIGNED) return;
            error(t, "operator >> on an @Unsigned value is an arithmetic shift; use >>> or compile with -Xplugin:TesseraSyntaxLite");
            return;
        }
        if (l != K.UNSIGNED && r != K.UNSIGNED) return;
        error(t, "operator " + sym + " has signed semantics in Java; for @Unsigned values use Integer/Long." + fix
                + " or compile with -Xplugin:TesseraSyntaxLite");
    }
}
