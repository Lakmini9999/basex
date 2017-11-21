package org.basex.query.func.proc;

import static org.basex.util.Token.*;

import org.basex.query.*;
import org.basex.query.value.item.*;
import org.basex.util.*;

/**
 * Function implementation.
 *
 * @author BaseX Team 2005-17, BSD License
 * @author Christian Gruen
 */
public final class ProcSystem extends ProcFn {
  @Override
  public Item item(final QueryContext qc, final InputInfo ii) throws QueryException {
    final Result result = exec(qc, false);
    if(result.code == 0) return Str.get(result.output.normalize().finish());

    // create error message
    final QNm name = new QNm(CODE + String.format("%04d", result.code), QueryText.PROC_URI);
    result.error.normalize();
    throw new QueryException(info, name, string(result.error.normalize().finish()));
  }
}
