package org.basex.query.expr;

import static org.basex.query.QueryText.*;

import org.basex.query.*;
import org.basex.query.expr.CmpV.OpV;
import org.basex.query.util.*;
import org.basex.query.value.item.*;
import org.basex.query.value.node.*;
import org.basex.query.value.seq.*;
import org.basex.query.value.type.*;
import org.basex.query.var.*;
import org.basex.util.*;
import org.basex.util.hash.*;

/**
 * Simple position check expression.
 *
 * @author BaseX Team 2005-17, BSD License
 * @author Christian Gruen
 */
public final class ItrPos extends Simple {
  /** Minimum position. */
  final long min;
  /** Maximum position. */
  final long max;

  /**
   * Constructor.
   * @param min minimum value (1 or larger)
   * @param max minimum value (1 or larger)
   * @param info input info
   */
  private ItrPos(final long min, final long max, final InputInfo info) {
    super(info);
    this.min = min;
    this.max = max;
    seqType = SeqType.BLN;
  }

  /**
   * Returns a position expression for the specified index, or an optimized boolean item.
   * @param index index position
   * @param info input info
   * @return expression
   */
  public static Expr get(final long index, final InputInfo info) {
    return get(index, index, info);
  }

  /**
   * Returns a position expression for the specified range, or an optimized boolean item.
   * @param min minimum value
   * @param max minimum value
   * @param info input info
   * @return expression
   */
  private static Expr get(final long min, final long max, final InputInfo info) {
    // suppose that positions always fit in long values..
    return min > max || max < 1 ? Bln.FALSE : min <= 1 && max == Long.MAX_VALUE ? Bln.TRUE :
      new ItrPos(Math.max(1, min), Math.max(1, max), info);
  }

  /**
   * Returns a position expression for the specified range comparison.
   * @param expr range comparison
   * @return expression
   */
  public static Expr get(final CmpR expr) {
    final double min = expr.min, max = expr.max;
    final long mn = (long) (expr.mni ? (long) Math.ceil(min) : Math.floor(min + 1));
    final long mx = (long) (expr.mxi ? (long) Math.floor(max) : Math.ceil(max - 1));
    return get(mn, mx, expr.info);
  }

  /**
   * Returns an instance of this class, the original expression, or an optimized expression.
   * @param cmp comparator
   * @param arg argument
   * @param orig original expression
   * @param ii input info
   * @return resulting or original expression
   */
  public static Expr get(final OpV cmp, final Expr arg, final Expr orig, final InputInfo ii) {
    if(arg instanceof RangeSeq && cmp == OpV.EQ) {
      final RangeSeq rs = (RangeSeq) arg;
      return get(rs.start(), rs.end(), ii);
    } else if(arg instanceof ANum) {
      final ANum it = (ANum) arg;
      final long p = it.itr();
      final boolean exact = p == it.dbl();
      switch(cmp) {
        case EQ: return exact ? get(p, ii) : Bln.FALSE;
        case GE: return get(exact ? p : p + 1, Long.MAX_VALUE, ii);
        case GT: return get(p + 1, Long.MAX_VALUE, ii);
        case LE: return get(1, p, ii);
        case LT: return get(1, exact ? p - 1 : p, ii);
        default:
      }
    }
    return orig;
  }

  @Override
  public Bln item(final QueryContext qc, final InputInfo ii) throws QueryException {
    ctxValue(qc);
    return Bln.get(matches(qc.focus.pos));
  }

  @Override
  public ItrPos copy(final CompileContext cc, final IntObjMap<Var> vm) {
    return new ItrPos(min, max, info);
  }

  /**
   * Returns false if no more results can be expected.
   * @param pos current position
   * @return result of check
   */
  public boolean skip(final long pos) {
    return pos >= max;
  }

  /**
   * Checks if the current position lies within the given position.
   * @param pos current position
   * @return result of check
   */
  public boolean matches(final long pos) {
    return pos >= min && pos <= max;
  }

  /**
   * Creates an intersection of the existing and the specified position expressions.
   * @param pos second position expression
   * @param ii input info
   * @return resulting expression
   */
  Expr intersect(final ItrPos pos, final InputInfo ii) {
    return get(Math.max(min, pos.min), Math.min(max, pos.max), ii);
  }

  @Override
  public boolean has(final Flag... flags) {
    return Flag.POS.in(flags);
  }

  @Override
  public boolean equals(final Object obj) {
    if(this == obj) return true;
    if(!(obj instanceof ItrPos)) return false;
    final ItrPos p = (ItrPos) obj;
    return min == p.min && max == p.max;
  }

  @Override
  public void plan(final FElem plan) {
    addPlan(plan, planElem(MIN, min, MAX, max == Long.MAX_VALUE ? INF : max));
  }

  @Override
  public String toString() {
    final StringBuilder sb = new StringBuilder("position() ");
    if(min == max) {
      sb.append("= ").append(min);
    } else {
      if(max == Long.MAX_VALUE) sb.append('>');
      sb.append("= ").append(min);
      if(max != Long.MAX_VALUE) sb.append(" to ").append(max);
    }
    return sb.toString();
  }
}
