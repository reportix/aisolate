package com.halvardfinch.audit.post_llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;

import com.halvardfinch.audit.support.FakeAIsolateManager;
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
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class PeriodVerifierTest {

  private static final String HARBOUR_LANE = "Harbour Lane Properties GmbH";
  private static final String FINCH = "Finch Advisory UG";

  // The evidence as the runtime hands it to a verifier: passages are a JSON list, a tool result is
  // an object.
  private static final Evidence PASSAGES =
      new Evidence(
          "ev-1",
          "AuditDocumentsContextPreparator",
          """
          [{"title": "Related party policy", "section": "Approval",
            "body": "Payments to related parties above 10,000 EUR need the board's approval."}]
          """);

  private static final Evidence PAYMENTS =
      new Evidence(
          "ev-2",
          "related_party_payments",
          """
          {"entity": "ledger.journal_entries", "row_count": 3,
           "columns": ["entry_id", "entry_date", "counterparty", "amount_eur"],
           "rows": [
             {"entry_id": 1, "entry_date": "2025-10-03",
              "counterparty": "Harbour Lane Properties GmbH", "amount_eur": 48500},
             {"entry_id": 7, "entry_date": "2025-11-20",
              "counterparty": "Finch Advisory UG", "amount_eur": 22000},
             {"entry_id": 12, "entry_date": "2026-01-15",
              "counterparty": "Harbour Lane Properties GmbH", "amount_eur": 48500}],
           "labels": {"row_key": "entry_id", "subject": "counterparty", "period": "entry_date",
                      "units": {"amount_eur": "EUR"}, "unit_columns": {}, "decimals": {},
                      "masked": [], "function": null}}
          """);

  private static final Evidence FIGURES =
      new Evidence(
          "ev-3",
          "read_records",
          """
          {"entity": "ledger.reported_figures", "row_count": 3,
           "columns": ["figure_id", "concept", "label", "period", "value_eur"],
           "rows": [
             {"figure_id": "ifrs-full:Revenue 2025", "concept": "ifrs-full:Revenue",
              "label": "Revenue", "period": "2025", "value_eur": 12400000},
             {"figure_id": "ifrs-full:Revenue 2024", "concept": "ifrs-full:Revenue",
              "label": "Revenue", "period": "2024", "value_eur": 11100000},
             {"figure_id": "ifrs-full:ProfitLoss 2025", "concept": "ifrs-full:ProfitLoss",
              "label": "Profit for the year", "period": "2025", "value_eur": 860000}],
           "labels": {"row_key": "figure_id", "subject": null, "period": "period",
                      "units": {"value_eur": "EUR"}, "unit_columns": {}, "decimals": {},
                      "masked": [], "function": null}}
          """);

  // The seeded books write a fiscal year.
  private static final Evidence FISCAL_FIGURES =
      new Evidence(
          "ev-4",
          "read_records",
          """
          {"entity": "ledger.reported_figures", "row_count": 1,
           "columns": ["figure_id", "concept", "label", "period", "value_eur"],
           "rows": [
             {"figure_id": "ifrs-full:Revenue FY2025", "concept": "ifrs-full:Revenue",
              "label": "Revenue", "period": "FY2025", "value_eur": 12400000}],
           "labels": {"row_key": "figure_id", "subject": null, "period": "period",
                      "units": {"value_eur": "EUR"}, "unit_columns": {}, "decimals": {},
                      "masked": [], "function": null}}
          """);

  private static final List<Evidence> EVIDENCE =
      List.of(PASSAGES, PAYMENTS, FIGURES, FISCAL_FIGURES);

  private final PeriodVerifier verifier = new PeriodVerifier();
  private final FakeAIsolateManager hannah = new FakeAIsolateManager();

  @Test
  void givenLastYearsRevenueForThisYearsQuestion_whenVerified_thenFail() {
    // Arrange
    Answer answer =
        new Answer(
            "Northbeck reported revenue of 11,100,000 EUR for 2025.",
            List.of(),
            "What revenue did Northbeck report for 2025?",
            grounding("11,100,000 EUR", reported("ev-3", "ifrs-full:Revenue 2024", "2024")));

    // Act
    Verdict verdict = verifier.verify(answer, EVIDENCE, hannah);

    // Assert
    assertThat(verdict)
        .isEqualTo(
            Verdict.fail("the figure 11,100,000 EUR is for 2024; the question asks about 2025"));
  }

  @Test
  void givenRevenueOfTheYearAsked_whenVerified_thenPass() {
    // Arrange
    Answer answer =
        new Answer(
            "Northbeck reported revenue of 12,400,000 EUR for 2025.",
            List.of(),
            "What revenue did Northbeck report for 2025?",
            grounding("12,400,000 EUR", reported("ev-3", "ifrs-full:Revenue 2025", "2025")));

    // Act
    Verdict verdict = verifier.verify(answer, EVIDENCE, hannah);

    // Assert
    assertThat(verdict).isEqualTo(Verdict.pass("every figure is for 2025"));
  }

  // A period is read by its year, the first in it, whatever else it says.
  static Stream<Arguments> periodOfTheYearAsked() {
    return Stream.of(
        argumentSet(
            "fiscal year",
            "What revenue did Northbeck report for 2025?",
            grounding("12,400,000 EUR", reported("ev-4", "ifrs-full:Revenue FY2025", "FY2025")),
            "2025"),
        argumentSet(
            "date",
            "What was paid on 2025-10-03?",
            grounding("48,500 EUR", amount("1", HARBOUR_LANE, "2025-10-03")),
            "2025"),
        argumentSet(
            "date and time",
            "What was paid in 2025?",
            grounding("48,500 EUR", amount("1", HARBOUR_LANE, "2025-10-03 06:10")),
            "2025"),
        argumentSet(
            "year named twice",
            "What was paid in 2025, and on 2025-10-03?",
            grounding("48,500 EUR", amount("1", HARBOUR_LANE, "2025-10-03")),
            "2025"),
        argumentSet(
            "span of two years",
            "What revenue did Northbeck report for 2025?",
            grounding("12,400,000 EUR", reported("ev-3", "ifrs-full:Revenue 2025", "2025/2026")),
            "2025"),
        argumentSet(
            "year before a word starting eur",
            "What was paid in 2025 European sales?",
            grounding("48,500 EUR", amount("1", HARBOUR_LANE, "2025-10-03")),
            "2025"),
        argumentSet(
            "year before a word starting usd",
            "What was paid in 2025 USDA fees?",
            grounding("48,500 EUR", amount("1", HARBOUR_LANE, "2025-10-03")),
            "2025"),
        argumentSet(
            "year of the 1900s",
            "What revenue did Northbeck report for 1999?",
            grounding("9,800,000 EUR", reported("ev-3", "ifrs-full:Revenue 1999", "1999")),
            "1999"));
  }

  @ParameterizedTest
  @MethodSource("periodOfTheYearAsked")
  void givenPeriodHoldingTheYearAsked_whenVerified_thenPass(
      String question, String groundingJson, String year) {
    // Arrange
    Answer answer = new Answer("See the figure.", List.of(), question, groundingJson);

    // Act
    Verdict verdict = verifier.verify(answer, EVIDENCE, hannah);

    // Assert
    assertThat(verdict).isEqualTo(Verdict.pass("every figure is for " + year));
  }

  @Test
  void givenQuestionNamingNoPeriod_whenVerified_thenPass() {
    // Arrange
    String figure = grounding("48,500 EUR", amount("12", HARBOUR_LANE, "2026-01-15"));
    Answer answer =
        new Answer(
            "Harbour Lane was paid 48,500 EUR.",
            List.of(),
            "What was paid to Harbour Lane?",
            figure);

    // Act
    Verdict verdict = verifier.verify(answer, EVIDENCE, hannah);

    // Assert
    assertThat(verdict).isEqualTo(Verdict.pass("the question names no period"));
  }

  // Four digits are a year when they are one of the 1900s or 2000s and not part of a longer number;
  // an amount or a rate is no year either.
  @ParameterizedTest
  @ValueSource(
      strings = {
        "Which entry is 12025?",
        "Which entry is 20250?",
        "What was paid in 1850?",
        "What was paid in 2150?",
        "Which related-party payments exceeded 2000 EUR?",
        "Which related-party payments exceeded 2000 eur?",
        "Which related-party payments exceeded 2000 USD?",
        "Which related-party payments exceeded 2000 \u20AC?",
        "Which related-party payments exceeded \u20AC2000?",
        "Which related-party payments exceeded $2000?",
        "Which related-party payments exceeded 2000$?",
        "Which related-party payments grew by more than 2000%?"
      })
  void givenDigitsThatAreNoYearInTheQuestion_whenVerified_thenItNamesNoPeriod(String question) {
    // Arrange: a 2025 figure, which a question read as asking about 2000 or 2026 would fail
    String figure = grounding("48,500 EUR", amount("1", HARBOUR_LANE, "2025-10-03"));
    Answer answer = new Answer("48,500 EUR was paid to Harbour Lane.", List.of(), question, figure);

    // Act
    Verdict verdict = verifier.verify(answer, EVIDENCE, hannah);

    // Assert
    assertThat(verdict).isEqualTo(Verdict.pass("the question names no period"));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "Which payments above \u20AC2025 were made in 2025?",
        "Which payments above 2000 EUR were made in 2025?"
      })
  void givenAnAmountAndAYearInTheQuestion_whenVerified_thenOnlyTheYearIsAsked(String question) {
    // Arrange
    String figure = grounding("48,500 EUR", amount("12", HARBOUR_LANE, "2026-01-15"));
    Answer answer = new Answer("48,500 EUR was paid to Harbour Lane.", List.of(), question, figure);

    // Act
    Verdict verdict = verifier.verify(answer, EVIDENCE, hannah);

    // Assert
    assertThat(verdict)
        .isEqualTo(
            Verdict.fail("the figure 48,500 EUR is for 2026-01-15; the question asks about 2025"));
  }

  @Test
  void givenPaymentsOfTheYearAskedInATable_whenVerified_thenPass() {
    // Arrange
    String table =
        groundingTable(
            List.of(
                List.of(cell("48,500 EUR", amount("1", HARBOUR_LANE, "2025-10-03"))),
                List.of(cell("22,000 EUR", amount("7", FINCH, "2025-11-20")))));
    Answer answer =
        new Answer(
            "These payments were made:", List.of(), "Which payments were made in 2025?", table);

    // Act
    Verdict verdict = verifier.verify(answer, EVIDENCE, hannah);

    // Assert
    assertThat(verdict).isEqualTo(Verdict.pass("every figure is for 2025"));
  }

  // Whether or not the question names a period: without grounding there is nothing to read.
  @ParameterizedTest
  @ValueSource(
      strings = {"What revenue did Northbeck report for 2025?", "What was paid to Harbour Lane?"})
  void givenNoGrounding_whenVerified_thenNoFigureToCheck(String question) {
    // Arrange
    Answer answer = new Answer("I don't know", List.of(), question, "");

    // Act
    Verdict verdict = verifier.verify(answer, List.of(), hannah);

    // Assert
    assertThat(verdict).isEqualTo(Verdict.pass("no figure to check"));
  }

  @Test
  void givenTwoYearsAsked_whenVerified_thenPassNamingBothSorted() {
    // Arrange
    String table =
        groundingTable(
            List.of(
                List.of(cell("12,400,000 EUR", reported("ev-3", "ifrs-full:Revenue 2025", "2025"))),
                List.of(
                    cell("11,100,000 EUR", reported("ev-3", "ifrs-full:Revenue 2024", "2024")))));
    Answer answer =
        new Answer(
            "The revenue by year:", List.of(), "What was the revenue in 2025 and in 2024?", table);

    // Act
    Verdict verdict = verifier.verify(answer, EVIDENCE, hannah);

    // Assert
    assertThat(verdict).isEqualTo(Verdict.pass("every figure is for 2024, 2025"));
  }

  static Stream<Arguments> anotherPeriodsCell() {
    return Stream.of(
        argumentSet(
            "table cell",
            "Which payments were made in 2025?",
            groundingTable(
                List.of(
                    List.of(cell("48,500 EUR", amount("1", HARBOUR_LANE, "2025-10-03"))),
                    List.of(cell("22,000 EUR", amount("7", FINCH, "2025-11-20"))),
                    List.of(cell("48,500 EUR", amount("12", HARBOUR_LANE, "2026-01-15")))))),
        argumentSet(
            "row shown as the figure",
            "What was the largest payment in 2025?",
            groundingPicked(
                "48,500 EUR", "max", "amount_eur", amount("12", HARBOUR_LANE, "2026-01-15"))),
        argumentSet(
            "ranked row shown as itself",
            "What were the two largest payments in 2025?",
            groundingRanked(
                "Harbour Lane Properties GmbH, Harbour Lane Properties GmbH",
                "top_n",
                "amount_eur",
                List.of(
                    operand("48,500 EUR", amount("1", HARBOUR_LANE, "2025-10-03")),
                    operand("48,500 EUR", amount("12", HARBOUR_LANE, "2026-01-15"))))));
  }

  @ParameterizedTest
  @MethodSource("anotherPeriodsCell")
  void givenAnotherPeriodsCellInATablePickedOrRanked_whenVerified_thenFail(
      String question, String groundingJson) {
    // Arrange
    Answer answer = new Answer("See the figure.", List.of(), question, groundingJson);

    // Act
    Verdict verdict = verifier.verify(answer, EVIDENCE, hannah);

    // Assert
    assertThat(verdict)
        .isEqualTo(
            Verdict.fail("the figure 48,500 EUR is for 2026-01-15; the question asks about 2025"));
  }

  // A growth or a difference is between periods: the operands of a computation are not judged.
  static Stream<Arguments> acrossPeriods() {
    return Stream.of(
        argumentSet(
            "growth",
            "How much did revenue grow in 2025?",
            groundingComputed(
                "11.71%",
                "percent_change",
                List.of(
                    operand("11,100,000 EUR", reported("ev-3", "ifrs-full:Revenue 2024", "2024")),
                    operand(
                        "12,400,000 EUR", reported("ev-3", "ifrs-full:Revenue 2025", "2025"))))),
        argumentSet(
            "difference",
            "By how much did revenue grow in 2025?",
            groundingComputed(
                "1,300,000 EUR",
                "subtract",
                List.of(
                    operand("12,400,000 EUR", reported("ev-3", "ifrs-full:Revenue 2025", "2025")),
                    operand(
                        "11,100,000 EUR", reported("ev-3", "ifrs-full:Revenue 2024", "2024"))))));
  }

  @ParameterizedTest
  @MethodSource("acrossPeriods")
  void givenComputationAcrossPeriods_whenVerified_thenItsOperandsAreNotJudged(
      String question, String groundingJson) {
    // Arrange
    Answer answer = new Answer("See the figure.", List.of(), question, groundingJson);

    // Act
    Verdict verdict = verifier.verify(answer, EVIDENCE, hannah);

    // Assert
    assertThat(verdict).isEqualTo(Verdict.pass("no figure to check"));
  }

  // The years a computation spans are expected too: "from 2024 to 2025" shows the 2024 cell.
  @Test
  void givenGrowthShownWithItsCells_whenVerified_thenTheYearsItSpansAreExpected() {
    // Arrange
    Map<String, Object> y2024 = reported("ev-3", "ifrs-full:Revenue 2024", "2024");
    Map<String, Object> y2025 = reported("ev-3", "ifrs-full:Revenue 2025", "2025");
    String shown =
        grounded(
            List.of(
                figure("f1", "11,100,000 EUR", "sourced", "source", y2024),
                figure("f2", "12,400,000 EUR", "sourced", "source", y2025),
                growth("f3", y2024, y2025)),
            List.of());
    Answer answer =
        new Answer(
            "Revenue grew from 11,100,000 EUR to 12,400,000 EUR, a rise of 11.71%.",
            List.of(), "How much did revenue grow in 2025?", shown);

    // Act
    Verdict verdict = verifier.verify(answer, EVIDENCE, hannah);

    // Assert
    assertThat(verdict).isEqualTo(Verdict.pass("every figure is for 2025"));
  }

  @Test
  void givenGrowthAndAFigureOfAnotherYear_whenVerified_thenFailNamingOnlyTheYearsAsked() {
    // Arrange
    String shown =
        grounded(
            List.of(
                figure(
                    "f1",
                    "10,200,000 EUR",
                    "sourced",
                    "source",
                    reported("ev-3", "ifrs-full:Revenue 2023", "2023")),
                growth(
                    "f2",
                    reported("ev-3", "ifrs-full:Revenue 2024", "2024"),
                    reported("ev-3", "ifrs-full:Revenue 2025", "2025"))),
            List.of());
    Answer answer =
        new Answer(
            "Revenue was 10,200,000 EUR in 2023 and grew by 11.71% in 2025.",
            List.of(), "How much did revenue grow in 2025?", shown);

    // Act
    Verdict verdict = verifier.verify(answer, EVIDENCE, hannah);

    // Assert
    assertThat(verdict)
        .isEqualTo(
            Verdict.fail("the figure 10,200,000 EUR is for 2023; the question asks about 2025"));
  }

  // Only a tool result of this exchange can say what a cell is for, an operand's included.
  @ParameterizedTest
  @ValueSource(strings = {"ev-1", "ev-9"})
  void givenGrowthOfACellOfNoToolResult_whenVerified_thenItsYearIsNotExpected(String evidenceId) {
    // Arrange
    String shown =
        grounded(
            List.of(
                figure(
                    "f1",
                    "11,100,000 EUR",
                    "sourced",
                    "source",
                    reported("ev-3", "ifrs-full:Revenue 2024", "2024")),
                growth(
                    "f2",
                    reported(evidenceId, "ifrs-full:Revenue 2024", "2024"),
                    reported("ev-3", "ifrs-full:Revenue 2025", "2025"))),
            List.of());
    Answer answer =
        new Answer(
            "Revenue was 11,100,000 EUR in 2024 and grew by 11.71% in 2025.",
            List.of(), "How much did revenue grow in 2025?", shown);

    // Act
    Verdict verdict = verifier.verify(answer, EVIDENCE, hannah);

    // Assert
    assertThat(verdict)
        .isEqualTo(
            Verdict.fail("the figure 11,100,000 EUR is for 2024; the question asks about 2025"));
  }

  // Grounding keeps the source of a mismatch, which can be about another period all the same.
  static Stream<Arguments> flaggedMismatch() {
    Map<String, Object> revenue2024 = reported("ev-3", "ifrs-full:Revenue 2024", "2024");
    return Stream.of(
        argumentSet("figure", grounding("11,100,000 EUR", revenue2024, "mismatch")),
        argumentSet(
            "table cell",
            groundingTable(List.of(List.of(cell("11,100,000 EUR", revenue2024, "mismatch"))))));
  }

  @ParameterizedTest
  @MethodSource("flaggedMismatch")
  void givenAFigureOrCellFlaggedMismatch_whenVerified_thenItsSourceIsStillJudged(
      String groundingJson) {
    // Arrange
    Answer answer =
        new Answer(
            "Northbeck reported 11,100,000 EUR for 2025.",
            List.of(),
            "What revenue did Northbeck report for 2025?",
            groundingJson);

    // Act
    Verdict verdict = verifier.verify(answer, EVIDENCE, hannah);

    // Assert
    assertThat(verdict)
        .isEqualTo(
            Verdict.fail("the figure 11,100,000 EUR is for 2024; the question asks about 2025"));
  }

  // What is not a source with a year judges nothing: a contract has no period, a quarter names no
  // year, and no run of digits is one.
  @ParameterizedTest
  @NullSource
  @ValueSource(strings = {"Q3", "20251003", "2150"})
  void givenCitedPeriodNamingNoYear_whenVerified_thenNoFigureToCheck(String period) {
    // Arrange
    Map<String, Object> source =
        source("ev-2", "ledger.journal_entries", "amount_eur", "12", HARBOUR_LANE, period, "EUR");
    Answer answer =
        new Answer(
            "Harbour Lane was paid 48,500 EUR.",
            List.of(),
            "What was paid to Harbour Lane in 2025?",
            grounding("48,500 EUR", source));

    // Act
    Verdict verdict = verifier.verify(answer, EVIDENCE, hannah);

    // Assert
    assertThat(verdict).isEqualTo(Verdict.pass("no figure to check"));
  }

  static Stream<Arguments> citingNoSource() {
    Map<String, Object> typed =
        object(
            "id",
            "u1",
            "display",
            "12",
            "status",
            "unsourced",
            "reason",
            "typed in the text, not referenced");
    return Stream.of(
        argumentSet("no figures or tables", grounded(List.of(), List.of())),
        argumentSet("unsourced figure", grounded(List.of(typed), List.of())));
  }

  @ParameterizedTest
  @MethodSource("citingNoSource")
  void givenAnswerCitingNoSource_whenVerified_thenNoFigureToCheck(String groundingJson) {
    // Arrange
    Answer answer =
        new Answer(
            "The policy says: ...",
            List.of(),
            "What does the policy say about 2025?",
            groundingJson);

    // Act
    Verdict verdict = verifier.verify(answer, EVIDENCE, hannah);

    // Assert
    assertThat(verdict).isEqualTo(Verdict.pass("no figure to check"));
  }

  // A passage is no tool result, and ev-9 is not of this exchange: neither says what a row is for.
  @ParameterizedTest
  @ValueSource(strings = {"ev-1", "ev-9"})
  void givenSourceOfAnEvidenceThatIsNoToolResult_whenVerified_thenNoFigureToCheck(
      String evidenceId) {
    // Arrange
    Answer answer =
        new Answer(
            "Northbeck reported revenue of 11,100,000 EUR for 2025.",
            List.of(),
            "What revenue did Northbeck report for 2025?",
            grounding("11,100,000 EUR", reported(evidenceId, "ifrs-full:Revenue 2024", "2024")));

    // Act
    Verdict verdict = verifier.verify(answer, EVIDENCE, hannah);

    // Assert
    assertThat(verdict).isEqualTo(Verdict.pass("no figure to check"));
  }

  /** Where a cited value is from, as the airlock writes it: what is null is left out. */
  private static Map<String, Object> source(
      String evidence,
      String entity,
      String column,
      String rowKey,
      String subject,
      String period,
      String unit) {
    Map<String, Object> source =
        object(
            "evidence", evidence,
            "entity", entity,
            "column", column,
            "row_key", rowKey,
            "subject", subject,
            "period", period,
            "unit", unit);
    source.values().removeIf(Objects::isNull);
    return source;
  }

  private static Map<String, Object> reported(String evidence, String figureId, String period) {
    return source(evidence, "ledger.reported_figures", "value_eur", figureId, null, period, "EUR");
  }

  private static Map<String, Object> amount(String entryId, String counterparty, String date) {
    return source(
        "ev-2", "ledger.journal_entries", "amount_eur", entryId, counterparty, date, "EUR");
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
    return figure("f1", display, status, member, value);
  }

  private static Map<String, Object> figure(
      String id, String display, String status, String member, Map<String, Object> value) {
    return object("id", id, "display", display, "status", status, member, value);
  }

  /** The growth from one cell to another, computed by AIsolate: the cells are its operands. */
  private static Map<String, Object> growth(
      String id, Map<String, Object> from, Map<String, Object> to) {
    Map<String, Object> computed =
        object(
            "op",
            "percent_change",
            "of",
            List.of(operand("11,100,000 EUR", from), operand("12,400,000 EUR", to)));
    return figure(id, "11.71%", "computed", "computed", computed);
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
          list.stream().map(PeriodVerifierTest::json).collect(Collectors.joining(",", "[", "]"));
      case String text -> '"' + text + '"';
      case null, default -> String.valueOf(value);
    };
  }
}
