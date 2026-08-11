package com.reportix.aisolate.toolserver.conformance;

import static com.reportix.aisolate.toolserver.conformance.ConformanceCases.SNAKE;
import static com.reportix.aisolate.toolserver.conformance.ConformanceCases.canonical;
import static org.assertj.core.api.Assertions.assertThat;

import com.reportix.aisolate.sdk.Entity;
import com.reportix.aisolate.toolserver.conformance.ConformanceCases.Query;
import com.reportix.aisolate.toolserver.domain.sql.CompiledQuery;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;

/** {@code conformance/cases/sql-safety/compensation.json}: the argument boundary alone. */
class SqlSafetyConformanceTest {

  private static final JsonNode FILE = ConformanceCases.file("sql-safety", "compensation.json");
  private static final Entity ENTITY =
      ConformanceCases.entity(
          ConformanceCases.file(FILE.path("fixture").asString()).path("fixture").path("entity"));

  static Stream<Arguments> cases() {
    return ConformanceCases.cases(FILE);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("cases")
  void givenRawArguments_whenShapedCheckedAndCompiled_thenStatementOrToolErrorAsJava(
      JsonNode testCase) {
    // Arrange
    JsonNode expect = testCase.path("expect");
    var obligations =
        ConformanceCases.obligations(
            FILE.path("obligations").path(testCase.path("obligations").asString()));
    Object selfKey = ConformanceCases.value(testCase.path("self_key"));

    // Act
    CompiledQuery query = null;
    String error = null;
    try {
      query = Query.shape(testCase.path("query")).compile(ENTITY, obligations, selfKey);
    } catch (IllegalArgumentException invalid) {
      error = "Invalid arguments: " + invalid.getMessage();
    }

    // Assert
    if (expect.has("error")) {
      assertThat(error).isEqualTo(expect.path("error").asString());
      return;
    }
    assertThat(error).isNull();
    assertThat(query.sql()).isEqualTo(expect.path("sql").asString());
    assertThat(canonical(SNAKE.valueToTree(query.params())))
        .isEqualTo(canonical(expect.path("params")));
    assertThat(query.sqlHash()).isEqualTo(expect.path("sql_hash").asString());
    assertThat(query.columns())
        .containsExactlyElementsOf(
            expect.path("columns").valueStream().map(JsonNode::asString).toList());
    assertThat(canonical(SNAKE.valueToTree(query.labels())))
        .isEqualTo(canonical(expect.path("labels")));
  }
}
