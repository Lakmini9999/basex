package org.basex.query;

import static org.basex.core.Text.*;
import static org.basex.query.QueryTokens.*;
import static org.basex.query.util.Err.*;
import static org.basex.util.Token.*;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import org.basex.core.Context;
import org.basex.core.Progress;
import org.basex.core.Prop;
import org.basex.core.User;
import org.basex.core.Commands.CmdPerm;
import org.basex.data.Data;
import org.basex.data.FTPosData;
import org.basex.data.Nodes;
import org.basex.data.Result;
import org.basex.data.Serializer;
import org.basex.data.SerializerProp;
import org.basex.io.IO;
import org.basex.query.expr.Expr;
import org.basex.query.item.DBNode;
import org.basex.query.item.Dat;
import org.basex.query.item.Dtm;
import org.basex.query.item.Item;
import org.basex.query.item.Seq;
import org.basex.query.item.Tim;
import org.basex.query.item.Uri;
import org.basex.query.item.Value;
import org.basex.query.iter.Iter;
import org.basex.query.iter.NodIter;
import org.basex.query.iter.ItemIter;
import org.basex.query.up.Updates;
import org.basex.query.util.Err;
import org.basex.query.util.Functions;
import org.basex.query.util.Namespaces;
import org.basex.query.util.Variables;
import org.basex.util.IntList;
import org.basex.util.StringList;
import org.basex.util.TokenBuilder;
import org.basex.util.Util;
import org.basex.util.ft.FTLexer;
import org.basex.util.ft.FTOpt;
import org.basex.util.ft.Scoring;

/**
 * This abstract query expression provides the architecture for a compiled
 * query. // *
 * @author Workgroup DBIS, University of Konstanz 2005-10, ISC License
 * @author Christian Gruen
 */
public final class QueryContext extends Progress {
  /** Functions. */
  public final Functions funcs = new Functions();
  /** Variables. */
  public final Variables vars = new Variables();
  /** Scoring instance. */
  public final Scoring score = new Scoring();
  /** Namespaces. */
  public Namespaces ns = new Namespaces();

  /** Query string. */
  public String query;
  /** XQuery version flag. */
  public boolean xquery30;

  /** Cached stop word files. */
  public HashMap<String, String> stop;
  /** Cached thesaurus files. */
  public HashMap<String, String> thes;

  /** Reference to the root expression. */
  public Expr root;
  /** Current context position. */
  public long pos;
  /** Current context size. */
  public long size;

  /** Current full-text options. */
  public FTOpt ftopt;
  /** Current full-text token. */
  public FTLexer fttoken;

  /** Current Date. */
  public Dat date;
  /** Current DateTime. */
  public Dtm dtm;
  /** Current Time. */
  public Tim time;

  /** Default function namespace. */
  public byte[] nsFunc = FNURI;
  /** Default element namespace. */
  public byte[] nsElem = EMPTY;
  /** Static Base URI. */
  public Uri baseURI = Uri.EMPTY;
  /** Default collation. */
  public Uri collation = Uri.uri(URLCOLL);

  /** Default boundary-space. */
  public boolean spaces;
  /** Empty Order mode. */
  public boolean orderGreatest;
  /** Preserve Namespaces. */
  public boolean nsPreserve = true;
  /** Inherit Namespaces. */
  public boolean nsInherit = true;
  /** Ordering mode. */
  public boolean ordered;
  /** Construction mode. */
  public boolean construct;

  /** Full-text position data (needed for highlighting of full-text results). */
  public FTPosData ftpos;
  /** Full-text token counter (needed for highlighting of full-text results). */
  public byte ftoknum;

  /** Copied nodes, resulting from transform expression. */
  public final Set<Data> copiedNods = new HashSet<Data>();
  /** Pending updates. */
  public Updates updates = new Updates(false);
  /** Indicates if this query performs updates. */
  public boolean updating;

  /** Compilation flag: current node has leaves. */
  public boolean leaf;
  /** Compilation flag: FLWOR clause performs grouping. */
  public boolean grouping;
  /** Compilation flag: full-text evaluation can be stopped after first hit. */
  public boolean ftfast = true;

  /** String container for query background information. */
  private final TokenBuilder info = new TokenBuilder();
  /** Info flag. */
  private final boolean inf;
  /** Optimization flag. */
  private boolean firstOpt = true;
  /** Evaluation flag. */
  private boolean firstEval = true;

  /** List of modules. */
  final StringList modules = new StringList();
  /** List of loaded modules. */
  final StringList modLoaded = new StringList();
  /** Serializer options. */
  SerializerProp serProp;
  /** Initial context set (default: null). */
  Nodes nodes;

  /** Query resource. */
  public QueryContextRes resource = new QueryContextRes(
      new DBNode[1], new NodIter[1], new byte[1][]);
  
  /**
   * Constructor.
   * @param ctx context reference
   */
  public QueryContext(final Context ctx) {
    resource.context = ctx;
    nodes = ctx.current;
    ftopt = new FTOpt();
    xquery30 = ctx.prop.is(Prop.XQUERY11);
    inf = ctx.prop.is(Prop.QUERYINFO);
    if(ctx.query != null) baseURI = Uri.uri(token(ctx.query.url()));
  }

  /**
   * Parses the specified query.
   * @param q input query
   * @throws QueryException query exception
   */
  public void parse(final String q) throws QueryException {
    root = new QueryParser(q, this).parse(file(), null);
    query = q;
  }

  /**
   * Parses the specified module.
   * @param q input query
   * @throws QueryException query exception
   */
  public void module(final String q) throws QueryException {
    new QueryParser(q, this).parse(file(), Uri.EMPTY);
  }

  /**
   * Optimizes the expression.
   * @throws QueryException query exception
   */
  public void compile() throws QueryException {
    // add full-text container reference
    if(nodes != null && nodes.ftpos != null) ftpos = new FTPosData();

    try {
      // cache the initial context nodes
      if(nodes != null) {
        final Data data = nodes.data;
        if(!resource.context.perm(User.READ, data.meta))
          Err.PERMNO.thrw(null, CmdPerm.READ);

        final int s = data.empty() ? 0 : (int) nodes.size();
        if(nodes.root) {
          // create document nodes
          resource.doc = new DBNode[s];
          for(int n = 0; n < s; ++n) {
            resource.addDoc(new DBNode(data, nodes.list[n], Data.DOC));
          }
        } else {
          final IntList il = data.doc();
          for(int p = 0; p < il.size(); p++)
            resource.addDoc(new DBNode(data, il.get(p), Data.DOC));
        }
        resource.rootDocs = resource.docs;

        // create initial context items
        if(nodes.root) {
          resource.value = Seq.get(resource.doc, resource.docs);
        } else {
          // otherwise, add all context items
          final ItemIter ir = new ItemIter(s);
          for(int n = 0; n < s; ++n) ir.add(new DBNode(data, nodes.list[n]));
          resource.value = ir.finish();
        }
        // add default collection
        resource.addCollection(new NodIter(resource.doc, resource.docs), 
            token(data.meta.name));
      }

      // dump compilation info
      if(inf) compInfo(NL + QUERYCOMP);

      // cache initial context
      final boolean empty = resource.value == null;
      if(empty) resource.value = Item.DUMMY;

      // compile global functions.
      // variables will be compiled if called for the first time
      funcs.comp(this);
      // compile the expression
      root = root.comp(this);
      // reset initial context
      if(empty) resource.value = null;

      // dump resulting query
      if(inf) info.add(NL + QUERYRESULT + funcs + root + NL);

    } catch(final StackOverflowError ex) {
      Util.debug(ex);
      XPSTACK.thrw(null);
    }
  }

  /**
   * Evaluates the expression with the specified context set.
   * @return resulting value
   * @throws QueryException query exception
   */
  protected Result eval() throws QueryException {
    // evaluates the query
    final Iter it = iter();
    final ItemIter ir = new ItemIter();
    Item i;

    // check if all results belong to the database of the input context
    if(nodes != null) {
      final Data data = nodes.data;
      final IntList pre = new IntList();

      while((i = it.next()) != null) {
        checkStop();
        if(!(i instanceof DBNode)) break;
        final DBNode n = (DBNode) i;
        if(n.data != data) break;
        pre.add(((DBNode) i).pre);
      }

      // completed... return standard nodeset with full-text positions
      final int ps = pre.size();
      if(i == null)
        return ps == 0 ? ir : new Nodes(pre.toArray(), data, ftpos).checkRoot();

      // otherwise, add nodes to standard iterator
      for(int p = 0; p < ps; ++p) ir.add(new DBNode(data, pre.get(p)));
      ir.add(i);
    }

    // use standard iterator
    while((i = it.next()) != null) {
      checkStop();
      ir.add(i);
    }
    return ir;
  }

  /**
   * Returns a result iterator.
   * @return result iterator
   * @throws QueryException query exception
   */
  public Iter iter() throws QueryException {
    try {
      final Iter iter = iter(root);
      if(!updating) return iter;

      final Value v = iter.finish();
      updates.apply(this);
      if(resource.context.data != null) resource.context.update();
      return v.iter(this);
    } catch(final StackOverflowError ex) {
      Util.debug(ex);
      XPSTACK.thrw(null);
      return null;
    }
  }

  /**
   * Recursively serializes the query plan.
   * @param ser serializer
   * @throws Exception exception
   */
  protected void plan(final Serializer ser) throws Exception {
    // only show root node if functions or variables exist
    final boolean r = funcs.size() != 0 || vars.global().size != 0;
    if(r) ser.openElement(PLAN);
    funcs.plan(ser);
    root.plan(ser);
    if(r) ser.closeElement();
  }

  /**
   * Evaluates the specified expression and returns an iterator.
   * @param e expression to be evaluated
   * @return iterator
   * @throws QueryException query exception
   */
  public Iter iter(final Expr e) throws QueryException {
    checkStop();
    return e.iter(this);
  }



  /**
   * Copies properties of the specified context.
   * @param ctx context
   */
  public void copy(final QueryContext ctx) {
    baseURI = ctx.baseURI;
    spaces = ctx.spaces;
    construct = ctx.construct;
    nsInherit = ctx.nsInherit;
    nsPreserve = ctx.nsPreserve;
    collation = ctx.collation;
    nsElem = ctx.nsElem;
    nsFunc = ctx.nsFunc;
    orderGreatest = ctx.orderGreatest;
    ordered = ctx.ordered;
  }

  /**
   * Adds some optimization info.
   * @param string evaluation info
   * @param ext text text extensions
   */
  public void compInfo(final String string, final Object... ext) {
    if(!inf) return;
    if(!firstOpt) info.add(QUERYSEP);
    firstOpt = false;
    info.addExt(string, ext);
    info.add(NL);
  }

  /**
   * Adds some evaluation info.
   * @param string evaluation info
   * @param msg message
   */
  public void evalInfo(final byte[] string, final String msg) {
    if(!inf) return;
    if(firstEval) info.add(NL + QUERYEVAL + NL);
    info.add(QUERYSEP);
    info.add(string);
    info.add(' ');
    info.add(msg);
    info.add(NL);
    firstEval = false;
  }

  /**
   * Returns an IO representation of the base uri.
   * @return IO reference
   */
  IO file() {
    return baseURI != Uri.EMPTY ? IO.get(string(baseURI.atom())) : null;
  }

  /**
   * Returns info on query compilation and evaluation.
   * @return query info
   */
  String info() {
    return info.toString();
  }

  /**
   * Returns the serialization properties.
   * @return serialization properties
   */
  public SerializerProp serProp() {
    return serProp;
  }

  @Override
  public String tit() {
    return QUERYEVAL;
  }

  @Override
  public String det() {
    return QUERYEVAL;
  }

  @Override
  public double prog() {
    return 0;
  }

  @Override
  public String toString() {
    return Util.name(this) + '[' + file() + ']';
  }
}
