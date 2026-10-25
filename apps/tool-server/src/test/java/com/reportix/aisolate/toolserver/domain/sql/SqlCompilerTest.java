package com.reportix.aisolate.toolserver.domain.sql;

import static org.assertj.core.api.Assertions.assertThat;

import com.reportix.aisolate.sdk.Column;
import com.reportix.aisolate.sdk.Entity;
import com.reportix.aisolate.sdk.Filter;
import com.reportix.aisolate.sdk.GovernedQuery.Fn;
import com.reportix.aisolate.sdk.Result;
import com.reportix.aisolate.toolserver.application.GovernedToolsConfig;
import com.reportix.aisolate.toolserver.domain.rules.Obligations;
import com.reportix.aisolate.toolserver.domain.rules.RowPredicate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.JsonNode;

class SqlCompilerTest {

  private static final Entity ENTITY = TestEntities.HR_COMPENSATION;

  private static final Entity MEDICATIONS =
      new Entity(
          "ehr.medications",
          "ehr.medications m",
          "id",
          new Column("id", "m.id", Column.Type.NUMBER),
          new Column("drug", "m.drug", Column.Type.TEXT).asFact(),
          new Column("dose", "m.dose", Column.Type.TEXT).asFact());

  private final SqlCompiler compiler = new SqlCompiler();

  private static Obligations ownRow() {
    return new Obligations(
        false,
        List.of(new RowPredicate("employee_id", 2)),
        Set.of("internal_notes"),
        Set.of("iban"),
        null,
        List.of("own-record"));
  }

  private static Obligations allRows(Integer minGroupSize) {
    return new Obligations(
        true, List.of(), Set.of(), Set.of("iban"), minGroupSize, List.of("leadership"));
  }

  @Test
  void givenEntityAboutAnotherColumn_whenCompiled_thenSelfAndTheGroupMinimumUseIt() {
    // Arrange: one row per reading, each about a patient
    Entity readings =
        new Entity(
                "ehr.vitals",
                "ehr.vitals v",
                "vital_id",
                new Column("vital_id", "v.id", Column.Type.NUMBER),
                new Column("patient_id", "v.patient_id", Column.Type.NUMBER),
                new Column("heart_rate", "v.heart_rate", Column.Type.NUMBER))
            .withSubject("patient_id");

    // Act
    CompiledQuery read =
        compiler.compileRead(readings, allRows(null), new ToolArgs.ReadArgs(List.of(), 10), 7);
    CompiledQuery aggregate =
        compiler.compileAggregate(
            readings,
            allRows(5),
            new ToolArgs.AggregateArgs(Fn.AVG, "heart_rate", null, List.of()));

    // Assert: five readings of one patient are a group of one
    assertThat(read.sql()).contains("(v.patient_id = ?) AS self");
    assertThat(aggregate.sql()).contains("HAVING count(DISTINCT v.patient_id) >= ?");
  }

  @ParameterizedTest
  @CsvSource(
      nullValues = "NULL",
      value = {
        // The join finds an employee; the compensation rows are about employees
        "employee_id, employee_id, 13",
        // The join finds a clinician; a patient's row is never the clinician's own
        "patient_id, clinician_id, NULL",
        "employee_id, NULL, NULL"
      })
  void givenIdentityAttribute_whenOwnKey_thenOnlyAnEntityAboutTheIdentityHasOwnRows(
      String subject, String identityAttribute, Integer expected) {
    // Arrange
    Entity entity =
        new Entity(
                "e",
                "e",
                "id",
                new Column("id", "id", Column.Type.NUMBER),
                new Column(subject, subject, Column.Type.NUMBER))
            .withSubject(subject);

    // Act
    Object own = SqlCompiler.ownKey(entity, identityAttribute, 13);

    // Assert
    assertThat(own).isEqualTo(expected);
  }

  @Test
  void givenAnyQuery_whenCompiled_thenSqlHashIsStableForSameShape() {
    // Arrange
    ToolArgs.ReadArgs first =
        new ToolArgs.ReadArgs(List.of(new Filter("department", Filter.Op.EQ, "A")), 100);
    ToolArgs.ReadArgs second =
        new ToolArgs.ReadArgs(List.of(new Filter("department", Filter.Op.EQ, "B")), 100);

    // Act
    CompiledQuery q1 = compiler.compileRead(ENTITY, ownRow(), first, null);
    CompiledQuery q2 = compiler.compileRead(ENTITY, ownRow(), second, null);

    // Assert
    assertThat(q1.sqlHash()).isEqualTo(q2.sqlHash()).hasSize(12);
  }

  @Test
  void givenMaskedAndHiddenColumns_whenCompileRead_thenLabelsNameOnlyWhatTheRowsShow() {
    // Arrange: iban masked, internal_notes hidden; the salary's unit is the currency column
    Obligations obligations = ownRow();

    // Act
    Result.Labels labels =
        compiler
            .compileRead(ENTITY, obligations, new ToolArgs.ReadArgs(List.of(), 10), null)
            .labels();

    // Assert
    assertThat(labels.rowKey()).isEqualTo("employee_id");
    assertThat(labels.subject()).isEqualTo("full_name");
    assertThat(labels.period()).isNull();
    assertThat(labels.units()).isEqualTo(Map.of("bonus_2025", "EUR"));
    assertThat(labels.unitColumns()).isEqualTo(Map.of("gross_salary", "currency"));
    assertThat(labels.decimals()).isEqualTo(Map.of("bonus_2025", 2));
    assertThat(labels.masked()).containsExactly("iban");
  }

  @Test
  void givenMaskedUnitColumn_whenCompileRead_thenTheFigureHasNoUnitRatherThanStars() {
    // Arrange
    Obligations currencyMasked =
        new Obligations(true, List.of(), Set.of(), Set.of("currency"), null, List.of("r"));

    // Act
    Result.Labels labels =
        compiler
            .compileRead(ENTITY, currencyMasked, new ToolArgs.ReadArgs(List.of(), 10), null)
            .labels();

    // Assert
    assertThat(labels.unitColumns()).doesNotContainKey("gross_salary");
    assertThat(labels.masked()).containsExactly("currency");
  }

  @ParameterizedTest
  @CsvSource(
      nullValues = "NULL",
      value = {"SUM, NULL, EUR, 2", "COUNT, NULL, NULL, NULL", "AVG, department, EUR, 2"})
  void
      givenAggregate_whenCompileAggregate_thenValueCarriesTheColumnsUnitAndDecimalsButACountNeither(
          Fn function, String groupBy, String unit, Integer decimals) {
    // Arrange
    ToolArgs.AggregateArgs args =
        new ToolArgs.AggregateArgs(function, "bonus_2025", groupBy, List.of());

    // Act
    Result.Labels labels = compiler.compileAggregate(ENTITY, allRows(null), args).labels();

    // Assert
    assertThat(labels.units().get("value")).isEqualTo(unit);
    assertThat(labels.decimals().get("value")).isEqualTo(decimals);
    assertThat(labels.rowKey()).isEqualTo(groupBy);
    assertThat(labels.subject()).isNull();
    assertThat(labels.function()).isEqualTo(function.name().toLowerCase(java.util.Locale.ROOT));
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      nullValues = "NULL",
      value = {"NULL|NULL|drug,dose", "drug|NULL|dose", "NULL|dose|drug", "drug|dose|NULL"})
  void givenFactColumns_whenCompileRead_thenLabelsListTheReadableOnesOnlyWhenAny(
      String hidden, String masked, String facts) {
    // Arrange
    Obligations obligations =
        new Obligations(
            true,
            List.of(),
            hidden == null ? Set.of() : Set.of(hidden),
            masked == null ? Set.of() : Set.of(masked),
            null,
            List.of("all"));
    List<String> readable = facts == null ? List.of() : List.of(facts.split(","));

    // Act
    Result.Labels labels =
        compiler
            .compileRead(MEDICATIONS, obligations, new ToolArgs.ReadArgs(List.of(), 10), null)
            .labels();
    JsonNode written = GovernedToolsConfig.RESULT_JSON.valueToTree(labels);

    // Assert: absent when none, so a result without facts is written as before
    assertThat(labels.facts()).isEqualTo(readable);
    assertThat(written.path("facts").valueStream().map(JsonNode::asString).toList())
        .isEqualTo(readable);
    assertThat(written.has("facts")).isEqualTo(facts != null);
  }

  @Test
  void givenFactColumn_whenCompileAggregate_thenLabelsCarryNoFacts() {
    // Arrange
    Obligations obligations =
        new Obligations(true, List.of(), Set.of(), Set.of(), null, List.of("all"));
    ToolArgs.AggregateArgs args = new ToolArgs.AggregateArgs(Fn.COUNT, "id", "drug", List.of());

    // Act
    Result.Labels labels = compiler.compileAggregate(MEDICATIONS, obligations, args).labels();

    // Assert
    assertThat(labels.facts()).isEmpty();
    assertThat(GovernedToolsConfig.RESULT_JSON.valueToTree(labels).has("facts")).isFalse();
  }
}
