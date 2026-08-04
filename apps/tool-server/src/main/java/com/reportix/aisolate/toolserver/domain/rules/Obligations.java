package com.reportix.aisolate.toolserver.domain.rules;

import java.util.List;
import java.util.Set;

/**
 * What an allow decision carries: the rows the user may see, the columns to drop or mask, and the
 * aggregation threshold.
 *
 * @param allRows a matched rule grants every row; {@code rows} is then not applied
 * @param rows any one predicate matching is enough
 * @param minGroupSize null when no matched rule sets one
 */
public record Obligations(
    boolean allRows,
    List<RowPredicate> rows,
    Set<String> hidden,
    Set<String> masked,
    Integer minGroupSize,
    List<String> matchedRules) {

  public Obligations {
    rows = List.copyOf(rows);
    hidden = Set.copyOf(hidden);
    masked = Set.copyOf(masked);
    matchedRules = List.copyOf(matchedRules);
  }
}
