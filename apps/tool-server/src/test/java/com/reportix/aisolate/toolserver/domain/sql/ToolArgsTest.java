package com.reportix.aisolate.toolserver.domain.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import com.reportix.aisolate.sdk.Arguments;
import com.reportix.aisolate.sdk.Filter;
import com.reportix.aisolate.sdk.GovernedQuery.Fn;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** The model's raw arguments shaped by the SDK, then validated against the entity here. */
class ToolArgsTest {

  private static final Set<String> VISIBLE =
      Set.of("full_name", "department", "title", "gross_salary", "currency", "bonus_2025", "iban");

  private static final ToolArgs ARGS = new ToolArgs(TestEntities.HR_COMPENSATION, VISIBLE);

  @Test
  void givenFilterAndLimit_whenRead_thenTypedArgs() {
    // Arrange
    List<Filter> filters =
        Arguments.filters(
            List.of(Map.of("column", "department", "op", "eq", "value", "Engineering")));

    // Act
    ToolArgs.ReadArgs args = ARGS.read(filters, Arguments.limit(10, ToolArgs.MAX_LIMIT));

    // Assert
    assertThat(args.filters())
        .containsExactly(new Filter("department", Filter.Op.EQ, "Engineering"));
    assertThat(args.limit()).isEqualTo(10);
  }

  @Test
  void givenNoArguments_whenRead_thenDefaults() {
    // Arrange
    List<Filter> none = Arguments.filters(null);

    // Act
    ToolArgs.ReadArgs args = ARGS.read(none, Arguments.limit(null, ToolArgs.MAX_LIMIT));

    // Assert
    assertThat(args.filters()).isEmpty();
    assertThat(args.limit()).isEqualTo(ToolArgs.MAX_LIMIT);
  }

  @Test
  void givenLimitAboveMax_whenRead_thenClampedToMax() {
    // Arrange
    int limit = 5000;

    // Act
    ToolArgs.ReadArgs args = ARGS.read(List.of(), limit);

    // Assert
    assertThat(args.limit()).isEqualTo(ToolArgs.MAX_LIMIT);
  }

  static Stream<org.junit.jupiter.params.provider.Arguments> filtersOutsideTheCatalog() {
    return Stream.of(
        arguments(Map.of("column", "internal_notes", "op", "eq", "value", "x"), "column"),
        arguments(Map.of("column", "salary; drop table", "op", "eq", "value", "x"), "column"),
        arguments(Map.of("column", "department", "op", "regex", "value", ".*"), "op"),
        arguments(Map.of("column", "gross_salary", "op", "gt", "value", "lots"), "value"),
        arguments(Map.of("column", "gross_salary", "op", "ilike", "value", "7"), "ilike"));
  }

  @Test
  void givenMoreFiltersThanTheCap_whenRead_thenRejected() {
    // Arrange
    List<Filter> filters =
        Collections.nCopies(
            ToolArgs.MAX_FILTERS + 1, new Filter("department", Filter.Op.EQ, "Sales"));

    // Act
    Throwable thrown = catchThrowable(() -> ARGS.read(filters, 100));

    // Assert
    assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("filters");
  }

  @ParameterizedTest
  @MethodSource("filtersOutsideTheCatalog")
  void givenFilterOutsideTheCatalog_whenRead_thenRejectedNamingTheField(
      Map<String, Object> filter, String field) {
    // Arrange
    List<Map<String, Object>> raw = List.of(filter);

    // Act
    Throwable thrown = catchThrowable(() -> ARGS.read(Arguments.filters(raw), 100));

    // Assert
    assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessageContaining(field);
  }

  @Test
  void givenFiltersThatAreNotAList_whenShaped_thenRejected() {
    // Arrange
    String notAList = "department = x";

    // Act
    Throwable thrown = catchThrowable(() -> Arguments.filters(notAList));

    // Assert
    assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("filters");
  }

  @Test
  void givenAverageOfSalaryByDepartment_whenAggregate_thenTypedArgs() {
    // Arrange
    Fn average = Arguments.function("avg");

    // Act
    ToolArgs.AggregateArgs args = ARGS.aggregate(average, "gross_salary", "department", List.of());

    // Assert
    assertThat(args.function()).isEqualTo(Fn.AVG);
    assertThat(args.column()).isEqualTo("gross_salary");
    assertThat(args.groupBy()).isEqualTo("department");
    assertThat(args.filters()).isEmpty();
  }

  @Test
  void givenAggregateOnMaskedColumn_whenAggregate_thenRejected() {
    // Arrange: the argument-visible set is what production builds: catalog minus hidden minus
    // masked
    Set<String> queryable =
        TestEntities.HR_COMPENSATION.queryableColumns(Set.of("internal_notes"), Set.of("iban"));
    ToolArgs args = new ToolArgs(TestEntities.HR_COMPENSATION, queryable);

    // Act
    Throwable thrown = catchThrowable(() -> args.aggregate(Fn.COUNT, "iban", null, List.of()));

    // Assert
    assertThat(queryable).doesNotContain("iban", "internal_notes").contains("gross_salary");
    assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("column");
  }

  @Test
  void givenAverageOfTextColumn_whenAggregate_thenRejected() {
    // Arrange
    String text = "department";

    // Act
    Throwable thrown = catchThrowable(() -> ARGS.aggregate(Fn.AVG, text, null, List.of()));

    // Assert
    assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("numeric");
  }
}
