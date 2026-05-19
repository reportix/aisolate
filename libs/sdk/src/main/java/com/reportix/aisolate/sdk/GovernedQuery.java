package com.reportix.aisolate.sdk;

import java.util.List;

/**
 * What a tool may do with the customer's data, bound to the asking user and the airlock of this
 * call. The airlock's rules decide which rows and columns come back; a filter can only narrow that.
 * A denied user gets an empty result, never an error the model could see.
 */
public interface GovernedQuery {

  /** The rows of the entity the asking user may see, filtered further; {@code limit} is capped. */
  Result read(String entity, List<Filter> filters, int limit);

  /**
   * One aggregate over a column ({@code count} over any, the others over a numeric one), optionally
   * grouped by another. Groups under the rules' minimum size are not returned.
   */
  Result aggregate(String entity, Fn function, String column, String groupBy, List<Filter> filters);

  enum Fn {
    SUM,
    AVG,
    COUNT,
    MIN,
    MAX
  }
}
