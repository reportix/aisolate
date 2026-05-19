package com.reportix.aisolate.sdk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.util.List;
import org.junit.jupiter.api.Test;

class ColumnTest {

  @Test
  void givenNumberColumn_whenMarkedAFact_thenIllegalArgument() {
    // Arrange
    Column salary = new Column("salary", "c.salary", Column.Type.NUMBER);

    // Act
    Throwable built =
        catchThrowable(
            () -> new Column("salary", "c.salary", Column.Type.NUMBER, null, null, null, true));
    Throwable marked = catchThrowable(salary::asFact);

    // Assert: every typed number is checked anyway
    assertThat(List.of(built, marked))
        .allSatisfy(
            refused ->
                assertThat(refused)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("column salary is a number: only a text column can be a fact"));
  }

  @Test
  void givenTextColumn_whenAsFact_thenAFactThroughEveryWitherAndNothingElseChanged() {
    // Arrange
    Column drug = new Column("drug", "m.drug", Column.Type.TEXT);

    // Act
    Column fact = drug.asFact();

    // Assert
    assertThat(drug.fact()).isFalse();
    assertThat(fact)
        .isEqualTo(new Column("drug", "m.drug", Column.Type.TEXT, null, null, null, true));
    assertThat(List.of(fact.withUnit("mg"), fact.withUnitFrom("unit"), fact.withDecimals(1)))
        .allMatch(Column::fact);
  }

  @Test
  void givenTheConstructorBeforeFacts_whenAColumnIsBuilt_thenItIsNoFact() {
    // Arrange: the six arguments a plugin compiled against the SDK before facts passes

    // Act
    Column column = new Column("amount", "c.amount", Column.Type.NUMBER, "EUR", null, 2);

    // Assert
    assertThat(column)
        .isEqualTo(new Column("amount", "c.amount", Column.Type.NUMBER, "EUR", null, 2, false));
  }
}
