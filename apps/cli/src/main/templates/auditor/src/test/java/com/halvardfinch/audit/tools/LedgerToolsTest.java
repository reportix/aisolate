package com.halvardfinch.audit.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.reportix.aisolate.sdk.Filter;
import com.reportix.aisolate.sdk.GovernedQuery;
import com.reportix.aisolate.sdk.Result;
import com.reportix.aisolate.sdk.Tool;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class LedgerToolsTest {

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
      calls.add("aggregate " + entity + " " + function + " " + column + " by " + groupBy);
      return null;
    }
  }

  private final RecordingQuery query = new RecordingQuery();

  @ParameterizedTest
  @ValueSource(strings = {"read_records", "aggregate_records"})
  void givenEntityOfNoPlugin_whenCalled_thenRefusedNamingTheChoicesWithoutAQuery(String name) {
    // Arrange: another company's books are another airlock's data source, never an entity here
    Map<String, Object> args =
        Map.of("entity", "northbeck.invoices", "function", "sum", "column", "amount_eur");

    // Act
    Throwable thrown = catchThrowable(() -> tool(name).handler().call(query, args));

    // Assert
    assertThat(thrown)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage(
            "unknown entity northbeck.invoices; one of [ledger.journal_entries, ledger.invoices,"
                + " ledger.contracts, ledger.related_parties, ledger.reported_figures]");
    assertThat(query.calls).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(ints = {5, 500})
  void givenAnyLimit_whenReadRecords_thenEveryRowUpTo50(int limit) {
    // Arrange: a limit of 5 asked for "the five largest" read the five oldest, so none is offered
    Map<String, Object> args = Map.of("entity", "ledger.contracts", "limit", limit);

    // Act
    tool("read_records").handler().call(query, args);

    // Assert
    assertThat(query.calls).containsExactly("read ledger.contracts [] 50");
    assertThat(tool("read_records").inputSchema().toString()).doesNotContain("limit");
  }

  @Test
  void givenGroupBy_whenAggregateRecords_thenOneQueryGroupedByIt() {
    // Arrange
    Map<String, Object> args =
        Map.of(
            "entity",
            "ledger.invoices",
            "function",
            "sum",
            "column",
            "amount_eur",
            "group_by",
            "supplier");

    // Act
    tool("aggregate_records").handler().call(query, args);

    // Assert
    assertThat(query.calls).containsExactly("aggregate ledger.invoices SUM amount_eur by supplier");
  }

  @ParameterizedTest
  @CsvSource(
      value = {"null, 10000", "25000, 25000"},
      nullValues = "null")
  void givenThreshold_whenRelatedPartyPayments_thenPaymentsToRelatedPartiesAboveIt(
      Integer threshold, int expected) {
    // Arrange
    Map<String, Object> args = new HashMap<>();
    args.put("min_amount_eur", threshold);

    // Act
    tool("related_party_payments").handler().call(query, args);

    // Assert
    assertThat(query.calls)
        .containsExactly(
            "read ledger.journal_entries [Filter[column=related_party, op=EQ, value=yes],"
                + " Filter[column=entry_type, op=EQ, value=payment],"
                + " Filter[column=amount_eur, op=GT, value="
                + expected
                + "]] 50");
  }

  @Test
  void givenNoArguments_whenInvoicesWithoutContract_thenInvoicesWithNoMatchingContract() {
    // Arrange
    Map<String, Object> args = Map.of();

    // Act
    tool("invoices_without_contract").handler().call(query, args);

    // Assert
    assertThat(query.calls)
        .containsExactly("read ledger.invoices [Filter[column=has_contract, op=EQ, value=no]] 50");
  }

  @Test
  void givenTheAuditTests_whenTheirFilters_thenEachColumnIsOneOfTheEntity() {
    // Arrange: the tool server refuses a filter on a column the entity does not declare
    List<Filter> filters = new ArrayList<>(LedgerTools.relatedPartyPayments(null));

    // Act
    List<String> columns = filters.stream().map(Filter::column).toList();

    // Assert
    assertThat(LedgerTools.JOURNAL_ENTRIES.columns()).containsKeys(columns.toArray(String[]::new));
    assertThat(LedgerTools.INVOICES.columns()).containsKey("has_contract");
  }

  private static Tool tool(String name) {
    return new LedgerTools()
        .tools().stream().filter(t -> t.name().equals(name)).findFirst().orElseThrow();
  }
}
