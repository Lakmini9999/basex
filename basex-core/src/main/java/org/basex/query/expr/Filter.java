package org.basex.query.expr;

import static org.basex.query.expr.path.Axis.*;

import org.basex.query.*;
import org.basex.query.expr.CmpV.*;
import org.basex.query.expr.gflwor.*;
import org.basex.query.expr.path.*;
import org.basex.query.func.*;
import org.basex.query.util.*;
import org.basex.query.util.list.*;
import org.basex.query.value.*;
import org.basex.query.value.item.*;
import org.basex.query.value.node.*;
import org.basex.query.value.seq.*;
import org.basex.query.value.type.*;
import org.basex.query.var.*;
import org.basex.util.*;

/**
 * Abstract filter expression.
 *
 * @author BaseX Team 2005-19, BSD License
 * @author Christian Gruen
 */
public abstract class Filter extends Preds {
  /** Expression. */
  public Expr root;

  /**
   * Constructor.
   * @param info input info
   * @param root root expression
   * @param exprs predicates
   */
  protected Filter(final InputInfo info, final Expr root, final Expr... exprs) {
    super(info, SeqType.ITEM_ZM, exprs);
    this.root = root;
  }

  /**
   * Creates a filter or path expression for the given root and predicates.
   * @param info input info
   * @param root root expression
   * @param exprs predicate expressions
   * @return filter expression
   */
  public static Expr get(final InputInfo info, final Expr root, final Expr... exprs) {
    final Expr expr = simplify(root, exprs);
    if(expr != null) return expr;

    // use simple filter for single deterministic predicate
    final Expr pred = exprs[0];
    if(exprs.length == 1 && pred.isSimple()) return new SimpleFilter(info, root, exprs);

    return new CachedFilter(info, root, exprs);
  }

  @Override
  public final void checkUp() throws QueryException {
    checkNoUp(root);
    super.checkUp();
  }

  @Override
  public final Expr compile(final CompileContext cc) throws QueryException {
    root = root.compile(cc);
    cc.pushFocus(root);
    try {
      super.compile(cc);
    } finally {
      cc.removeFocus();
    }
    return optimize(cc);
  }

  @Override
  public final Expr optimize(final CompileContext cc) throws QueryException {
    // return empty root
    if(root.seqType().zero()) return cc.replaceWith(this, root);

    // simplify predicates
    simplify(cc, root);

    // remember current context value (will be temporarily overwritten)
    cc.pushFocus(root);
    try {
      final Expr expr = super.optimize(cc);
      if(expr != this) return expr;
    } finally {
      cc.removeFocus();
    }

    // check result size
    if(!exprType(root.seqType(), root.size())) return cc.emptySeq(this);

    // if possible, convert filter to root or path expression
    Expr exp = simplify(root, exprs);
    if(exp != null) return exp.optimize(cc);

    // no numeric predicates..
    if(!positional()) {
      // rewrite filter with document nodes to path; enables index rewritings
      // example: db:open('db')[.//text() = 'x'] -> db:open('db')/.[.//text() = 'x']
      if(root instanceof Value && root.data() != null && root.seqType().type == NodeType.DOC) {
        final Expr path = Path.get(info, root, Step.get(info, SELF, KindTest.NOD, exprs));
        return cc.replaceWith(this, path.optimize(cc));
      }

      // rewrite independent deterministic single filter to if expression:
      // example: (1 to 10)[$boolean] -> if($boolean) then (1 to 10) else ()
      final Expr expr = exprs[0];
      if(exprs.length == 1 && expr.isSimple() && !expr.seqType().mayBeNumber()) {
        final Expr iff = new If(info, expr, root).optimize(cc);
        return cc.replaceWith(this, iff);
      }

      // otherwise, return iterative filter
      return copyType(new IterFilter(info, root, exprs));
    }

    // evaluate positional predicates: build new root expression
    Expr rt = root;
    boolean opt = false;
    for(final Expr pred : exprs) {
      exp = null;
      if(Function.LAST.is(pred)) {
        if(rt instanceof Value) {
          // value: replace with last item
          exp = ((Value) rt).itemAt(rt.size() - 1);
        } else {
          // rewrite positional predicate to util:last
          exp = cc.function(Function._UTIL_LAST, info, rt);
        }
      } else if(pred instanceof ItrPos) {
        final ItrPos pos = (ItrPos) pred;
        if(rt instanceof Value) {
          // value: replace with subsequence
          final long size = pos.min - 1, len = Math.min(pos.max, rt.size()) - size;
          exp = len <= 0 ? Empty.SEQ : ((Value) rt).subSequence(size, len, cc.qc);
        } else if(pos.min == pos.max) {
          // expr[pos] -> util:item(expr, pos)
          exp = pos.min == 1 ? cc.function(Function.HEAD, info, rt) :
            cc.function(Function._UTIL_ITEM, info, rt, Int.get(pos.min));
        } else {
          // expr[min..max] -> util:range(expr, min, max)
          exp = cc.function(Function._UTIL_RANGE, info, rt, Int.get(pos.min), Int.get(pos.max));
        }
      } else if(pred instanceof Pos) {
        final Pos pos = (Pos) pred;
        if(pos.eq()) {
          // expr[pos] -> util:item(expr, pos.min)
          exp = cc.function(Function._UTIL_ITEM, info, rt, pos.exprs[0]);
        } else {
          // expr[min..max] -> util:range(expr, pos.min, pos.max)
          exp = cc.function(Function._UTIL_RANGE, info, rt, pos.exprs[0], pos.exprs[1]);
        }
      } else if(numeric(pred)) {
        /* - rewrite positional predicate to util:item
         *   expr[pos] -> util:item(expr, pos)
         * - only choose deterministic and context-independent offsets
         *   illegal examples: (1 to 10)[random:integer(10)]  or  (1 to 10)[.] */
        exp = cc.function(Function._UTIL_ITEM, info, rt, pred);
      } else if(pred instanceof Cmp) {
        // rewrite positional predicate to fn:remove
        final Cmp cmp = (Cmp) pred;
        final OpV opV = cmp.opV();
        if(cmp.positional() && opV != null) {
          final Expr ex = cmp.exprs[1];
          if(opV == OpV.LT || opV == OpV.NE && Function.LAST.is(ex)) {
            // expr[position() < last()] -> util:init(expr)
            exp = cc.function(Function._UTIL_INIT, info, rt);
          } else if(opV == OpV.NE && ex instanceof Item) {
            // expr[position() != INT] -> remove(expr, INT)
            final long p = ((Item) ex).itr(info);
            if(p == ((Item) ex).dbl(info)) exp = cc.function(Function.REMOVE, info, rt, ex);
          }
        }
      }

      if(exp != null) {
        // predicate was optimized: replace old with new expression
        rt = exp;
        opt = true;
      } else {
        // no optimization: create new filter expression, or add predicate to temporary filter
        rt = rt != root && rt instanceof Filter ? ((Filter) rt).addPred(pred) : get(info, rt, pred);
      }
    }

    // return optimized expression or standard iterator
    if(opt) return cc.replaceWith(this, rt);

    exp = get(info, root, exprs);
    return exp instanceof ParseExpr ? copyType((ParseExpr) exp) : exp;
  }

  /**
   * Adds a predicate to the filter.
   * This function is e.g. called by {@link For#addPredicate}.
   * @param pred predicate to be added
   * @return new filter
   */
  public final CachedFilter addPred(final Expr pred) {
    exprs = new ExprList(exprs.length + 1).add(exprs).add(pred).finish();
    return copyType(new CachedFilter(info, root, exprs));
  }

  @Override
  public final Expr optimizeEbv(final CompileContext cc) throws QueryException {
    final Expr expr = optimizeEbv(root, cc);
    return expr == this ? super.optimizeEbv(cc) : cc.replaceEbv(this, expr);
  }

  /**
   * Checks if the specified filter input can be rewritten to the root or an axis path.
   * @param root root expression
   * @param exprs predicate expressions
   * @return filter expression or {@code null}
   */
  private static Expr simplify(final Expr root, final Expr... exprs) {
    // no predicates: return root
    if(exprs.length == 0) return root;
    /* axis path: attach non-positional predicates to last step.
     * example: (//x)[text() = 'a'] != //x[text() = 'a']
     * illegal: (//x)[1] != //x[1] */
    if(root instanceof AxisPath && !positional(exprs)) return ((AxisPath) root).addPreds(exprs);
    return null;
  }

  @Override
  public final boolean has(final Flag... flags) {
    if(root.has(flags)) return true;
    final Flag[] flgs = Flag.POS.remove(Flag.CTX.remove(flags));
    return flgs.length != 0 && super.has(flgs);
  }

  @Override
  public final boolean inlineable(final Var var) {
    return root.inlineable(var) && super.inlineable(var);
  }

  @Override
  public final VarUsage count(final Var var) {
    final VarUsage inPreds = super.count(var), inRoot = root.count(var);
    return inPreds == VarUsage.NEVER ? inRoot :
      root.seqType().zeroOrOne() ? inRoot.plus(inPreds) : VarUsage.MORE_THAN_ONCE;
  }

  @Override
  public final Expr inline(final Var var, final Expr ex, final CompileContext cc)
      throws QueryException {

    boolean changed = inlineAll(var, ex, exprs, cc);
    if(root != null) {
      final Expr rt = root.inline(var, ex, cc);
      if(rt != null) {
        root = rt;
        changed = true;
      }
    }
    return changed ? optimize(cc) : null;
  }

  @Override
  public final boolean accept(final ASTVisitor visitor) {
    for(final Expr expr : exprs) {
      visitor.enterFocus();
      if(!expr.accept(visitor)) return false;
      visitor.exitFocus();
    }
    return root.accept(visitor);
  }

  @Override
  public final int exprSize() {
    int size = 1;
    for(final Expr expr : exprs) size += expr.exprSize();
    return size + root.exprSize();
  }

  @Override
  public final boolean equals(final Object obj) {
    return this == obj || obj instanceof Filter && root.equals(((Filter) obj).root) &&
        super.equals(obj);
  }

  @Override
  public final void plan(final FElem plan) {
    addPlan(plan, planElem(), root, exprs);
  }

  @Override
  public final String toString() {
    return "(" + root + ')' + super.toString();
  }
}
