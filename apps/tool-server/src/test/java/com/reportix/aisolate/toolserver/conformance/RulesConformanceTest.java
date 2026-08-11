package com.reportix.aisolate.toolserver.conformance;

import static com.reportix.aisolate.toolserver.conformance.ConformanceCases.SNAKE;
import static com.reportix.aisolate.toolserver.conformance.ConformanceCases.canonical;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Named.named;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import com.reportix.aisolate.config.rules.Rule;
import com.reportix.aisolate.config.rules.RuleSet;
import com.reportix.aisolate.identity.Principal;
import com.reportix.aisolate.sdk.Entity;
import com.reportix.aisolate.sdk.Result;
import com.reportix.aisolate.toolserver.conformance.ConformanceCases.Query;
import com.reportix.aisolate.toolserver.domain.rules.Obligations;
import com.reportix.aisolate.toolserver.domain.rules.RulesEvaluator;
import com.reportix.aisolate.toolserver.domain.sql.CompiledQuery;
import com.reportix.aisolate.toolserver.domain.sql.SqlCompiler;
import com.reportix.aisolate.toolserver.support.Containers;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@code conformance/cases/rules/compensation.json} and {@code vitals.json}: the SDK shapes the raw
 * arguments, the rules are evaluated, the arguments checked against what the user sees, the
 * statement compiled and run on the seeded table, in {@code GovernedQueryService.Bound}'s order.
 */
class RulesConformanceTest {

  private static final List<String> FILES = List.of("compensation.json", "vitals.json");
  private static final Set<String> SEEDED = new HashSet<>();

  static Stream<Arguments> cases() {
    return FILES.stream()
        .flatMap(
            name -> {
              JsonNode file = ConformanceCases.file("rules", name);
              return ConformanceCases.cases(file)
                  .map(
                      c -> {
                        var named = (org.junit.jupiter.api.Named<?>) c.get()[0];
                        return arguments(
                            named(name + " " + named.getName(), named.getPayload()), file);
                      });
            });
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("cases")
  void givenPrincipalRulesAndArguments_whenToolQueries_thenObligationsStatementAndResultAsJava(
      JsonNode testCase, JsonNode file) throws SQLException {
    // Arrange
    JsonNode fixture = file.path("fixture");
    Entity entity = ConformanceCases.entity(fixture.path("entity"));
    String identityAttribute = fixture.path("identity_attribute").asString();
    String ruleVersion = fixture.path("rule_version").asString();
    RuleSet ruleSet =
        RuleSet.parse(file.path("rule_sets").path(testCase.path("rule_set").asString()).toString());
    Principal principal = principal(testCase.path("principal"));
    JsonNode expect = testCase.path("expect");

    // Act
    String error = null;
    Obligations obligations = null;
    CompiledQuery query = null;
    Result result;
    try {
      Query shaped = Query.shape(testCase.path("query"));
      obligations = new RulesEvaluator().evaluate(principal, ruleSet, shaped.action()).orElse(null);
      query =
          obligations == null
              ? null
              : shaped.compile(
                  entity,
                  obligations,
                  SqlCompiler.ownKey(
                      entity, identityAttribute, principal.attribute(identityAttribute)));
    } catch (IllegalArgumentException invalid) {
      error = "Invalid arguments: " + invalid.getMessage();
    }
    if (query == null) {
      result = Result.empty(entity.name(), ruleVersion);
    } else {
      List<Map<String, Object>> rows = run(fixture.path("table"), query);
      result =
          new Result(
              entity.name(),
              rows.size(),
              query.columns(),
              rows,
              ruleVersion,
              obligations.matchedRules(),
              query.sqlHash(),
              query.labels());
    }

    // Assert
    if (expect.has("error")) {
      assertThat(error).isEqualTo(expect.path("error").asString());
      return;
    }
    assertThat(error).isNull();
    if (expect.path("obligations").isNull()) {
      assertThat(obligations).isNull();
    } else {
      assertThat(canonical(ConformanceCases.json(obligations)))
          .isEqualTo(canonical(expect.path("obligations")));
      assertThat(query.sql()).isEqualTo(expect.path("sql").asString());
      assertThat(canonical(SNAKE.valueToTree(query.params())))
          .isEqualTo(canonical(expect.path("params")));
      assertThat(query.sqlHash()).isEqualTo(expect.path("sql_hash").asString());
    }
    ObjectNode actual = SNAKE.valueToTree(result);
    ObjectNode expected = (ObjectNode) expect.path("result").deepCopy();
    if (shapedIsAggregate(testCase)) {
      // An aggregate has no ORDER BY: its groups compare as a multiset.
      assertThat(rows(actual.remove("rows")))
          .containsExactlyInAnyOrderElementsOf(rows(expected.remove("rows")));
    }
    assertThat(canonical(actual)).isEqualTo(canonical(expected));
  }

  @SuppressWarnings("unchecked")
  private static List<Object> rows(JsonNode rows) {
    return (List<Object>) canonical(rows);
  }

  private static boolean shapedIsAggregate(JsonNode testCase) {
    return Rule.Action.AGGREGATE
        .name()
        .equalsIgnoreCase(testCase.path("query").path("action").asString());
  }

  /** The token's claims plus what the identity join yielded, as the tool server builds it. */
  private static Principal principal(JsonNode principal) {
    Map<String, Object> attributes = new LinkedHashMap<>();
    principal
        .path("attributes")
        .properties()
        .forEach(e -> attributes.put(e.getKey(), ConformanceCases.value(e.getValue())));
    attributes.put("sub", principal.path("sub").asString());
    attributes.put("email", principal.path("email").asString());
    Set<String> roles = new HashSet<>();
    principal.path("roles").forEach(role -> roles.add(role.asString()));
    return new Principal(
        principal.path("sub").asString(),
        roles,
        null,
        principal.path("email").asString(),
        attributes);
  }

  private static List<Map<String, Object>> run(JsonNode table, CompiledQuery query)
      throws SQLException {
    try (Connection connection = Containers.connect()) {
      seed(connection, table);
    }
    return Containers.rows(query);
  }

  /** The fixture's table, created once per test JVM in the shared container. */
  private static synchronized void seed(Connection connection, JsonNode table) throws SQLException {
    String name = table.path("name").asString();
    if (!SEEDED.add(name)) {
      return;
    }
    List<String> columns = new ArrayList<>(table.path("columns").propertyNames());
    try (Statement ddl = connection.createStatement()) {
      ddl.execute("CREATE SCHEMA IF NOT EXISTS " + name.substring(0, name.indexOf('.')));
      ddl.execute("DROP TABLE IF EXISTS " + name);
      ddl.execute(
          "CREATE TABLE "
              + name
              + " ("
              + String.join(
                  ", ",
                  columns.stream()
                      .map(c -> c + " " + table.path("columns").path(c).asString())
                      .toList())
              + ")");
    }
    String insert =
        "INSERT INTO "
            + name
            + " ("
            + String.join(", ", columns)
            + ") VALUES ("
            + String.join(", ", columns.stream().map(c -> "?").toList())
            + ")";
    try (PreparedStatement statement = connection.prepareStatement(insert)) {
      for (JsonNode row : table.path("rows")) {
        for (int i = 0; i < columns.size(); i++) {
          statement.setObject(i + 1, ConformanceCases.value(row.path(columns.get(i))));
        }
        statement.addBatch();
      }
      statement.executeBatch();
    }
  }
}
