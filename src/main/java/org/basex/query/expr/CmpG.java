package org.basex.query.expr;

import static org.basex.query.util.Err.*;
import static org.basex.query.QueryTokens.*;
import static org.basex.query.QueryText.*;
import java.io.IOException;
import org.basex.data.Serializer;
import org.basex.index.IndexToken.IndexType;
import org.basex.index.ValuesToken;
import org.basex.query.IndexContext;
import org.basex.query.QueryContext;
import org.basex.query.QueryException;
import org.basex.query.func.FunDef;
import org.basex.query.item.Bln;
import org.basex.query.item.Item;
import org.basex.query.item.NodeType;
import org.basex.query.item.SeqType;
import org.basex.query.iter.Iter;
import org.basex.query.iter.ItemCache;
import org.basex.query.path.Axis;
import org.basex.query.path.AxisPath;
import org.basex.query.path.AxisStep;
import org.basex.util.Array;
import org.basex.util.InputInfo;
import org.basex.util.Token;

/**
 * General comparison.
 *
 * @author BaseX Team 2005-11, BSD License
 * @author Christian Gruen
 */
public final class CmpG extends Cmp {
  /** Comparators. */
  public enum Op {
    /** General comparison: less or equal. */
    LE("<=", CmpV.Op.LE) {
      @Override
      public Op invert() { return Op.GE; }
    },

    /** General comparison: less. */
    LT("<", CmpV.Op.LT) {
      @Override
      public Op invert() { return Op.GT; }
    },

    /** General comparison: greater of equal. */
    GE(">=", CmpV.Op.GE) {
      @Override
      public Op invert() { return Op.LE; }
    },

    /** General comparison: greater. */
    GT(">", CmpV.Op.GT) {
      @Override
      public Op invert() { return Op.LT; }
    },

    /** General comparison: equal. */
    EQ("=", CmpV.Op.EQ) {
      @Override
      public Op invert() { return Op.EQ; }
    },

    /** General comparison: not equal. */
    NE("!=", CmpV.Op.NE) {
      @Override
      public Op invert() { return Op.NE; }
    };

    /** String representation. */
    public final String name;
    /** Comparator. */
    final CmpV.Op op;

    /**
     * Constructor.
     * @param n string representation
     * @param c comparator
     */
    private Op(final String n, final CmpV.Op c) {
      name = n;
      op = c;
    }

    /**
     * Inverts the comparator.
     * @return inverted comparator
     */
    public abstract Op invert();

    @Override
    public String toString() { return name; }
  }

  /** Comparator. */
  Op op;
  /** Index expression. */
  private IndexAccess[] iacc = {};
  /** Flag for atomic evaluation. */
  private boolean atomic;

  /**
   * Constructor.
   * @param ii input info
   * @param e1 first expression
   * @param e2 second expression
   * @param o operator
   */
  public CmpG(final InputInfo ii, final Expr e1, final Expr e2, final Op o) {
    super(ii, e1, e2);
    op = o;
  }

  @Override
  public Expr comp(final QueryContext ctx) throws QueryException {
    super.comp(ctx);

    // swap expressions; add text() to location paths to simplify optimizations
    if(swap()) {
      op = op.invert();
      ctx.compInfo(OPTSWAP, this);
    }
    for(int e = 0; e != expr.length; ++e) expr[e] = expr[e].addText(ctx);

    final Expr e1 = expr[0];
    final Expr e2 = expr[1];
    Expr e = this;
    if(oneEmpty()) {
      e = optPre(Bln.FALSE, ctx);
    } else if(values()) {
      e = preEval(ctx);
    } else if(e1.isFun(FunDef.COUNT)) {
      e = count(op.op);
      if(e != this) ctx.compInfo(e instanceof Bln ? OPTPRE : OPTWRITE, this);
    } else if(e1.isFun(FunDef.POS)) {
      if(e2 instanceof Range && op.op == CmpV.Op.EQ) {
        // position() CMP range
        final long[] rng = ((Range) e2).range(ctx);
        e = rng == null ? this : Pos.get(rng[0], rng[1], input);
      } else {
        // position() CMP number
        e = Pos.get(op.op, e2, e, input);
      }
      if(e != this) ctx.compInfo(OPTWRITE, this);
    } else {
      // rewrite path CMP number
      e = CmpR.get(this);
      if(e != this) ctx.compInfo(OPTWRITE, this);
    }

    // check if both arguments will always yield one result
    atomic = e1.type().zeroOrOne() && e2.type().zeroOrOne();
    type = SeqType.BLN;
    return e;
  }

  @Override
  public Bln item(final QueryContext ctx, final InputInfo ii)
      throws QueryException {

    // atomic evaluation of arguments (faster)
    if(atomic) {
      final Item it1 = expr[0].item(ctx, input);
      if(it1 == null) return Bln.FALSE;
      final Item it2 = expr[1].item(ctx, input);
      if(it2 == null) return Bln.FALSE;
      return Bln.get(eval(it1, it2));
    }

    final Iter ir1 = ctx.iter(expr[0]);
    final long is1 = ir1.size();

    // skip empty result
    if(is1 == 0) return Bln.FALSE;
    final boolean s1 = is1 == 1;

    // evaluate single items
    if(s1 && expr[1].size() == 1)
      return Bln.get(eval(ir1.next(), expr[1].item(ctx, input)));

    Iter ir2 = ctx.iter(expr[1]);
    final long is2 = ir2.size();

    // skip empty result
    if(is2 == 0) return Bln.FALSE;
    final boolean s2 = is2 == 1;

    // evaluate single items
    if(s1 && s2) return Bln.get(eval(ir1.next(), ir2.next()));

    // evaluate iterator and single item
    Item it1, it2;
    if(s2) {
      it2 = ir2.next();
      while((it1 = ir1.next()) != null) if(eval(it1, it2)) return Bln.TRUE;
      return Bln.FALSE;
    }

    // evaluate two iterators
    if(!ir2.reset()) {
      // cache items for next comparisons
      final ItemCache ic = new ItemCache();
      if((it1 = ir1.next()) != null) {
        while((it2 = ir2.next()) != null) {
          if(eval(it1, it2)) return Bln.TRUE;
          ic.add(it2);
        }
      }
      ir2 = ic;
    }

    while((it1 = ir1.next()) != null) {
      ir2.reset();
      while((it2 = ir2.next()) != null) if(eval(it1, it2)) return Bln.TRUE;
    }
    return Bln.FALSE;
  }

  /**
   * Compares a single item.
   * @param a first item to be compared
   * @param b second item to be compared
   * @return result of check
   * @throws QueryException query exception
   */
  private boolean eval(final Item a, final Item b) throws QueryException {
    if(a.type != b.type && !a.unt() && !b.unt() && !(a.str() && b.str()) &&
        !(a.num() && b.num()) && !a.func() && !b.func())
      XPTYPECMP.thrw(input, a.type, b.type);
    return op.op.e(input, a, b);
  }

  /**
   * Creates a union of the existing and the specified expressions.
   * @param g general comparison
   * @param ctx query context
   * @return true if union was successful
   * @throws QueryException query exception
   */
  boolean union(final CmpG g, final QueryContext ctx) throws QueryException {
    if(op != g.op || !expr[0].sameAs(g.expr[0])) return false;
    expr[1] = new List(input, expr[1], g.expr[1]).comp(ctx);
    atomic = atomic && expr[1].type().zeroOrOne();
    return true;
  }

  @Override
  public boolean indexAccessible(final IndexContext ic) throws QueryException {
    // accept only location path, string and equality expressions
    final AxisStep s = indexStep(expr[0]);
    if(s == null || op != Op.EQ) return false;

    // check which index applies
    final boolean text = s.test.type == NodeType.TXT && ic.data.meta.textindex;
    final boolean attr = s.test.type == NodeType.ATT && ic.data.meta.attrindex;
    if(!text && !attr) return false;

    // support expressions
    final IndexType ind = text ? IndexType.TEXT : IndexType.ATTRIBUTE;
    final Expr arg = expr[1];
    if(!arg.value()) {
      final SeqType t = arg.type();
      // index access not possible if returned type is no string or node,
      // and if expression depends on context
      if(arg.uses(Use.CTX) || !(t.type.str() || t.type.node())) return false;

      ic.costs += Math.max(1, ic.data.meta.size / 10);
      iacc = Array.add(iacc, new IndexAccess(input, arg, ind, ic));
      return true;
    }

    // loop through all items
    final Iter ir = arg.iter(ic.ctx);
    Item it;
    ic.costs = 0;
    while((it = ir.next()) != null) {
      final SeqType t = it.type();
      if(!(t.type.str() || t.type.node())) return false;

      final int is = ic.data.nrIDs(new ValuesToken(ind, it.atom(input)));
      // add only expressions that yield results
      if(is != 0) iacc = Array.add(iacc, new IndexAccess(input, it, ind, ic));
      ic.costs += is;
    }
    return true;
  }

  @Override
  public Expr indexEquivalent(final IndexContext ic) {
    // more than one string - merge index results
    final Expr root = iacc.length == 1 ? iacc[0] : new Union(input, iacc);

    final AxisPath orig = (AxisPath) expr[0];
    final AxisPath path = orig.invertPath(root, ic.step);

    if(iacc[0].ind == IndexType.TEXT) {
      ic.ctx.compInfo(OPTTXTINDEX);
    } else {
      ic.ctx.compInfo(OPTATVINDEX);
      // add attribute step
      final AxisStep step = orig.step[orig.step.length - 1];
      if(step.test.name != null) {
        AxisStep[] steps = { AxisStep.get(input, Axis.SELF, step.test) };
        for(final AxisStep s : path.step) steps = Array.add(steps, s);
        path.step = steps;
      }
    }
    return path;
  }

  /**
   * If possible, returns the last location step of the specified expression.
   * @param expr expression
   * @return location step
   */
  public static AxisStep indexStep(final Expr expr) {
    // check if index can be applied
    if(!(expr instanceof AxisPath)) return null;
    // accept only single axis steps as first expression
    final AxisPath path = (AxisPath) expr;
    // path must contain no root node
    return path.root != null ? null : path.step[path.step.length - 1];
  }

  @Override
  public void plan(final Serializer ser) throws IOException {
    ser.openElement(this, OP, Token.token(op.name));
    for(final Expr e : expr) e.plan(ser);
    ser.closeElement();
  }

  @Override
  public String desc() {
    return "'" + op + "' expression";
  }

  @Override
  public String toString() {
    return toString(" " + op + " ");
  }
}
