package com.reportix.aisolate.toolserver.domain.rules;

import com.reportix.aisolate.config.rules.RowFilter;
import com.reportix.aisolate.config.rules.Rule;
import com.reportix.aisolate.config.rules.RuleSet;
import com.reportix.aisolate.identity.Principal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Default deny; explicit deny wins; several allows combine as the union of rows with the most
 * restrictive column and aggregation obligations. A missing principal attribute denies (fail
 * closed). Empty result means "no rows", never an error the model could see.
 */
@Component
public class RulesEvaluator {

  public Optional<Obligations> evaluate(Principal principal, RuleSet ruleSet, Rule.Action action) {
    List<Rule> matched =
        ruleSet.rules().stream()
            .filter(rule -> principal.roles().stream().anyMatch(r -> rule.appliesTo(r, action)))
            .toList();
    if (matched.isEmpty() || matched.stream().anyMatch(r -> r.effect() == Rule.Effect.DENY)) {
      return Optional.empty();
    }

    boolean allRows = false;
    List<RowPredicate> rows = new ArrayList<>();
    Set<String> hidden = new LinkedHashSet<>();
    Set<String> masked = new LinkedHashSet<>();
    Integer minGroupSize = null;
    List<String> names = new ArrayList<>();

    for (Rule rule : matched) {
      RowFilter filter = RowFilter.parse(rule.rowFilter());
      if (filter.matchesAll()) {
        allRows = true;
      } else {
        Object value =
            filter.literal() != null ? filter.literal() : principal.attribute(filter.attribute());
        if (value == null) {
          return Optional.empty();
        }
        rows.add(new RowPredicate(filter.column(), value));
      }
      rule.columns()
          .forEach(
              (column, act) -> {
                if (act == Rule.ColumnAction.HIDE) {
                  hidden.add(column);
                } else {
                  masked.add(column);
                }
              });
      if (rule.aggregation() != null && rule.aggregation().minGroupSize() != null) {
        int size = rule.aggregation().minGroupSize();
        minGroupSize = minGroupSize == null ? size : Math.max(minGroupSize, size);
      }
      names.add(rule.name());
    }
    masked.removeAll(hidden);
    return Optional.of(new Obligations(allRows, rows, hidden, masked, minGroupSize, names));
  }
}
