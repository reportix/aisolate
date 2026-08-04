package com.reportix.aisolate.toolserver.domain.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Named.named;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import com.reportix.aisolate.config.rules.Rule;
import com.reportix.aisolate.config.rules.RuleSet;
import com.reportix.aisolate.identity.Principal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class RulesEvaluatorTest {

  private static final Rule OWN_RECORD =
      new Rule(
          "own-record",
          Map.of("role", "employee"),
          List.of(Rule.Action.READ),
          Rule.Effect.ALLOW,
          "employee_id == principal.employee_id",
          Map.of("iban", Rule.ColumnAction.MASK, "internal_notes", Rule.ColumnAction.HIDE),
          null);

  private static final Rule LEADERSHIP =
      new Rule(
          "leadership",
          Map.of("role", "ceo"),
          List.of(Rule.Action.READ, Rule.Action.AGGREGATE),
          Rule.Effect.ALLOW,
          "true",
          Map.of("iban", Rule.ColumnAction.MASK),
          new Rule.Aggregation(5));

  private static final RuleSet RULES =
      new RuleSet("hr.compensation", "restricted", List.of(OWN_RECORD, LEADERSHIP));

  private final RulesEvaluator evaluator = new RulesEvaluator();

  private static Principal principal(Set<String> roles, Integer employeeId) {
    Map<String, Object> attributes =
        new HashMap<>(Map.of("sub", "sub-1", "email", "eric@example.com"));
    if (employeeId != null) {
      attributes.put("employee_id", employeeId);
    }
    return new Principal("sub-1", roles, "acme-chat", "eric@example.com", attributes);
  }

  @Test
  void givenEmployee_whenEvaluateRead_thenOwnRowPredicateAndColumnObligations() {
    // Arrange
    Principal ali = principal(Set.of("employee"), 2);

    // Act
    Optional<Obligations> result = evaluator.evaluate(ali, RULES, Rule.Action.READ);

    // Assert
    assertThat(result).isPresent();
    Obligations obligations = result.get();
    assertThat(obligations.rows()).containsExactly(new RowPredicate("employee_id", 2));
    assertThat(obligations.hidden()).containsExactly("internal_notes");
    assertThat(obligations.masked()).containsExactly("iban");
    assertThat(obligations.minGroupSize()).isNull();
    assertThat(obligations.matchedRules()).containsExactly("own-record");
  }

  @Test
  void givenLiteralRowFilter_whenEvaluateRead_thenRowsBoundToTheLiteral() {
    // Arrange
    Rule publicDocs =
        new Rule(
            "public",
            Map.of("role", "employee"),
            List.of(Rule.Action.READ),
            Rule.Effect.ALLOW,
            "audience == 'all'",
            null,
            null);
    RuleSet rules = new RuleSet("kb.hr-documents", "internal", List.of(publicDocs));

    // Act
    Optional<Obligations> result =
        evaluator.evaluate(principal(Set.of("employee"), null), rules, Rule.Action.READ);

    // Assert
    assertThat(result.orElseThrow().rows()).containsExactly(new RowPredicate("audience", "all"));
  }

  @Test
  void givenCeo_whenEvaluateAggregate_thenAllRowsAndMinGroupSize() {
    // Arrange
    Principal dana = principal(Set.of("ceo"), 1);

    // Act
    Optional<Obligations> result = evaluator.evaluate(dana, RULES, Rule.Action.AGGREGATE);

    // Assert
    assertThat(result).isPresent();
    assertThat(result.get().allRows()).isTrue();
    assertThat(result.get().rows()).isEmpty();
    assertThat(result.get().minGroupSize()).isEqualTo(5);
    assertThat(result.get().matchedRules()).containsExactly("leadership");
  }

  static Stream<Arguments> givenNoRuleGrants_whenEvaluate_thenDenied() {
    Rule contractorDeny =
        new Rule(
            "no-contractors",
            Map.of("role", "contractor"),
            List.of(Rule.Action.READ, Rule.Action.AGGREGATE),
            Rule.Effect.DENY,
            null,
            Map.of(),
            null);
    return Stream.of(
        arguments(
            named("no rule for the action", principal(Set.of("employee"), 2)),
            RULES,
            Rule.Action.AGGREGATE),
        arguments(
            named("no rule for the role", principal(Set.of("intern"), 9)), RULES, Rule.Action.READ),
        // a row filter on an attribute the principal lacks denies, never widens
        arguments(
            named("the principal's attribute missing", principal(Set.of("employee"), null)),
            RULES,
            Rule.Action.READ),
        arguments(
            named(
                "an explicit deny beside a matching allow",
                principal(Set.of("employee", "contractor"), 4)),
            new RuleSet("hr.compensation", "restricted", List.of(OWN_RECORD, contractorDeny)),
            Rule.Action.READ));
  }

  @ParameterizedTest
  @MethodSource
  void givenNoRuleGrants_whenEvaluate_thenDenied(
      Principal principal, RuleSet rules, Rule.Action action) {
    // Arrange

    // Act
    Optional<Obligations> result = evaluator.evaluate(principal, rules, action);

    // Assert
    assertThat(result).isEmpty();
  }

  @Test
  void givenEmployeeAndCeoRoles_whenEvaluateRead_thenUnionOfRowsAndMostRestrictiveColumns() {
    // Arrange
    Principal both = principal(Set.of("employee", "ceo"), 2);

    // Act
    Optional<Obligations> result = evaluator.evaluate(both, RULES, Rule.Action.READ);

    // Assert
    assertThat(result).isPresent();
    Obligations obligations = result.get();
    assertThat(obligations.allRows()).isTrue();
    assertThat(obligations.rows()).containsExactly(new RowPredicate("employee_id", 2));
    assertThat(obligations.hidden()).containsExactly("internal_notes");
    assertThat(obligations.masked()).containsExactly("iban");
    assertThat(obligations.minGroupSize()).isEqualTo(5);
    assertThat(obligations.matchedRules()).containsExactlyInAnyOrder("own-record", "leadership");
  }
}
