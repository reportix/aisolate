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

  static Stream<Arguments> roles() {
    return Stream.of(
        // roles match exactly: another case is another role
        arguments(named("distinct roles", List.of("ceo", "Finance", "finance")), List.of()),
        arguments(named("no roles", List.of()), List.of()),
        arguments(
            named("a star", List.of("employee", "*")),
            List.of("* is no role: list each role; only callers take * for any")),
        // the general rules first, the star's own sentence once
        arguments(
            named("a star twice", List.of("*", "*")),
            List.of("lists * twice", "* is no role: list each role; only callers take * for any")),
        arguments(
            named("a blank role", List.of("ceo", " ")), List.of("must not contain a blank role")),
        arguments(
            named("a role with a space", List.of("hr admin")),
            List.of("\"hr admin\" has a space in it")),
        arguments(named("a role twice", List.of("ceo", "hr", "ceo")), List.of("lists ceo twice")));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("roles")
  void givenRoles_whenRoleProblems_thenOneSentencePerRule(
      List<String> roles, List<String> expected) {
    // Arrange: the row's roles

    // Act
    List<String> problems = Names.roleProblems(roles);

    // Assert
    assertThat(problems).containsExactlyElementsOf(expected);
  }

  static Stream<Arguments> emails() {
    return Stream.of(
        arguments(
            named("distinct e-mails", List.of("eric@example.com", "nora@example.com")), List.of()),
        // one mailbox in two cases
        arguments(
            named("one e-mail in two cases", List.of("eric@example.com", "Eric@Example.com")),
            List.of("lists eric@example.com twice")),
        // a star is no e-mail like any other word
        arguments(
            named("no addresses", List.of("eric", "*", "eric")),
            List.of("lists eric twice", "eric is no e-mail", "* is no e-mail")));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("emails")
  void givenEmails_whenEmailProblems_thenRepeatsInAnyCaseAndNonAddressesRefused(
      List<String> emails, List<String> expected) {
    // Arrange: the row's e-mails

    // Act
    List<String> problems = Names.emailProblems(emails);

    // Assert
    assertThat(problems).containsExactlyElementsOf(expected);
  }
}
