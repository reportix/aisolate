package com.reportix.aisolate.toolserver.domain.sql;

import com.reportix.aisolate.sdk.Column;
import com.reportix.aisolate.sdk.Entity;
import com.reportix.aisolate.sdk.Filter;
import com.reportix.aisolate.sdk.GovernedQuery.Fn;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * A tool's query arguments, validated against the entity before any SQL is built: every column one
 * the model may see, every value typed to its column, the limit capped.
 *
 * @param visible the columns the model may filter, group or aggregate on: the entity's minus the
 *     hidden and the masked ones
 */
public record ToolArgs(Entity entity, Set<String> visible) {

  public static final int MAX_LIMIT = 100;

  /** A prompt-injected argument must not grow the WHERE clause without bound. */
  public static final int MAX_FILTERS = 10;

  public record ReadArgs(List<Filter> filters, int limit) {}

  public record AggregateArgs(Fn function, String column, String groupBy, List<Filter> filters) {}

  public ReadArgs read(List<Filter> filters, int limit) {
    return new ReadArgs(typed(filters), Math.max(1, Math.min(MAX_LIMIT, limit)));
  }

  public AggregateArgs aggregate(Fn function, String column, String groupBy, List<Filter> filters) {
    if (function == null) {
      throw new IllegalArgumentException("function is required");
    }
    String aggregated = visibleColumn(column);
    if (function != Fn.COUNT && entity.column(aggregated).type() != Column.Type.NUMBER) {
      throw new IllegalArgumentException(function + " needs a numeric column, got: " + aggregated);
    }
    String group = groupBy == null ? null : visibleColumn(groupBy);
    return new AggregateArgs(function, aggregated, group, typed(filters));
  }

  private List<Filter> typed(List<Filter> filters) {
    List<Filter> typed = new ArrayList<>();
    if (filters != null && filters.size() > MAX_FILTERS) {
      throw new IllegalArgumentException("at most " + MAX_FILTERS + " filters");
    }
    for (Filter filter : filters == null ? List.<Filter>of() : filters) {
      String column = visibleColumn(filter.column());
      if (filter.op() == null) {
        throw new IllegalArgumentException("op is required");
      }
      if (filter.op() == Filter.Op.ILIKE && entity.column(column).type() != Column.Type.TEXT) {
        throw new IllegalArgumentException("op ilike needs a text column, got: " + column);
      }
      typed.add(new Filter(column, filter.op(), typedValue(entity.column(column), filter.value())));
    }
    return typed;
  }

  private String visibleColumn(String name) {
    if (name == null || !visible.contains(name)) {
      throw new IllegalArgumentException("column is not available: " + name);
    }
    return name;
  }

  private static Object typedValue(Column column, Object raw) {
    if (raw == null) {
      throw new IllegalArgumentException("value is required for filter on " + column.name());
    }
    if (column.type() == Column.Type.TEXT) {
      return String.valueOf(raw);
    }
    if (raw instanceof Number n) {
      return new BigDecimal(n.toString());
    }
    try {
      return new BigDecimal(String.valueOf(raw).trim());
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException(
          "value for numeric column " + column.name() + " is not a number: " + raw);
    }
  }
}
