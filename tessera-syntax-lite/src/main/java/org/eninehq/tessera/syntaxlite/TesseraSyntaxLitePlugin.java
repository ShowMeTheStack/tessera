package org.eninehq.tessera.syntaxlite;

import com.sun.source.util.JavacTask;
import com.sun.source.util.Plugin;
import com.sun.source.util.TaskEvent;
import com.sun.source.util.TaskListener;
import com.sun.tools.javac.api.BasicJavacTask;
import com.sun.tools.javac.api.JavacTrees;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.util.Context;
import org.eninehq.tessera.checker.UnsignedLowering;

/**
 * {@code javac -Xplugin:TesseraSyntaxLite}: two of the Tessera javac's features for stock javac,
 * through javac's internal tree API (it needs
 * {@code -J--add-exports=jdk.compiler/com.sun.tools.javac.<pkg>=ALL-UNNAMED} for
 * {@code api, code, jvm, tree, util}).
 * <ul>
 *   <li><b>Automatic local names</b>: {@code UInt sum = a.add(b);} becomes
 *       {@code UInt sum = (UInt) Data.nameLocal(a.add(b), "sum");}, for every local variable whose type
 *       has a static method annotated {@code @Operator("name")}, as the Tessera javac does.</li>
 *   <li><b>Unsigned lowering</b> of {@code @Unsigned int/long} values: {@code / %} become
 *       {@code divideUnsigned/remainderUnsigned}, {@code < <= > >=} compare with
 *       {@code compareUnsigned}, {@code >>} becomes {@code >>>}, string concatenation uses
 *       {@code toUnsignedString} and widening to {@code long} uses {@code Integer.toUnsignedLong}.
 *       Compound assignments ({@code /= %= >>=}) are lowered on plain variables.</li>
 * </ul>
 * Mixing checks stay in {@code -Xplugin:TesseraCheck}, which should run too; this plugin tells it
 * (via {@link UnsignedLowering}) that the operators above are lowered. Both plugins act when javac
 * has finished analysing a class (after attribution and flow analysis, before lowering to bytecode).
 */
public final class TesseraSyntaxLitePlugin implements Plugin {

    @Override public String getName() { return "TesseraSyntaxLite"; }

    @Override
    public void init(JavacTask task, String... args) {
        UnsignedLowering.register(task);
        Context context = ((BasicJavacTask) task).getContext();
        JavacTrees trees = JavacTrees.instance(context);
        Rewriter rewriter = new Rewriter(context);
        task.addTaskListener(new TaskListener() {
            @Override public void finished(TaskEvent e) {
                if (e.getKind() != TaskEvent.Kind.ANALYZE || e.getTypeElement() == null) return;
                if (trees.getTree(e.getTypeElement()) instanceof JCTree.JCClassDecl cd)
                    rewriter.rewrite(cd, (JCTree.JCCompilationUnit) e.getCompilationUnit());
            }
        });
    }
}
