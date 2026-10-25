package org.eninehq.tessera.core;

import org.eninehq.tessera.ir.Expr;
import org.eninehq.tessera.ir.Ops;

/** The continuation of a {@code when(...)} block: {@code elseWhen(...)} and {@code otherwise(...)}. */
public final class WhenChain {
    private final ModuleBuilder b;
    private final ModuleBuilder.Chain chain;
    private final Expr parentFull;
    private final int depth;
    private Expr taken;

    private WhenChain(ModuleBuilder b, ModuleBuilder.Chain chain, Expr parentFull, int depth) {
        this.b = b;
        this.chain = chain;
        this.parentFull = parentFull;
        this.depth = depth;
    }

    static WhenChain start(Bool cond, Runnable body) {
        var b = Elab.cur();
        var chain = new ModuleBuilder.Chain();
        b.openChain(chain);
        var wc = new WhenChain(b, chain, b.fullCondition(), b.scopes.size());
        Expr c = Elab.read(cond);
        chain.conds.add(c);
        wc.run(0, Ops.and(wc.parentFull, c), body);
        wc.taken = c;
        return wc;
    }

    private void check() {
        if (Elab.cur() != b || b.scopes.size() != depth || chain.closed)
            throw TesseraException.here("elseWhen/otherwise must directly follow its when(...) block");
    }

    private void run(int branch, Expr full, Runnable body) {
        b.scopes.push(new ModuleBuilder.Scope(chain, branch, full));
        try {
            body.run();
        } finally {
            b.scopes.pop();
        }
    }

    public WhenChain elseWhen(Bool cond, Runnable body) {
        check();
        Expr c = Elab.read(cond);
        int idx = chain.conds.size();
        chain.conds.add(c);
        run(idx, Ops.and(parentFull, Ops.and(Ops.not(taken), c)), body);
        taken = Ops.or(taken, c);
        return this;
    }

    public void otherwise(Runnable body) {
        check();
        chain.hasOtherwise = true;
        run(chain.conds.size(), Ops.and(parentFull, Ops.not(taken)), body);
        chain.closed = true;
    }
}
