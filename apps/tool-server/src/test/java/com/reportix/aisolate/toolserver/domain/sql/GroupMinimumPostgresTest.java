package com.reportix.aisolate.toolserver.domain.sql;

import static org.assertj.core.api.Assertions.assertThat;

import com.reportix.aisolate.sdk.Column;
import com.reportix.aisolate.sdk.Entity;
import com.reportix.aisolate.sdk.Filter;
import com.reportix.aisolate.sdk.GovernedQuery.Fn;
import com.reportix.aisolate.toolserver.domain.rules.Obligations;
import com.reportix.aisolate.toolserver.support.Containers;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** The minimum group size against the seeded database: it counts people, never rows. */
class GroupMinimumPostgresTest {

  /** One row per objective, each about an employee; employee 2 has three. */
  private static final Entity OBJECTIVES =
      new Entity(
              "hr.objectives",
              "hr.objectives o",
              "objective_id",
              new Column("objective_id", "o.id", Column.Type.NUMBER),
              new Column("employee_id", "o.employee_id", Column.Type.NUMBER),
              new Column("progress_pct", "o.progress_pct", Column.Type.NUMBER))
          .withSubject("employee_id");

  @ParameterizedTest
  @CsvSource({
    // Three objectives of one person: a group of one
    "2, 0",
    // Every objective, five people
    "0, 1"
  })
  void givenMinimumOfThree_whenAverageProgressOverObjectives_thenOnlyThreePeopleOrMoreAnswer(
      int employee, int expectedRows) throws Exception {
    // Arrange
    Obligations obligations =
        new Obligations(true, List.of(), Set.of(), Set.of(), 3, List.of("leadership"));
    List<Filter> filters =
        employee == 0 ? List.of() : List.of(new Filter("employee_id", Filter.Op.EQ, employee));
    CompiledQuery query =
        new SqlCompiler()
            .compileAggregate(
                OBJECTIVES,
                obligations,
                new ToolArgs.AggregateArgs(Fn.AVG, "progress_pct", null, filters));

    // Act
    int rows = Containers.rows(query).size();

    // Assert
    assertThat(rows).isEqualTo(expectedRows);
  }
}
