package com.acme.sales.post_llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;

import com.acme.sales.support.FakeAIsolateManager;
import com.reportix.aisolate.sdk.Answer;
import com.reportix.aisolate.sdk.Evidence;
import com.reportix.aisolate.sdk.Verdict;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class CustomerVerifierTest {

  private static final String NORTHWIND = "Northwind Bank AG";
  private static final String FABRIKAM = "Fabrikam Logistics";
  private static final String TAILSPIN = "Tailspin Air";

  // The evidence as the runtime hands it to a verifier: passages are a JSON list, a tool result is
  // an object.
  private static final Evidence PASSAGES =
      new Evidence(
          "ev-1",
          "SalesDocumentsContextPreparator",
          """
          [{"title": "Master agreement Northwind Bank AG", "section": "Pricing",
            "body": "Increases are capped."}]
          """);

  private static final Evidence INVOICES =
      new Evidence(
          "ev-2",
          "read_records",
          """
          {"entity": "clients.invoices", "row_count": 3,
           "columns": ["invoice_id", "client", "number", "amount_eur"],
           "rows": [
             {"invoice_id": 412, "client": "Northwind Bank AG",
              "number": "INV-2026-0412", "amount_eur": 18400},
             {"invoice_id": 433, "client": "Northwind Bank AG",
              "number": "INV-2026-0433", "amount_eur": 6250},
             {"invoice_id": 388, "client": "Fabrikam Logistics",
              "number": "INV-2026-0388", "amount_eur": 12900}],
           "labels": {"row_key": "invoice_id", "subject": "client", "period": null,
                      "units": {"amount_eur": "EUR"}, "unit_columns": {}, "decimals": {},
                      "masked": [], "function": null}}
          """);

  private static final Evidence ACCOUNTS =
      new Evidence(
          "ev-3",
          "read_records",
          """
          {"entity": "clients.accounts", "row_count": 4,
           "columns": ["account_id", "name", "contract_value_eur"],
           "rows": [
             {"account_id": 1, "name": "Northwind Bank AG", "contract_value_eur": 240000},
             {"account_id": 2, "name": "Contoso Retail", "contract_value_eur": 112800},
             {"account_id": 3, "name": "Fabrikam Logistics", "contract_value_eur": 98000},
             {"account_id": 4, "name": "Tailspin Air", "contract_value_eur": 310000}],
           "labels": {"row_key": "account_id", "subject": "name", "period": null,
                      "units": {"contract_value_eur": "EUR"}, "unit_columns": {}, "decimals": {},
                      "masked": [], "function": null}}
          """);

  private static final Evidence TOTALS =
      new Evidence(
          "ev-4",
          "aggregate_records",
          """
          {"entity": "clients.invoices", "row_count": 2,
           "columns": ["client", "value", "n"],
           "rows": [
             {"client": "Northwind Bank AG", "value": 24650, "n": 2},
             {"client": "Fabrikam Logistics", "value": 12900, "n": 1}],
           "labels": {"row_key": "client", "subject": "client", "period": null,
                      "units": {"value": "EUR"}, "unit_columns": {}, "decimals": {},
                      "masked": [], "function": "sum"}}
          """);

  // A tool the project wrote itself builds its result without labels.
  private static final Evidence OWN_TOOL =
      new Evidence(
          "ev-5",
          "find_notes",
          """
          {"entity": "notes", "row_count": 1, "columns": ["note"],
           "rows": [{"note": "Call"}], "labels": null}
          """);

  // A result that lacks its rows reads as having none.
  private static final Evidence NO_ROWS =
      new Evidence(
          "ev-6",
          "read_records",
          """
          {"entity": "clients.accounts",
           "labels": {"row_key": "account_id", "subject": "name", "period": null,
                      "units": {}, "unit_columns": {}, "decimals": {}, "masked": [],
                      "function": null}}
          """);

  private static final List<Evidence> EVIDENCE =
      List.of(PASSAGES, INVOICES, ACCOUNTS, TOTALS, OWN_TOOL, NO_ROWS);

  private final CustomerVerifier verifier = new CustomerVerifier();
  private final FakeAIsolateManager leo = new FakeAIsolateManager();

  @Test
  void givenNorthwindsOverdueInvoicesAsTable_whenVerified_thenPass() {
    // Arrange
    String table =
        groundingTable(
            List.of(
                List.of(
                    cell("INV-2026-0412", number("412", NORTHWIND)),
                    cell("18,400 EUR", amount("412", NORTHWIND))),
                List.of(
                    cell("INV-2026-0433", number("433", NORTHWIND)),
                    cell("6,250 EUR", amount("433", NORTHWIND)))));
    Answer answer =
        new Answer(
            "Two invoices of Northwind are overdue.",
            List.of(),
            "Which invoices of Northwind are overdue?",
            table);

    // Act
    Verdict verdict = verifier.verify(answer, EVIDENCE, leo);

    // Assert
    assertThat(verdict).isEqualTo(Verdict.pass("every figure is about Northwind Bank AG"));
  }

  @Test
  void givenQuestionNamingNoCustomer_whenVerified_thenPass() {
    // Arrange
    String figure = grounding("18,400 EUR", amount("412", NORTHWIND));
    Answer answer =
        new Answer("18,400 EUR is overdue.", List.of(), "Which invoices are overdue?", figure);

    // Act
    Verdict verdict = verifier.verify(answer, EVIDENCE, leo);

    // Assert
    assertThat(verdict).isEqualTo(Verdict.pass("the question names no customer"));
  }

  @Test
  void givenNoGrounding_whenVerified_thenPass() {
    // Arrange
    Answer answer = new Answer("I don't know", List.of(), "What does Fabrikam owe?", "");

    // Act
    Verdict verdict = verifier.verify(answer, List.of(), leo);

    // Assert
    assertThat(verdict).isEqualTo(Verdict.pass("no figure to check"));
  }

  @Test
  void givenComparisonOfTwoNamedCustomers_whenVerified_thenPassNamingBothSorted() {
    // Arrange
    String percent =
        groundingComputed(
            "144.9%",
            "percent_change",
            List.of(
                operand("98,000 EUR", contractValue("3", FABRIKAM)),
                operand("240,000 EUR", contractValue("1", NORTHWIND))));
    String question = "How much larger is Northwind's contract than Fabrikam's?";
    Answer answer =
        new Answer("Northwind's contract is 144.9% larger.", List.of(), question, percent);

    // Act
    Verdict verdict = verifier.verify(answer, EVIDENCE, leo);

    // Assert
    assertThat(verdict)
        .isEqualTo(Verdict.pass("every figure is about Fabrikam Logistics, Northwind Bank AG"));
  }

  static Stream<Arguments> anotherCustomersFigure() {
    return Stream.of(
        argumentSet(
            "invoice",
            "What does Fabrikam owe?",
            "Fabrikam owes 18,400 EUR.",
            grounding("18,400 EUR", amount("412", NORTHWIND)),
            "the figure 18,400 EUR is about Northwind Bank AG;"
                + " the question asks about Fabrikam Logistics"),
        argumentSet(
            "total of an aggregate",
            "What does Northwind owe in total?",
            "Northwind owes 12,900 EUR.",
            grounding(
                "12,900 EUR",
                source("ev-4", "clients.invoices", "value", FABRIKAM, FABRIKAM, "EUR")),
            "the figure 12,900 EUR is about Fabrikam Logistics;"
                + " the question asks about Northwind Bank AG"),
        argumentSet(
            "operand shown as itself",
            "What does Northwind owe in total?",
            "Northwind owes 31,300 EUR.",
            groundingComputed(
                "31,300 EUR",
                "sum",
                List.of(
                    operand("18,400 EUR", amount("412", NORTHWIND)),
                    operand("12,900 EUR", amount("388", FABRIKAM)))),
            "the figure 12,900 EUR is about Fabrikam Logistics;"
                + " the question asks about Northwind Bank AG"),
        argumentSet(
            "row shown as the figure",
            "What is Fabrikam's contract worth?",
            "Fabrikam's contract is worth 310,000 EUR.",
            groundingPicked(
                "310,000 EUR", "max", "contract_value_eur", contractValue("4", TAILSPIN)),
            "the figure 310,000 EUR is about Tailspin Air;"
                + " the question asks about Fabrikam Logistics"),
        argumentSet(
            "ranked row shown as itself",
            "What are Northwind's two biggest invoices?",
            "Northwind's two biggest invoices are INV-2026-0412 and INV-2026-0388.",
            groundingRanked(
                "INV-2026-0412, INV-2026-0388",
                "top_n",
                "amount_eur",
                List.of(
                    operand("18,400 EUR", amount("412", NORTHWIND)),
                    operand("12,900 EUR", amount("388", FABRIKAM)))),
            "the figure 12,900 EUR is about Fabrikam Logistics;"
                + " the question asks about Northwind Bank AG"),
        argumentSet(
            "table cell",
            "Which invoices of Northwind are overdue?",
            "Two invoices of Northwind are overdue.",
            groundingTable(
                List.of(
                    List.of(cell("18,400 EUR", amount("412", NORTHWIND))),
                    List.of(cell("12,900 EUR", amount("388", FABRIKAM))))),
            "the figure 12,900 EUR is about Fabrikam Logistics;"
                + " the question asks about Northwind Bank AG"));
  }

  @ParameterizedTest
  @MethodSource("anotherCustomersFigure")
  void givenAnotherCustomersFigureOrCell_whenVerified_thenFail(
      String question, String text, String groundingJson, String note) {
    // Arrange
    Answer answer = new Answer(text, List.of(), question, groundingJson);

    // Act
    Verdict verdict = verifier.verify(answer, EVIDENCE, leo);

    // Assert
    assertThat(verdict).isEqualTo(Verdict.fail(note));
  }

  static Stream<Arguments> citingNoCustomer() {
    return Stream.of(
        argumentSet(
            "figure without a subject",
            grounding(
                "24,650 EUR", source("ev-4", "clients.invoices", "value", null, null, "EUR"))),
        argumentSet("no figures or tables", grounded(List.of(), List.of())));
  }

  @ParameterizedTest
  @MethodSource("citingNoCustomer")
  void givenAnswerCitingNoCustomer_whenVerified_thenNoFigureToCheck(String groundingJson) {
    // Arrange
    Answer answer =
        new Answer(
            "Northwind owes 24,650 EUR.",
            List.of(),
            "What does Northwind owe in total?",
            groundingJson);

    // Act
    Verdict verdict = verifier.verify(answer, EVIDENCE, leo);

    // Assert
    assertThat(verdict).isEqualTo(Verdict.pass("no figure to check"));
  }

  // Grounding keeps the source of a mismatch, which can be about another customer all the same.
  static Stream<Arguments> flaggedMismatch() {
    return Stream.of(
        argumentSet("figure", grounding("18,400 EUR", amount("412", NORTHWIND), "mismatch")),
        argumentSet(
            "table cell",
            groundingTable(
                List.of(List.of(cell("18,400 EUR", amount("412", NORTHWIND), "mismatch"))))));
  }

  @ParameterizedTest
  @MethodSource("flaggedMismatch")
  void givenAFigureOrCellFlaggedMismatch_whenVerified_thenItsSourceIsStillJudged(
      String groundingJson) {
    // Arrange
    Answer answer =
        new Answer(
            "Fabrikam owes 18,400 EUR.", List.of(), "What does Fabrikam owe?", groundingJson);

    // Act
    Verdict verdict = verifier.verify(answer, EVIDENCE, leo);

    // Assert
    assertThat(verdict)
        .isEqualTo(
            Verdict.fail(
                "the figure 18,400 EUR is about Northwind Bank AG;"
                    + " the question asks about Fabrikam Logistics"));
  }

  static Stream<Arguments> resemblingName() {
    return Stream.of(
        argumentSet(
            "word that only contains the name", "Air Nordic AG", "Which invoices are fairly late?"),
        argumentSet(
            "leading word of under three letters", "AG Holdings", "Which invoices does AG owe?"));
  }

  @ParameterizedTest
  @MethodSource("resemblingName")
  void givenQuestionThatOnlyResemblesACustomersName_whenVerified_thenItNamesNone(
      String customer, String question) {
    // Arrange
    Evidence other =
        new Evidence(
            "ev-7",
            "read_records",
            """
            {"entity": "clients.accounts", "row_count": 1, "columns": ["account_id", "name"],
             "rows": [{"account_id": 7, "name": "%s"}],
             "labels": {"row_key": null, "subject": "name", "period": null, "units": {},
                        "unit_columns": {}, "decimals": {}, "masked": [], "function": null}}
            """
                .formatted(customer));
    List<Evidence> evidence = Stream.concat(EVIDENCE.stream(), Stream.of(other)).toList();
    Answer answer =
        new Answer(
            "18,400 EUR is overdue.",
            List.of(),
            question,
            grounding("18,400 EUR", amount("412", NORTHWIND)));

    // Act
    Verdict verdict = verifier.verify(answer, evidence, leo);

    // Assert
    assertThat(verdict).isEqualTo(Verdict.pass("the question names no customer"));
  }

  /** Where a cited value is from, as the airlock writes it: what is null is left out. */
  private static Map<String, Object> source(
      String evidence, String entity, String column, String rowKey, String subject, String unit) {
    Map<String, Object> source =
        object(
            "evidence", evidence,
            "entity", entity,
            "column", column,
            "row_key", rowKey,
            "subject", subject,
            "unit", unit);
    source.values().removeIf(Objects::isNull);
    return source;
  }

  private static Map<String, Object> number(String invoiceId, String client) {
    return source("ev-2", "clients.invoices", "number", invoiceId, client, null);
  }

  private static Map<String, Object> amount(String invoiceId, String client) {
    return source("ev-2", "clients.invoices", "amount_eur", invoiceId, client, "EUR");
  }

  private static Map<String, Object> contractValue(String accountId, String name) {
    return source("ev-3", "clients.accounts", "contract_value_eur", accountId, name, "EUR");
  }

  /** One cell of a computation, shown as itself. */
  private static Map<String, Object> operand(String display, Map<String, Object> cited) {
    Map<String, Object> operand = object("display", display);
    operand.putAll(cited);
    return operand;
  }

  private static Map<String, Object> cell(String display, Map<String, Object> cited) {
    return cell(display, cited, "sourced");
  }

  private static Map<String, Object> cell(
      String display, Map<String, Object> cited, String status) {
    return object("display", display, "status", status, "source", cited);
  }

  // The grounded answer, as far as a verifier reads it.

  private static String grounding(String display, Map<String, Object> cited) {
    return grounding(display, cited, "sourced");
  }

  private static String grounding(String display, Map<String, Object> cited, String status) {
    return grounded(List.of(figure(display, status, "source", cited)), List.of());
  }

  private static String groundingComputed(String display, String op, List<Map<String, Object>> of) {
    Map<String, Object> computed = object("op", op, "of", of);
    return grounded(List.of(figure(display, "computed", "computed", computed)), List.of());
  }

  /** A figure computed by picking a row by one of its columns: the row is what it is about. */
  private static String groundingPicked(
      String display, String op, String column, Map<String, Object> row) {
    Map<String, Object> computed = object("op", op, "by", by(column, row), "row", row);
    return grounded(List.of(figure(display, "computed", "computed", computed)), List.of());
  }

  /** A ranking: its figure shows the names, each ranked row is shown as itself. */
  private static String groundingRanked(
      String display, String op, String column, List<Map<String, Object>> rows) {
    Map<String, Object> computed =
        object("op", op, "n", rows.size(), "by", by(column, rows.getFirst()), "rows", rows);
    return grounded(List.of(figure(display, "computed", "computed", computed)), List.of());
  }

  /** The column a ranking or a pick goes by, of the evidence its row is from. */
  private static Map<String, Object> by(String column, Map<String, Object> row) {
    return object("evidence", row.get("evidence"), "entity", row.get("entity"), "column", column);
  }

  private static String groundingTable(List<List<Map<String, Object>>> rows) {
    return grounded(List.of(), List.of(object("id", "t1", "status", "sourced", "rows", rows)));
  }

  private static Map<String, Object> figure(
      String display, String status, String member, Map<String, Object> value) {
    return object("id", "f1", "display", display, "status", status, member, value);
  }

  private static String grounded(List<?> figures, List<?> tables) {
    return json(object("figures", figures, "tables", tables));
  }

  // The SDK reads JSON and does not write it: these two build the fixtures' text.

  private static Map<String, Object> object(Object... pairs) {
    Map<String, Object> members = new LinkedHashMap<>();
    for (int i = 0; i < pairs.length; i += 2) {
      members.put((String) pairs[i], pairs[i + 1]);
    }
    return members;
  }

  // No escaping: the fixtures hold no quote or backslash.
  private static String json(Object value) {
    return switch (value) {
      case Map<?, ?> map ->
          map.entrySet().stream()
              .map(member -> json(member.getKey()) + ":" + json(member.getValue()))
              .collect(Collectors.joining(",", "{", "}"));
      case List<?> list ->
          list.stream().map(CustomerVerifierTest::json).collect(Collectors.joining(",", "[", "]"));
      case String text -> '"' + text + '"';
      case null, default -> String.valueOf(value);
    };
  }
}
