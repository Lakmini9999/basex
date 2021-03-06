package org.basex.query.expr;

import static org.basex.query.QueryText.*;

import org.basex.query.*;
import org.basex.query.expr.CmpV.*;
import org.basex.query.value.item.*;
import org.basex.query.value.node.*;
import org.basex.query.value.type.*;
import org.basex.query.var.*;
import org.basex.util.*;
import org.basex.util.hash.*;

/**
 * Node comparison.
 *
 * @author BaseX Team 2005-19, BSD License
 * @author Christian Gruen
 */
public final class CmpN extends Cmp {
  /** Comparators. */
  public enum OpN {
    /** Node comparison: same. */
    EQ("is") {
      @Override
      public boolean eval(final ANode item1, final ANode item2) {
        return item1.is(item2);
      }
    },

    /** Node comparison: before. */
    ET("<<") {
      @Override
      public boolean eval(final ANode item1, final ANode item2) {
        return item1.diff(item2) < 0;
      }
    },

    /** Node comparison: after. */
    GT(">>") {
      @Override
      public boolean eval(final ANode item1, final ANode item2) {
        return item1.diff(item2) > 0;
      }
    };

    /** Cached enums (faster). */
    public static final OpN[] VALUES = values();
    /** String representation. */
    public final String name;

    /**
     * Constructor.
     * @param name string representation
     */
    OpN(final String name) {
      this.name = name;
    }

    /**
     * Evaluates the expression.
     * @param item1 first node
     * @param item2 second node
     * @return result
     */
    public abstract boolean eval(ANode item1, ANode item2);

    @Override
    public String toString() {
      return name;
    }
  }

  /** Comparator. */
  final OpN op;

  /**
   * Constructor.
   * @param expr1 first expression
   * @param expr2 second expression
   * @param op comparator
   * @param info input info
   */
  public CmpN(final Expr expr1, final Expr expr2, final OpN op, final InputInfo info) {
    super(info, expr1, expr2, null, SeqType.BLN_ZO, null);
    this.op = op;
  }

  @Override
  public Expr optimize(final CompileContext cc) throws QueryException {
    final Expr expr1 = exprs[0], expr2 = exprs[1];
    final SeqType st1 = expr1.seqType(), st2 = expr2.seqType();
    if(st1.one() && st2.one()) exprType.assign(Occ.ONE);

    Expr expr = emptyExpr();
    if(expr == this) {
      if(allAreValues(false)) expr = value(cc.qc);
    }
    return cc.replaceWith(this, expr);
  }

  @Override
  public Bln item(final QueryContext qc, final InputInfo ii) throws QueryException {
    final ANode n1 = toEmptyNode(exprs[0], qc);
    if(n1 == null) return null;
    final ANode n2 = toEmptyNode(exprs[1], qc);
    if(n2 == null) return null;
    return Bln.get(op.eval(n1, n2));
  }

  @Override
  public CmpN invert(final CompileContext cc) {
    throw Util.notExpected();
  }

  @Override
  public OpV opV() {
    return null;
  }

  @Override
  public Expr copy(final CompileContext cc, final IntObjMap<Var> vm) {
    return copyType(new CmpN(exprs[0].copy(cc, vm), exprs[1].copy(cc, vm), op, info));
  }

  @Override
  public boolean equals(final Object obj) {
    return this == obj || obj instanceof CmpN && op == ((CmpN) obj).op && super.equals(obj);
  }

  @Override
  public void plan(final FElem plan) {
    addPlan(plan, planElem(OP, op.name), exprs);
  }

  @Override
  public String description() {
    return "'" + op + "' comparison";
  }

  @Override
  public String toString() {
    return toString(" " + op + ' ');
  }
}
