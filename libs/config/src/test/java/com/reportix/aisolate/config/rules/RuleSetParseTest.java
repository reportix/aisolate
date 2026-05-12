package com.reportix.aisolate.config.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * A rule set governs data, so anything that is not exactly a rule set is refused: read leniently, a
 * misspelt key would be dropped and the rule set apply as its developer never meant.
 */
class RuleSetParseTest {

  @ParameterizedTest
  @ValueSource(strings = {"scope", "airlock"})
  void givenKeyOutsideTheShape_whenParsed_thenRefused(String key) {
    // Arrange
    String json =
        "{\"entity\":\"x\",\"classification\":\"c\",\"rules\":[],\""
            + key
            + "\":{\"airlock\":\"a\"}}";

    // Act / Assert
    assertThatThrownBy(() -> RuleSet.parse(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("unknown key '" + key + "' (a rule set has entity, classification and rules)");
  }

  static Stream<Arguments> rulesWithoutAnEntity() {
    return Stream.of(arguments("absent", ""), arguments("blank", "\"entity\":\" \","));
  }

  @ParameterizedTest(name = "entity {0}")
  @MethodSource("rulesWithoutAnEntity")
  void givenMissingOrBlankEntity_whenParsed_thenRefused(String kind, String entityMember) {
    // Arrange
    String json = "{" + entityMember + "\"classification\":\"c\",\"rules\":[]}";

    // Act / Assert
    assertThatThrownBy(() -> RuleSet.parse(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("a rule set needs an entity");
  }

  @Test
  void givenRowFilterTwice_whenParsed_thenRefusedAsDuplicate() {
    // Arrange: read leniently, the second row_filter would replace the first and grant every row
    String json =
        """
        {"entity":"x","classification":"c","rules":[
          {"name":"r","principals":{"role":"employee"},"actions":["read"],"effect":"allow",
           "row_filter":"employee_id == principal.employee_id","row_filter":"true"}]}
        """;

    // Act / Assert
    assertThatThrownBy(() -> RuleSet.parse(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Duplicate");
  }

  @ParameterizedTest
  @ValueSource(strings = {"[]", "not json"})
  void givenNoObject_whenParsed_thenRefused(String json) {
    // Arrange / Act
    Throwable thrown = catchThrowable(() -> RuleSet.parse(json));

    // Assert
    assertThat(thrown).isInstanceOf(IllegalArgumentException.class);
  }
}
