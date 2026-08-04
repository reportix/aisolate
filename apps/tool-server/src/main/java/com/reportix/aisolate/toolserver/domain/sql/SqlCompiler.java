package com.reportix.aisolate.toolserver.domain.sql;

import com.reportix.aisolate.sdk.Column;
import com.reportix.aisolate.sdk.Entity;
import com.reportix.aisolate.sdk.Filter;
import com.reportix.aisolate.sdk.GovernedQuery.Fn;
import com.reportix.aisolate.sdk.Result;
import com.reportix.aisolate.toolserver.domain.rules.Obligations;
import com.reportix.aisolate.toolserver.domain.rules.RowPredicate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.StringJoiner;
import org.springframework.stereotype.Component;

/**
 * Turns obligations plus validated tool arguments into one parameterised SELECT. Every identifier
 * comes from the catalog, every value is a bound parameter.
 */
@Component
public class SqlCompiler {

  /**
   * The asking user's identity value as {@code compileRead}'s {@code selfKey}, for an entity the
   * identity join is about: one whose subject is the identity attribute. Any other entity's subject
   * is someone else's id, a patient's for a clinician, so no row of it is the user's.
   *
   * @return null when no row of {@code entity} can be the user's own
   */
  public static Object ownKey(Entity entity, String identityAttribute, Object identityValue) {
    return entity.subject().equals(identityAttribute) ? identityValue : null;
  }

  /**
   * @param selfKey the asking user's value of the entity's subject (from the identity join), or
   *     null; when present the rows that belong to the user are marked {@code self}, and come
   *     first, so the model can answer "my ..." questions without ever learning who the user is
   */
  public CompiledQuery compileRead(
      Entity entity, Obligations obligations, ToolArgs.ReadArgs args, Object selfKey) {
    List<Object> params = new ArrayList<>();
    List<String> columns = new ArrayList<>();
    StringJoiner select = new StringJoiner(", ");
    if (selfKey != null) {
      select.add("(" + entity.column(entity.subject()).expression() + " = ?) AS self");
      params.add(selfKey);
      columns.add("self");
    }
    for (String name : entity.visibleColumns(obligations.hidden())) {
      Column column = entity.column(name);
      select.add(
          obligations.masked().contains(name)
              ? "'****' AS " + name
              : column.expression() + " AS " + name);
      columns.add(name);
    }
    StringBuilder sql =
        new StringBuilder("SELECT ").append(select).append(" FROM ").append(entity.from());
    appendWhere(sql, params, entity, obligations, args.filters());
    // The user's own row first: under the limit, "my ..." must not depend on where it sorts.
    sql.append(selfKey != null ? " ORDER BY self DESC, " : " ORDER BY ")
        .append(entity.column(entity.rowKey()).expression());
    sql.append(" LIMIT ?");
    params.add(args.limit());
    return new CompiledQuery(sql.toString(), params, columns, readLabels(entity, obligations));
  }

  /**
   * @throws IllegalArgumentException {@code min} or {@code max} under a minimum group size: the
   *     value is one person's, whatever the group's size
   */
  public CompiledQuery compileAggregate(
      Entity entity, Obligations obligations, ToolArgs.AggregateArgs args) {
    if (obligations.minGroupSize() != null
        && (args.function() == Fn.MIN || args.function() == Fn.MAX)) {
      throw new IllegalArgumentException(
          "function " + args.function() + " is not allowed here; use sum, avg or count");
    }
    List<Object> params = new ArrayList<>();
    List<String> columns = new ArrayList<>();
    String fn = args.function().name().toLowerCase(Locale.ROOT);
    StringBuilder sql = new StringBuilder("SELECT ");
    if (args.groupBy() != null) {
      sql.append(entity.column(args.groupBy()).expression()).append(" AS ").append(args.groupBy());
      sql.append(", ");
      columns.add(args.groupBy());
    }
    sql.append(fn).append("(").append(entity.column(args.column()).expression()).append(")");
    sql.append(" AS value, count(*) AS n FROM ").append(entity.from());
    columns.add("value");
    columns.add("n");
    appendWhere(sql, params, entity, obligations, args.filters());
    if (args.groupBy() != null) {
      sql.append(" GROUP BY ").append(entity.column(args.groupBy()).expression());
    }
    if (obligations.minGroupSize() != null) {
      // People, not rows: five readings of one patient are a group of one. Also valid without
      // GROUP BY: an ungrouped aggregate over too few people yields no row.
      // Limit: per query only; two allowed aggregates can still differ by one person.
      // Closing that needs query auditing or noise.
      sql.append(" HAVING count(DISTINCT ")
          .append(entity.column(entity.subject()).expression())
          .append(") >= ?");
      params.add(obligations.minGroupSize());
    }
    sql.append(" LIMIT ?");
    params.add(ToolArgs.MAX_LIMIT);
    return new CompiledQuery(sql.toString(), params, columns, aggregateLabels(entity, args));
  }

  /** The entity's labels for the columns a read returns; a masked column labels nothing. */
  private static Result.Labels readLabels(Entity entity, Obligations obligations) {
    List<String> readable = new ArrayList<>(entity.visibleColumns(obligations.hidden()));
    List<String> masked = readable.stream().filter(obligations.masked()::contains).toList();
    readable.removeAll(masked);
    Map<String, String> units = new LinkedHashMap<>();
    Map<String, String> unitColumns = new LinkedHashMap<>();
    Map<String, Integer> decimals = new LinkedHashMap<>();
    List<String> facts = new ArrayList<>();
    for (String name : readable) {
      Column column = entity.column(name);
      if (column.fact()) {
        facts.add(name);
      }
      if (column.decimals() != null) {
        decimals.put(name, column.decimals());
      }
      if (column.unit() != null) {
        units.put(name, column.unit());
      } else if (readable.contains(column.unitColumn())) {
        unitColumns.put(name, column.unitColumn());
      }
    }
    return new Result.Labels(
        readable.contains(entity.rowKey()) ? entity.rowKey() : null,
        readable.contains(entity.subjectName()) ? entity.subjectName() : null,
        readable.contains(entity.period()) ? entity.period() : null,
        units,
        unitColumns,
        decimals,
        masked,
        null,
        facts);
  }

  /**
   * An aggregate's rows are its groups: the grouping column keys them, and labels them when it is
   * the subject's name or the period. {@code value} is in the aggregated column's unit and
   * decimals, a count in neither.
   */
  private static Result.Labels aggregateLabels(Entity entity, ToolArgs.AggregateArgs args) {
    String group = args.groupBy();
    Column aggregated = entity.column(args.column());
    Map<String, String> units = new LinkedHashMap<>();
    Map<String, String> unitColumns = new LinkedHashMap<>();
    if (group != null && entity.column(group).unit() != null) {
      units.put(group, entity.column(group).unit());
    }
    Map<String, Integer> decimals = new LinkedHashMap<>();
    if (group != null && entity.column(group).decimals() != null) {
      decimals.put(group, entity.column(group).decimals());
    }
    if (args.function() != Fn.COUNT && aggregated.decimals() != null) {
      decimals.put("value", aggregated.decimals());
    }
    if (args.function() != Fn.COUNT && aggregated.unit() != null) {
      units.put("value", aggregated.unit());
    } else if (args.function() != Fn.COUNT
        && group != null
        && group.equals(aggregated.unitColumn())) {
      unitColumns.put("value", group);
    }
    return new Result.Labels(
        group,
        group != null && group.equals(entity.subjectName()) ? group : null,
        group != null && group.equals(entity.period()) ? group : null,
        units,
        unitColumns,
        decimals,
        List.of(),
        args.function().name().toLowerCase(Locale.ROOT),
        List.of());
  }

  private static String operator(Filter.Op op) {
    return switch (op) {
      case EQ -> "=";
      case NEQ -> "<>";
      case GT -> ">";
      case GTE -> ">=";
      case LT -> "<";
      case LTE -> "<=";
      case ILIKE -> "ILIKE";
    };
  }

  /** {@code ilike} means "contains, case-insensitive" unless the model wrote its own wildcards. */
  private static Object bindValue(Filter filter) {
    if (filter.op() == Filter.Op.ILIKE
        && filter.value() instanceof String text
        && !text.contains("%")
        && !text.contains("_")) {
      return "%" + text + "%";
    }
    return filter.value();
  }

  private static void appendWhere(
      StringBuilder sql,
      List<Object> params,
      Entity entity,
      Obligations obligations,
      List<Filter> filters) {
    List<String> clauses = new ArrayList<>();
    if (!obligations.allRows()) {
      StringJoiner rows = new StringJoiner(" OR ", "(", ")");
      for (RowPredicate predicate : obligations.rows()) {
        rows.add(entity.column(predicate.column()).expression() + " = ?");
        params.add(predicate.value());
      }
      clauses.add(rows.toString());
    }
    for (Filter filter : filters) {
      clauses.add(
          "(" + entity.column(filter.column()).expression() + " " + operator(filter.op()) + " ?)");
      params.add(bindValue(filter));
    }
    if (!clauses.isEmpty()) {
      sql.append(" WHERE ").append(String.join(" AND ", clauses));
    }
  }
}
