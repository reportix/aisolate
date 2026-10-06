package com.acme.sales.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.reportix.aisolate.sdk.Filter;
import com.reportix.aisolate.sdk.GovernedQuery;
import com.reportix.aisolate.sdk.Result;
import com.reportix.aisolate.sdk.Tool;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SalesToolsTest {

  /** Records what a tool asks of the governed query; returns no rows. */
  static final class RecordingQuery implements GovernedQuery {
    final List<String> calls = new ArrayList<>();

    @Override
    public Result read(String entity, List<Filter> filters, int limit) {
      calls.add("read " + entity + " " + filters + " " + limit);
      return null;
    }

    @Override
    public Result aggregate(
        String entity, Fn function, String column, String groupBy, List<Filter> filters) {
      calls.add("aggregate " + entity + " " + function + " " + column);
      return null;
    }
  }

  private final RecordingQuery query = new RecordingQuery();

  @ParameterizedTest
  @ValueSource(strings = {"read_records", "aggregate_records"})
  void givenEntityOfNoPlugin_whenCalled_thenRefusedNamingTheChoicesWithoutAQuery(String name) {
    // Arrange
    Map<String, Object> args =
        Map.of("entity", "clients.payments", "function", "sum", "column", "amount_eur");

    // Act
    Throwable thrown = catchThrowable(() -> tool(name).handler().call(query, args));

    // Assert
    assertThat(thrown)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("unknown entity clients.payments; one of [clients.accounts, clients.invoices]");
    assertThat(query.calls).isEmpty();
  }

  @Test
  void givenLimitAbove20_whenReadRecords_thenAtMost20Rows() {
    // Arrange
    Map<String, Object> args = Map.of("entity", "clients.invoices", "limit", 500);

    // Act
    tool("read_records").handler().call(query, args);

    // Assert
    assertThat(query.calls).containsExactly("read clients.invoices [] 20");
  }

  private static Tool tool(String name) {
    return new SalesTools()
        .tools().stream().filter(t -> t.name().equals(name)).findFirst().orElseThrow();
  }
}
