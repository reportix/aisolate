package com.reportix.aisolate.toolserver.domain.sql;

import static org.assertj.core.api.Assertions.assertThat;

import com.reportix.aisolate.sdk.Arguments;
import com.reportix.aisolate.sdk.Filter;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

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
}
