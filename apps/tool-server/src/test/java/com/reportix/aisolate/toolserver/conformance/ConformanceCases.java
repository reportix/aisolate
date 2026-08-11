package com.reportix.aisolate.toolserver.conformance;

import static org.junit.jupiter.api.Named.named;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import com.reportix.aisolate.config.rules.Rule;
import com.reportix.aisolate.sdk.Arguments;
import com.reportix.aisolate.sdk.Column;
import com.reportix.aisolate.sdk.Entity;
import com.reportix.aisolate.sdk.Filter;
import com.reportix.aisolate.sdk.GovernedQuery.Fn;
import com.reportix.aisolate.toolserver.application.GovernedToolsConfig;
import com.reportix.aisolate.toolserver.domain.rules.Obligations;
import com.reportix.aisolate.toolserver.domain.rules.RowPredicate;
import com.reportix.aisolate.toolserver.domain.sql.CompiledQuery;
import com.reportix.aisolate.toolserver.domain.sql.SqlCompiler;
import com.reportix.aisolate.toolserver.domain.sql.ToolArgs;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Reading {@code conformance/cases} the way every runner of both runtimes does. */
final class ConformanceCases {

  /** {@code 78000.00} stays exact. */
  static final JsonMapper JSON =
      JsonMapper.builder().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();

  /** The tool result's wire names, as {@code GovernedToolsConfig} writes them. */
  static final JsonMapper SNAKE =
      GovernedToolsConfig.RESULT_JSON
          .rebuild()
          .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
          .build();

  static JsonNode file(String... path) {
    String dir = System.getProperty("aisolate.conformance.dir");
    if (dir == null) {
      throw new IllegalStateException("the build passes aisolate.conformance.dir");
    }
    return JSON.readTree(Path.of(dir, path).toFile());
  }

  /** One argument per case, named by its id. */
  static Stream<org.junit.jupiter.params.provider.Arguments> cases(JsonNode file) {
    return StreamSupport.stream(file.path("cases").spliterator(), false)
        .map(c -> arguments(named(c.path("id").asString(), c)));
  }

  static Object value(JsonNode node) {
    return node == null || node.isNull() || node.isMissingNode()
        ? null
        : JSON.treeToValue(node, Object.class);
  }

  /** The entity as a plugin declares it, from a fixture. */
  static Entity entity(JsonNode fixture) {
    Map<String, Column> columns = new LinkedHashMap<>();
    for (JsonNode c : fixture.path("columns")) {
      columns.put(
          c.path("name").asString(),
          new Column(
              c.path("name").asString(),
              c.path("expression").asString(),
              Column.Type.valueOf(c.path("type").asString().toUpperCase(Locale.ROOT)),
              c.path("unit").asString(null),
              c.path("unit_column").asString(null),
              c.path("decimals").isNumber() ? c.path("decimals").intValue() : null,
              false));
    }
    return new Entity(
        fixture.path("name").asString(),
        fixture.path("from").asString(),
        fixture.path("row_key").asString(),
        columns,
        fixture.path("subject").asString(null),
        fixture.path("subject_name").asString(null),
        fixture.path("period").asString(null));
  }

  static Obligations obligations(JsonNode preset) {
    List<RowPredicate> rows = new ArrayList<>();
    for (JsonNode row : preset.path("rows")) {
      rows.add(new RowPredicate(row.path("column").asString(), value(row.path("value"))));
    }
    return new Obligations(
        preset.path("all_rows").booleanValue(),
        rows,
        strings(preset.path("hidden")),
        strings(preset.path("masked")),
        preset.path("min_group_size").isNumber() ? preset.path("min_group_size").intValue() : null,
        List.copyOf(strings(preset.path("matched_rules"))));
  }

  /** Obligations in the cases' shape: sets sorted, rule names in rule-set order. */
  static JsonNode json(Obligations obligations) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("all_rows", obligations.allRows());
    out.put(
        "rows",
        obligations.rows().stream()
            .map(p -> Map.of("column", p.column(), "value", p.value()))
            .toList());
    out.put("hidden", obligations.hidden().stream().sorted().toList());
    out.put("masked", obligations.masked().stream().sorted().toList());
    out.put("min_group_size", obligations.minGroupSize());
    out.put("matched_rules", obligations.matchedRules());
    return JSON.valueToTree(out);
  }

  /** The model's raw arguments shaped as the standard tools shape them, before any rule. */
  record Query(
      Rule.Action action,
      List<Filter> filters,
      int limit,
      Fn function,
      String column,
      String groupBy) {

    @SuppressWarnings("unchecked")
    static Query shape(JsonNode query) {
      Map<String, Object> args = (Map<String, Object>) value(query.path("args"));
      if ("read".equals(query.path("action").asString())) {
        return new Query(
            Rule.Action.READ,
            Arguments.filters(args.get("filters")),
            Arguments.limit(args.get("limit"), ToolArgs.MAX_LIMIT),
            null,
            null,
            null);
      }
      // Argument order of the templates' aggregate tools: function, column, group_by, filters.
      Fn function = Arguments.function(args.get("function"));
      String column = Arguments.string(args.get("column"), "column");
      String groupBy =
          args.get("group_by") == null ? null : Arguments.string(args.get("group_by"), "group_by");
      return new Query(
          Rule.Action.AGGREGATE,
          Arguments.filters(args.get("filters")),
          0,
          function,
          column,
          groupBy);
    }

    /** {@code GovernedQueryService.Bound}: the arguments checked against what the user sees. */
    CompiledQuery compile(Entity entity, Obligations obligations, Object selfKey) {
      ToolArgs args =
          new ToolArgs(entity, entity.queryableColumns(obligations.hidden(), obligations.masked()));
      SqlCompiler compiler = new SqlCompiler();
      return action == Rule.Action.READ
          ? compiler.compileRead(entity, obligations, args.read(filters, limit), selfKey)
          : compiler.compileAggregate(
              entity, obligations, args.aggregate(function, column, groupBy, filters));
    }
  }

  /** Numbers as decimals ({@code 78000 == 78000.00}), objects without key order. */
  static Object canonical(JsonNode node) {
    if (node == null || node.isNull() || node.isMissingNode()) {
      return null;
    }
    if (node.isNumber()) {
      return node.decimalValue().stripTrailingZeros();
    }
    if (node.isBoolean()) {
      return node.booleanValue();
    }
    if (node.isArray()) {
      List<Object> items = new ArrayList<>();
      node.forEach(item -> items.add(canonical(item)));
      return items;
    }
    if (node.isObject()) {
      Map<String, Object> fields = new TreeMap<>();
      node.properties().forEach(e -> fields.put(e.getKey(), canonical(e.getValue())));
      return fields;
    }
    return node.asString();
  }

  private static Set<String> strings(JsonNode array) {
    Set<String> out = new LinkedHashSet<>();
    array.forEach(item -> out.add(item.asString()));
    return out;
  }

  private ConformanceCases() {}
}
