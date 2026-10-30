package org.eninehq.tessera.syntaxlite;

import com.sun.tools.javac.code.Attribute;
import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.code.Kinds.Kind;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Symbol.ClassSymbol;
import com.sun.tools.javac.code.Symbol.MethodSymbol;
import com.sun.tools.javac.code.Symbol.OperatorSymbol;
import com.sun.tools.javac.code.Symbol.VarSymbol;
import com.sun.tools.javac.code.Symtab;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.code.TypeTag;
import com.sun.tools.javac.code.Types;
import com.sun.tools.javac.jvm.ByteCodes;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.JCTree.JCAnnotatedType;
import com.sun.tools.javac.tree.JCTree.JCAnnotation;
import com.sun.tools.javac.tree.JCTree.JCArrayAccess;
import com.sun.tools.javac.tree.JCTree.JCAssign;
import com.sun.tools.javac.tree.JCTree.JCAssignOp;
import com.sun.tools.javac.tree.JCTree.JCBinary;
import com.sun.tools.javac.tree.JCTree.JCClassDecl;
import com.sun.tools.javac.tree.JCTree.JCCompilationUnit;
import com.sun.tools.javac.tree.JCTree.JCConditional;
import com.sun.tools.javac.tree.JCTree.JCExpression;
import com.sun.tools.javac.tree.JCTree.JCFieldAccess;
import com.sun.tools.javac.tree.JCTree.JCIdent;
import com.sun.tools.javac.tree.JCTree.JCLambda;
import com.sun.tools.javac.tree.JCTree.JCLiteral;
import com.sun.tools.javac.tree.JCTree.JCMethodDecl;
import com.sun.tools.javac.tree.JCTree.JCMethodInvocation;
import com.sun.tools.javac.tree.JCTree.JCNewClass;
import com.sun.tools.javac.tree.JCTree.JCReturn;
import com.sun.tools.javac.tree.JCTree.JCTypeCast;
import com.sun.tools.javac.tree.JCTree.JCUnary;
import com.sun.tools.javac.tree.JCTree.JCVariableDecl;
import com.sun.tools.javac.tree.JCTree.Tag;
import com.sun.tools.javac.tree.TreeInfo;
import com.sun.tools.javac.tree.TreeMaker;
import com.sun.tools.javac.tree.TreeTranslator;
import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.List;
import com.sun.tools.javac.util.ListBuffer;
import com.sun.tools.javac.util.Name;
import com.sun.tools.javac.util.Names;
import java.io.IOException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.TypeElement;

/** The tree rewrites of {@link TesseraSyntaxLitePlugin}, on attributed trees. */
final class Rewriter extends TreeTranslator {

    enum K { NONE, SIGNED, UNSIGNED, NEUTRAL }

    private final Symtab syms;
    private final Types types;
    private final Names names;
    private final TreeMaker make;
    /** Calls this rewriter made whose result is unsigned (divideUnsigned of unsigned values). */
    private final Set<JCTree> unsignedResults = Collections.newSetFromMap(new IdentityHashMap<>());
    private CharSequence source;
    private Type methodReturn;

    Rewriter(Context context) {
        syms = Symtab.instance(context);
        types = Types.instance(context);
        names = Names.instance(context);
        make = TreeMaker.instance(context);
    }

    void rewrite(JCClassDecl cd, JCCompilationUnit cu) {
        try {
            source = cu.getSourceFile().getCharContent(true);
        } catch (IOException e) {
            source = "";
        }
        methodReturn = null;
        translate(cd);
    }

    // ------------------------------------------------------------ classification (as UnsignedCheck)

    static boolean unsignedAnnotation(java.util.List<? extends AnnotationMirror> ams) {
        for (AnnotationMirror am : ams)
            if (((TypeElement) am.getAnnotationType().asElement()).getSimpleName().contentEquals("Unsigned")) return true;
        return false;
    }

    static boolean annotated(Type t) {
        return t != null && unsignedAnnotation(t.getAnnotationMirrors());
    }

    static boolean unsignedSymbol(Symbol s) {
        if (s == null) return false;
        if (unsignedAnnotation(s.getAnnotationMirrors())) return true;
        if (s instanceof MethodSymbol ms) return annotated(ms.type.getReturnType());
        return annotated(s.type);
    }

    static boolean intOrLong(Type t) {
        return t != null && (t.hasTag(TypeTag.INT) || t.hasTag(TypeTag.LONG));
    }

    static boolean unsignedCast(JCTypeCast c) {
        if (!(c.clazz instanceof JCAnnotatedType at)) return false;
        for (JCAnnotation a : at.annotations) {
            String n = a.annotationType.toString();
            if (n.equals("Unsigned") || n.endsWith(".Unsigned")) return true;
        }
        return false;
    }

    K kind(JCExpression e0) {
        JCExpression e = TreeInfo.skipParens(e0);
        if (!intOrLong(e.type)) return K.NONE;
        if (unsignedResults.contains(e)) return K.UNSIGNED;
        if (annotated(e.type) && !(e instanceof JCTypeCast)) return K.UNSIGNED;
        switch (e) {
            case JCLiteral l -> {
                if (!(l.getValue() instanceof Number n)) return K.SIGNED;
                if (n.longValue() >= 0) return K.NEUTRAL;
                int start = TreeInfo.getStartPos(l);
                return start >= 0 && start < source.length() && source.charAt(start) == '-' ? K.SIGNED : K.NEUTRAL;
            }
            case JCIdent id -> { return variableKind(id.sym); }
            case JCFieldAccess fa -> { return variableKind(fa.sym); }
            case JCMethodInvocation mi -> { return unsignedSymbol(TreeInfo.symbol(mi.meth)) ? K.UNSIGNED : K.SIGNED; }
            case JCTypeCast c -> {
                if (unsignedCast(c)) return K.UNSIGNED;
                return kind(c.expr) == K.NEUTRAL ? K.NEUTRAL : K.SIGNED;
            }
            case JCBinary b -> {
                return switch (b.getTag()) {
                    case SL, SR, USR -> kind(b.lhs);
                    default -> promotes(b.lhs, b.rhs) || promotes(b.rhs, b.lhs) ? K.NONE : combine(kind(b.lhs), kind(b.rhs));
                };
            }
            case JCUnary u -> {
                K k = kind(u.arg);
                if (u.hasTag(Tag.NEG)) return k == K.UNSIGNED ? K.UNSIGNED : K.SIGNED;
                return k;
            }
            case JCConditional c -> {
                return promotes(c.truepart, c.falsepart) || promotes(c.falsepart, c.truepart) ? K.NONE
                        : combine(kind(c.truepart), kind(c.falsepart));
            }
            case JCAssign a -> { return kind(a.lhs); }
            case JCAssignOp a -> { return kind(a.lhs); }
            case JCArrayAccess aa -> {
                if (aa.indexed.type instanceof Type.ArrayType at && annotated(at.elemtype)) return K.UNSIGNED;
                Symbol s = TreeInfo.symbol(aa.indexed);
                if (s != null && s.type instanceof Type.ArrayType at && annotated(at.elemtype)) return K.UNSIGNED;
                return K.SIGNED;
            }
            default -> { return K.SIGNED; }
        }
    }

    /** An unsigned operand {@code a} promoted to the wider type of {@code b} (sign extension; TesseraCheck reports it). */
    boolean promotes(JCExpression a, JCExpression b) {
        if (a.type == null || b.type == null || kind(a) != K.UNSIGNED) return false;
        TypeTag self = a.type.getTag(), other = b.type.getTag();
        if (self == TypeTag.INT) return other == TypeTag.LONG || other == TypeTag.FLOAT || other == TypeTag.DOUBLE;
        return other == TypeTag.FLOAT || other == TypeTag.DOUBLE;
    }

    static K combine(K a, K b) {
        if (a == K.UNSIGNED && b == K.SIGNED || a == K.SIGNED && b == K.UNSIGNED) return K.NONE;
        if (a == K.UNSIGNED || b == K.UNSIGNED) return K.UNSIGNED;
        if (a == K.NEUTRAL && b == K.NEUTRAL) return K.NEUTRAL;
        return K.SIGNED;
    }

    static K variableKind(Symbol s) {
        if (!(s instanceof VarSymbol v)) return K.SIGNED;
        if (unsignedSymbol(v)) return K.UNSIGNED;
        if (v.getConstValue() instanceof Number n) return n.longValue() >= 0 ? K.NEUTRAL : K.SIGNED;
        return K.SIGNED;
    }

    /** Both operands unsigned or constants that fit, at least one unsigned. */
    boolean unsignedPair(JCExpression a, JCExpression b) {
        K l = kind(a), r = kind(b);
        return (l == K.UNSIGNED || r == K.UNSIGNED) && (l == K.UNSIGNED || l == K.NEUTRAL) && (r == K.UNSIGNED || r == K.NEUTRAL);
    }

    // ------------------------------------------------------------ building trees

    ClassSymbol box(Type t) {
        return types.boxedClass(t.hasTag(TypeTag.LONG) ? syms.longType : syms.intType);
    }

    /** {@code Integer.name(args)} or {@code Long.name(args)}, with the overload whose first parameter is {@code param}. */
    JCMethodInvocation call(int pos, Type param, String name, JCExpression... args) {
        ClassSymbol owner = box(param);
        Name n = names.fromString(name);
        MethodSymbol found = null;
        for (Symbol s : owner.members().getSymbolsByName(n)) {
            if (!(s instanceof MethodSymbol ms) || (ms.flags() & Flags.STATIC) == 0) continue;
            List<Type> ps = ms.type.getParameterTypes();
            if (ps.size() == args.length && ps.head.getTag() == param.getTag()) found = ms;
        }
        if (found == null) throw new IllegalStateException("no " + owner + "." + name + "(" + param + ")");
        make.at(pos);
        return make.App(make.QualIdent(found), List.from(args));
    }

    OperatorSymbol operator(String name, int opcode, Type result, Type... params) {
        return new OperatorSymbol(names.fromString(name), new Type.MethodType(List.from(params), result, List.nil(), syms.methodClass),
                opcode, syms.noSymbol);
    }

    static int logical(int opcode) {
        if (opcode == ByteCodes.ishr) return ByteCodes.iushr;
        if (opcode == ByteCodes.lshr) return ByteCodes.lushr;
        if (opcode == ByteCodes.ishrl) return ByteCodes.iushrl;
        if (opcode == ByteCodes.lshrl) return ByteCodes.lushrl;
        throw new IllegalStateException("not an arithmetic shift: " + opcode);
    }

    OperatorSymbol logicalShift(OperatorSymbol sra) {
        List<Type> ps = sra.type.getParameterTypes();
        return operator(">>>", logical(sra.opcode), sra.type.getReturnType(), ps.head, ps.tail.head);
    }

    JCExpression widen(JCExpression e, Type target) {
        if (target != null && target.hasTag(TypeTag.LONG) && e.type != null && e.type.hasTag(TypeTag.INT) && kind(e) == K.UNSIGNED)
            return call(e.pos, syms.intType, "toUnsignedLong", e);
        return e;
    }

    // ------------------------------------------------------------ rewrites

    @Override
    public void visitMethodDef(JCMethodDecl tree) {
        Type saved = methodReturn;
        methodReturn = tree.sym == null ? null : tree.sym.type.getReturnType();
        super.visitMethodDef(tree);
        methodReturn = saved;
    }

    @Override
    public void visitLambda(JCLambda tree) {
        Type saved = methodReturn;
        methodReturn = null;
        super.visitLambda(tree);
        methodReturn = saved;
    }

    @Override
    public void visitBinary(JCBinary tree) {
        tree.lhs = translate(tree.lhs);
        tree.rhs = translate(tree.rhs);
        result = tree;
        Tag tag = tree.getTag();
        if (tag == Tag.PLUS && tree.type != null && types.isSameType(tree.type.baseType(), syms.stringType)) {
            boolean changed = false;
            if (intOrLong(tree.lhs.type) && kind(tree.lhs) == K.UNSIGNED) {
                tree.lhs = call(tree.lhs.pos, tree.lhs.type, "toUnsignedString", tree.lhs);
                changed = true;
            }
            if (intOrLong(tree.rhs.type) && kind(tree.rhs) == K.UNSIGNED) {
                tree.rhs = call(tree.rhs.pos, tree.rhs.type, "toUnsignedString", tree.rhs);
                changed = true;
            }
            if (changed)
                tree.operator = operator("+", ByteCodes.string_add, syms.stringType, operandType(tree.lhs), operandType(tree.rhs));
            return;
        }
        if (tag == Tag.SR && kind(tree.lhs) == K.UNSIGNED) {
            JCBinary n = make.at(tree.pos).Binary(Tag.USR, tree.lhs, tree.rhs);
            n.operator = logicalShift(tree.operator);
            n.type = tree.type;
            result = n;
            return;
        }
        if (tree.operator == null) return;
        Type opType = tree.operator.type.getParameterTypes().head;
        if (!intOrLong(opType) || !unsignedPair(tree.lhs, tree.rhs)) return;
        switch (tag) {
            case DIV, MOD -> {
                JCMethodInvocation c = call(tree.pos, opType, tag == Tag.DIV ? "divideUnsigned" : "remainderUnsigned", tree.lhs, tree.rhs);
                unsignedResults.add(c);
                result = c;
            }
            case LT, LE, GT, GE -> {
                JCMethodInvocation c = call(tree.pos, opType, "compareUnsigned", tree.lhs, tree.rhs);
                JCBinary n = make.at(tree.pos).Binary(tag, c, make.Literal(0));
                int opcode = switch (tag) {
                    case LT -> ByteCodes.if_icmplt;
                    case LE -> ByteCodes.if_icmple;
                    case GT -> ByteCodes.if_icmpgt;
                    default -> ByteCodes.if_icmpge;
                };
                n.operator = operator(tree.operator.name.toString(), opcode, syms.booleanType, syms.intType, syms.intType);
                n.type = syms.booleanType;
                result = n;
            }
            default -> {}
        }
    }

    Type operandType(JCExpression e) {
        Type t = e.type.baseType();
        return t.isPrimitive() ? t : (types.isSameType(t, syms.stringType) ? syms.stringType : syms.objectType);
    }

    @Override
    public void visitAssignop(JCAssignOp tree) {
        tree.lhs = translate(tree.lhs);
        tree.rhs = translate(tree.rhs);
        result = tree;
        if (!(tree.lhs instanceof JCIdent id) || kind(tree.lhs) != K.UNSIGNED || tree.operator == null) return;
        switch (tree.getTag()) {
            case SR_ASG -> {
                JCAssignOp n = make.at(tree.pos).Assignop(Tag.USR_ASG, tree.lhs, tree.rhs);
                n.operator = logicalShift(tree.operator);
                n.type = tree.type;
                result = n;
            }
            case DIV_ASG, MOD_ASG -> {
                Type opType = tree.operator.type.getParameterTypes().head;
                if (!intOrLong(opType) || opType.getTag() != tree.lhs.type.getTag()) return;
                K r = kind(tree.rhs);
                if (r != K.UNSIGNED && r != K.NEUTRAL) return;
                JCMethodInvocation c = call(tree.pos, opType, tree.hasTag(Tag.DIV_ASG) ? "divideUnsigned" : "remainderUnsigned",
                        make.at(tree.pos).Ident(id.sym), tree.rhs);
                JCAssign a = make.at(tree.pos).Assign(tree.lhs, c);
                a.type = tree.lhs.type;
                result = a;
            }
            default -> {}
        }
    }

    @Override
    public void visitVarDef(JCVariableDecl tree) {
        super.visitVarDef(tree);
        if (tree.init == null || tree.sym == null) return;
        tree.init = widen(tree.init, tree.sym.type);
        if (tree.sym.owner.kind == Kind.MTH && !tree.sym.type.isPrimitive()) autoName(tree);
    }

    @Override
    public void visitAssign(JCAssign tree) {
        super.visitAssign(tree);
        tree.rhs = widen(tree.rhs, tree.lhs.type);
    }

    @Override
    public void visitReturn(JCReturn tree) {
        super.visitReturn(tree);
        if (tree.expr != null && methodReturn != null) tree.expr = widen(tree.expr, methodReturn);
    }

    @Override
    public void visitApply(JCMethodInvocation tree) {
        super.visitApply(tree);
        tree.args = widenArgs(TreeInfo.symbol(tree.meth), tree.args);
    }

    @Override
    public void visitNewClass(JCNewClass tree) {
        super.visitNewClass(tree);
        tree.args = widenArgs(tree.constructor, tree.args);
    }

    List<JCExpression> widenArgs(Symbol s, List<JCExpression> args) {
        if (!(s instanceof MethodSymbol ms)) return args;
        List<Type> params = ms.type.getParameterTypes();
        ListBuffer<JCExpression> out = new ListBuffer<>();
        int i = 0, n = params.size();
        for (List<JCExpression> l = args; l.nonEmpty(); l = l.tail, i++) {
            boolean varargsTail = ms.isVarArgs() && i >= n - 1;
            out.append(!varargsTail && params.nonEmpty() ? widen(l.head, params.head) : l.head);
            if (params.nonEmpty() && params.tail.nonEmpty()) params = params.tail;
        }
        return out.toList();
    }

    @Override
    public void visitTypeCast(JCTypeCast tree) {
        super.visitTypeCast(tree);
        if (tree.type != null && tree.type.hasTag(TypeTag.LONG) && tree.expr.type != null && tree.expr.type.hasTag(TypeTag.INT)
                && kind(tree.expr) == K.UNSIGNED) {
            JCMethodInvocation c = call(tree.pos, syms.intType, "toUnsignedLong", tree.expr);
            if (unsignedCast(tree)) unsignedResults.add(c);
            result = c;
        }
    }

    // ------------------------------------------------------------ automatic local names (as the Tessera javac)

    /** {@code T x = init;} becomes {@code T x = (T) T.name(init, "x");} when T has a static @Operator("name") method. */
    void autoName(JCVariableDecl tree) {
        JCExpression init = TreeInfo.skipParens(tree.init);
        if (init.hasTag(Tag.IDENT) || init.hasTag(Tag.LITERAL)) return;
        Type t = tree.sym.type;
        MethodSymbol ms = nameOperator(t);
        if (ms == null) return;
        make.at(tree.init.pos);
        JCExpression name = make.Literal(tree.name.toString()).setType(syms.stringType.constType(tree.name.toString()));
        JCExpression c = make.App(make.QualIdent(ms), List.of(tree.init, name));
        c.type = t;
        tree.init = make.TypeCast(t, c).setType(t);
    }

    MethodSymbol nameOperator(Type site) {
        for (Type t = site; t != null && t.hasTag(TypeTag.CLASS); t = types.supertype(t)) {
            var c = (ClassSymbol) t.tsym;
            for (Symbol s : c.members().getSymbols(s -> s.kind == Kind.MTH)) {
                if ((s.flags() & Flags.STATIC) == 0 || !(s instanceof MethodSymbol ms)) continue;
                for (Attribute.Compound a : s.getDeclarationAttributes()) {
                    if (!a.type.tsym.getSimpleName().contentEquals("Operator")) continue;
                    for (var p : a.values) {
                        if (p.snd.getValue() instanceof String v && v.equals("name")) return ms;
                        if (p.snd instanceof Attribute.Array arr)
                            for (Attribute e : arr.values)
                                if (e.getValue() instanceof String v && v.equals("name")) return ms;
                    }
                }
            }
        }
        return null;
    }
}
