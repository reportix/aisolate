package com.reportix.aisolate.airlock.domain.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * The model and the browser read these sentences byte for byte from both runtimes: the Python
 * airlock reads {@code conformance/cases/prompts/airlock.json}, this test keeps the Java constants
 * equal to it.
 */
class PromptsConformanceTest {

  private static final Map<String, String> JAVA =
      Map.ofEntries(
          Map.entry("I_DONT_KNOW", ChatExchange.I_DONT_KNOW),
          Map.entry("COULD_NOT_REACH_DATA", ChatExchange.COULD_NOT_REACH_DATA),
          Map.entry("PLATFORM_PREAMBLE", ChatExchange.PLATFORM_PREAMBLE),
          Map.entry("USE_TOOLS_NUDGE", ChatExchange.USE_TOOLS_NUDGE),
          Map.entry("ANSWER_NOW", ChatExchange.ANSWER_NOW),
          Map.entry("REPEATED_CALL", ChatExchange.REPEATED_CALL),
          Map.entry("ANSWER_FORMAT_SCHEMA", AnswerFormat.SCHEMA),
          Map.entry("ANSWER_FORMAT_INSTRUCTION", AnswerFormat.INSTRUCTION),
          Map.entry("ANSWER_IN_FORMAT", AnswerFormat.ANSWER_IN_FORMAT),
          Map.entry("RETRY", AnswerFormat.RETRY),
          Map.entry("ERROR_COULD_NOT_COMPLETE", ChatEvent.Error.COULD_NOT_COMPLETE.message()),
          Map.entry("ERROR_NO_MODEL_ENDPOINT", ChatEvent.Error.NO_MODEL_ENDPOINT.message()),
          Map.entry("ERROR_MODEL_UNAVAILABLE", ChatEvent.Error.MODEL_UNAVAILABLE.message()));

  static Stream<Arguments> sentences() {
    String cases = System.getProperty("aisolate.conformance.dir");
    if (cases == null) {
      throw new IllegalStateException("the build passes aisolate.conformance.dir");
    }
    Map<String, String> shared =
        JsonMapper.shared()
            .readValue(
                Path.of(cases, "prompts", "airlock.json").toFile(), new TypeReference<>() {});
    assertThat(shared).containsOnlyKeys(JAVA.keySet());
    return shared.entrySet().stream().map(e -> arguments(e.getKey(), e.getValue()));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("sentences")
  void givenSharedSentence_whenComparedWithJava_thenEqual(String key, String shared) {
    // Arrange
    String java = JAVA.get(key);

    // Act
    boolean equal = shared.equals(java);

    // Assert
    assertThat(equal).as(key).isTrue();
  }
}
