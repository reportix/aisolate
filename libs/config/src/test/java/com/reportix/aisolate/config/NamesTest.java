package com.reportix.aisolate.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Named.named;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class NamesTest {

  static Stream<Arguments> names() {
    return Stream.of(
        arguments(
            named("distinct names", List.of("read_records", "write_note", "eric@example.com")),
            List.of()),
        // null, empty and whitespace-only are all blank
        arguments(
            named("blank entries", Arrays.asList("read_records", null, "", "  ")),
            List.of("must not contain a blank tool")),
        // a blank is no repeat, and its whitespace is no space in a name
        arguments(
            named("the same blank twice", List.of("  ", "read_records", "  ")),
            List.of("must not contain a blank tool")),
        // a tab is a space too; the repeat is the repeat rule's own sentence
        arguments(
            named(
                "names with whitespace",
                List.of("read records", "write_note", "send\tmail", "read records")),
            List.of(
                "\"read records\" has a space in it",
                "\"send\tmail\" has a space in it",
                "lists read records twice")),
        // b three times, a twice, c once
        arguments(
            named("repeated names", List.of("b", "a", "b", "c", "a", "b")),
            List.of("lists b twice", "lists a twice")));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("names")
  void givenNames_whenProblems_thenOneSentencePerRuleAndNameInListOrder(
      List<String> names, List<String> expected) {
    // Arrange: the row's names

    // Act
    List<String> problems = Names.problems("tool", names);

    // Assert
    assertThat(problems).containsExactlyElementsOf(expected);
  }
}
