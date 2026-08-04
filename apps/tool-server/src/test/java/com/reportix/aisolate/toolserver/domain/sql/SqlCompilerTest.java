package com.reportix.aisolate.toolserver.domain.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.reportix.aisolate.sdk.Column;
import com.reportix.aisolate.sdk.Entity;
import com.reportix.aisolate.sdk.Filter;
import com.reportix.aisolate.sdk.GovernedQuery.Fn;
import com.reportix.aisolate.sdk.Result;
import com.reportix.aisolate.toolserver.domain.rules.Obligations;
import com.reportix.aisolate.toolserver.domain.rules.RowPredicate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

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
  void givenOwnRowObligation_whenCompileRead_thenIdentityPredicateIsBoundAndColumnsObeyed() {
    // Arrange
    Obligations obligations = ownRow();
    ToolArgs.ReadArgs args = new ToolArgs.ReadArgs(List.of(), 100);

    // Act
    CompiledQuery query = compiler.compileRead(ENTITY, obligations, args, null);

    // Assert
    assertThat(query.sql()).contains("WHERE (c.employee_id = ?)");
    assertThat(query.sql()).contains("'****' AS iban");
    assertThat(query.sql()).doesNotContain("internal_notes");
    assertThat(query.sql()).endsWith("LIMIT ?");
    assertThat(query.params()).containsExactly(2, 100);
    assertThat(query.columns()).doesNotContain("internal_notes").contains("iban", "gross_salary");
  }

  @Test
  void givenModelFilterWithQuote_whenCompileRead_thenValueIsBoundNeverInterpolated() {
    // Arrange
    String hostile = "Engineering' OR '1'='1";
    ToolArgs.ReadArgs args =
        new ToolArgs.ReadArgs(List.of(new Filter("department", Filter.Op.EQ, hostile)), 100);

    // Act
    CompiledQuery query = compiler.compileRead(ENTITY, ownRow(), args, null);

    // Assert
    assertThat(query.sql()).doesNotContain(hostile);
    assertThat(query.sql()).contains("WHERE (c.employee_id = ?) AND (e.department = ?)");
    assertThat(query.params()).containsExactly(2, hostile, 100);
  }

  @Test
  void givenUnionOfOwnRowAndAllRows_whenCompileRead_thenNoRowRestriction() {
    // Arrange
    Obligations union =
        new Obligations(
            true,
            List.of(new RowPredicate("employee_id", 2)),
            Set.of("internal_notes"),
            Set.of("iban"),
            5,
            List.of("own-record", "leadership"));

    // Act
    CompiledQuery query =
        compiler.compileRead(ENTITY, union, new ToolArgs.ReadArgs(List.of(), 100), null);

    // Assert
    assertThat(query.sql()).doesNotContain("WHERE");
    assertThat(query.params()).containsExactly(100);
  }

  @Test
  void givenAggregateWithMinGroupSize_whenCompileAggregate_thenHavingCountAtLeastGroupSize() {
    // Arrange
    ToolArgs.AggregateArgs args =
        new ToolArgs.AggregateArgs(Fn.AVG, "gross_salary", "department", List.of());

    // Act
    CompiledQuery query = compiler.compileAggregate(ENTITY, allRows(5), args);

    // Assert
    assertThat(query.sql())
        .contains("SELECT e.department AS department, avg(c.gross_salary) AS value, count(*) AS n")
        .contains("GROUP BY e.department")
        .contains("HAVING count(DISTINCT c.employee_id) >= ?")
        .endsWith("LIMIT ?");
    assertThat(query.params()).containsExactly(5, ToolArgs.MAX_LIMIT);
  }

  @Test
  void givenUngroupedAggregateUnderOwnRow_whenCompileAggregate_thenPredicateAndNoGroupBy() {
    // Arrange
    ToolArgs.AggregateArgs args =
        new ToolArgs.AggregateArgs(Fn.SUM, "gross_salary", null, List.of());

    // Act
    CompiledQuery query = compiler.compileAggregate(ENTITY, ownRow(), args);

    // Assert
    assertThat(query.sql())
        .contains("SELECT sum(c.gross_salary) AS value, count(*) AS n")
        .contains("WHERE (c.employee_id = ?)")
        .doesNotContain("GROUP BY")
        .doesNotContain("HAVING");
    assertThat(query.params()).containsExactly(2, ToolArgs.MAX_LIMIT);
  }

  @Test
  void givenUngroupedAggregateWithMinGroupSize_whenCompileAggregate_thenHavingWithoutGroupBy() {
    // Arrange: "average salary in Finance" for the ceo: no group_by, min group size 5
    ToolArgs.AggregateArgs args =
        new ToolArgs.AggregateArgs(
            Fn.AVG,
            "gross_salary",
            null,
            List.of(new Filter("department", Filter.Op.EQ, "Finance")));

    // Act
    CompiledQuery query = compiler.compileAggregate(ENTITY, allRows(5), args);

    // Assert
    assertThat(query.sql())
        .contains("WHERE (e.department = ?)")
        .doesNotContain("GROUP BY")
        .contains("HAVING count(DISTINCT c.employee_id) >= ?");
    assertThat(query.params()).containsExactly("Finance", 5, ToolArgs.MAX_LIMIT);
  }

  @ParameterizedTest
  @EnumSource(
      value = Fn.class,
      names = {"MIN", "MAX"})
  void givenMinGroupSize_whenCompileMinOrMax_thenRefusedBecauseTheValueIsOnePersons(Fn function) {
    // Arrange: the largest salary of a group of five is still one person's salary
    ToolArgs.AggregateArgs args =
        new ToolArgs.AggregateArgs(function, "gross_salary", "department", List.of());

    // Act
    Throwable thrown = catchThrowable(() -> compiler.compileAggregate(ENTITY, allRows(5), args));

    // Assert
    assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("sum");
  }

  @Test
  void givenKnownBusinessKey_whenCompileRead_thenOwnRowIsMarkedSelfAndComesFirst() {
    // Arrange: the ceo sees every row; the asking user's own row must still be recognisable, and
    // under the limit whatever its id
    ToolArgs.ReadArgs args = new ToolArgs.ReadArgs(List.of(), 100);

    // Act
    CompiledQuery query = compiler.compileRead(ENTITY, allRows(null), args, 13);

    // Assert
    assertThat(query.sql())
        .contains("(c.employee_id = ?) AS self")
        .contains(" ORDER BY self DESC, c.employee_id LIMIT ?");
    assertThat(query.columns()).contains("self");
    assertThat(query.params()).containsExactly(13, 100);
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

  @ParameterizedTest
  @CsvSource({"eric, %eric%", "Ma%, Ma%"})
  void givenIlikeValue_whenCompileRead_thenContainsUnlessTheModelWroteWildcards(
      String value, String bound) {
    // Arrange: ilike "eric" means "contains eric"; a value with wildcards is used as written
    ToolArgs.ReadArgs args =
        new ToolArgs.ReadArgs(List.of(new Filter("full_name", Filter.Op.ILIKE, value)), 100);

    // Act
    CompiledQuery query = compiler.compileRead(ENTITY, allRows(null), args, null);

    // Assert
    assertThat(query.sql()).contains("(e.full_name ILIKE ?)");
    assertThat(query.params()).containsExactly(bound, 100);
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
}
