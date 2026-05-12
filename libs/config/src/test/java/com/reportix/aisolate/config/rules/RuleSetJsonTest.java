package com.reportix.aisolate.config.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.api.Named.named;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class RuleSetJsonTest {

  private static final String SEED_JSON =
      """
      {"entity":"hr.compensation","classification":"restricted","rules":[
        {"name":"own-record","principals":{"role":"employee"},"actions":["read"],"effect":"allow",
         "row_filter":"employee_id == principal.employee_id",
         "columns":{"iban":"mask","internal_notes":"hide"}},
        {"name":"leadership","principals":{"role":"ceo"},"actions":["read","aggregate"],"effect":"allow",
         "row_filter":"true","columns":{"iban":"mask"},"aggregation":{"min_group_size":5}}]}
      """;

  @Test
  void givenSeedRulesJson_whenParse_thenRulesWithObligationsAreRead() {
    // Arrange / Act
    RuleSet ruleSet = RuleSet.parse(SEED_JSON);

    // Assert
    assertThat(ruleSet.entity()).isEqualTo("hr.compensation");
    assertThat(ruleSet.classification()).isEqualTo("restricted");
    assertThat(ruleSet.rules()).hasSize(2);
    Rule ownRecord = ruleSet.rules().get(0);
    assertThat(ownRecord.effect()).isEqualTo(Rule.Effect.ALLOW);
    assertThat(ownRecord.actions()).containsExactly(Rule.Action.READ);
    assertThat(ownRecord.rowFilter()).isEqualTo("employee_id == principal.employee_id");
    assertThat(ownRecord.columns())
        .containsEntry("iban", Rule.ColumnAction.MASK)
        .containsEntry("internal_notes", Rule.ColumnAction.HIDE);
    assertThat(ownRecord.aggregation()).isNull();
    assertThat(ruleSet.rules().get(1).aggregation().minGroupSize()).isEqualTo(5);
  }

  static Stream<Arguments> rulesOutsideTheGrammar() {
    return Stream.of(
        // Only `role` is understood; any other selector would silently widen the rule.
        arguments(
            named(
                "unknown principals selector",
                """
                {"entity":"hr.compensation","classification":"restricted","rules":[
                  {"name":"eng","principals":{"role":"employee","department":"Engineering"},
                   "actions":["read"],"effect":"allow","row_filter":"true","columns":{}}]}
                """),
            "principals"),
        arguments(
            named(
                "allow rule without a role",
                """
                {"entity":"hr.compensation","classification":"restricted","rules":[
                  {"name":"anyone","principals":{},"actions":["read"],"effect":"allow","row_filter":"true"}]}
                """),
            "principals.role"),
        arguments(
            named(
                "row filter outside the grammar",
                """
                {"entity":"hr.compensation","classification":"restricted","rules":[
                  {"name":"bad","principals":{"role":"employee"},"actions":["read"],"effect":"allow",
                   "row_filter":"1=1 OR true","columns":{}}]}
                """),
            "row_filter"),
        // Without an effect the evaluator would read the rule as an allow.
        arguments(
            named(
                "missing effect",
                """
                {"entity":"hr.compensation","classification":"restricted","rules":[
                  {"name":"x","principals":{"role":"contractor"},"actions":["read"],"row_filter":"true"}]}
                """),
            "effect"),
        // A deny that names no one never matches, which is wider than what was written.
        arguments(
            named(
                "deny without a role",
                """
                {"entity":"hr.compensation","classification":"restricted","rules":[
                  {"name":"nobody","effect":"deny","actions":["read"]}]}
                """),
            "principals.role"),
        arguments(
            named(
                "empty actions",
                """
                {"entity":"hr.compensation","classification":"restricted","rules":[
                  {"name":"idle","principals":{"role":"contractor"},"actions":[],"effect":"deny"}]}
                """),
            "actions"),
        // A deny drops every row: a filter, columns or a group size on it read narrower than that.
        arguments(
            named(
                "deny with a row filter",
                """
                {"entity":"hr.compensation","classification":"restricted","rules":[
                  {"name":"bad","principals":{"role":"contractor"},"actions":["read"],"effect":"deny",
                   "row_filter":"department == 'board'"}]}
                """),
            "a deny takes no row_filter"),
        arguments(
            named(
                "deny with columns",
                """
                {"entity":"hr.compensation","classification":"restricted","rules":[
                  {"name":"bad","principals":{"role":"contractor"},"actions":["read"],"effect":"deny",
                   "columns":{"salary":"hide"}}]}
                """),
            "a deny takes no row_filter"),
        arguments(
            named(
                "deny with an aggregation",
                """
                {"entity":"hr.compensation","classification":"restricted","rules":[
                  {"name":"bad","principals":{"role":"contractor"},"actions":["aggregate"],
                   "effect":"deny","aggregation":{"min_group_size":5}}]}
                """),
            "a deny takes no row_filter"),
        arguments(
            named(
                "min_group_size below one",
                """
                {"entity":"hr.compensation","classification":"restricted","rules":[
                  {"name":"agg","principals":{"role":"ceo"},"actions":["aggregate"],"effect":"allow",
                   "row_filter":"true","aggregation":{"min_group_size":0}}]}
                """),
            "min_group_size"));
  }

  @ParameterizedTest
  @MethodSource("rulesOutsideTheGrammar")
  void givenRuleOutsideTheGrammar_whenParse_thenRejectedAtLoadTime(String json, String field) {
    // Arrange / Act
    Throwable thrown = catchThrowable(() -> RuleSet.parse(json));

    // Assert
    assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessageContaining(field);
  }
}
