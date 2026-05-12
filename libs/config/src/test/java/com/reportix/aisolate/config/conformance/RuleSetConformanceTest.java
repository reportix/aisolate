package com.reportix.aisolate.config.conformance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.api.Named.named;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import com.reportix.aisolate.config.rules.RuleSet;
import java.nio.file.Path;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code conformance/cases/rules/rule-set-load.json}: which rule-set files load, and the refusal.
 */
class RuleSetConformanceTest {

  static Stream<Arguments> cases() {
    String dir = System.getProperty("aisolate.conformance.dir");
    if (dir == null) {
      throw new IllegalStateException("the build passes aisolate.conformance.dir");
    }
    JsonNode file =
        JsonMapper.shared().readTree(Path.of(dir, "rules", "rule-set-load.json").toFile());
    return StreamSupport.stream(file.path("cases").spliterator(), false)
        .map(c -> arguments(named(c.path("id").asString(), c)));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("cases")
  void givenRuleSetFile_whenParsed_thenLoadsOrRefusesAsTheCaseSays(JsonNode testCase) {
    // Arrange
    String json = testCase.path("json").asString();
    JsonNode expect = testCase.path("expect");

    // Act
    Throwable thrown = catchThrowable(() -> RuleSet.parse(json));

    // Assert
    if (expect.path("valid").asBoolean(false)) {
      assertThat(thrown).isNull();
    } else if (expect.has("error")) {
      assertThat(thrown)
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage(expect.path("error").asString());
    } else {
      assertThat(thrown)
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageStartingWith(expect.path("error_prefix").asString());
    }
  }
}
