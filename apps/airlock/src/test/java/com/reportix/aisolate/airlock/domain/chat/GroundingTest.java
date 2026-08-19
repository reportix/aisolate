package com.reportix.aisolate.airlock.domain.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** The answer format's parser, its text, values and hashes. */
class GroundingTest {

  private static final JsonMapper JSON =
      JsonMapper.builder().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();

  private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

  private static final String TYPED = "typed in the text, not referenced";

  /** Fact values of one column: the longest first, whole words, three characters or more. */
  private static final Grounding.Rows DRUGS =
      read(
          "ev-1",
          List.of("drug"),
          List.of("drug"),
          Map.of(
              "r1", Map.of("drug", "Insulin"),
              "r2", Map.of("drug", "Insulin glargine"),
              "r3", Map.of("drug", " ab ")));

  /** One row of {@code value}: a read's cell, or with {@code function} a group's aggregate. */
  private static Grounding value(Object value, String unit, Integer decimals, String function) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("department", "Cardiology");
    row.put("value", value);
    return new Grounding(
        List.of(
            new Grounding.Rows(
                "ev-1",
                new GroundedAnswer.Call("aggregate_records", Map.of()),
                "ehr.vitals",
                List.of("department", "value"),
                new ToolEvidence.Labels(
                    "department",
                    null,
                    null,
                    unit == null ? Map.of() : Map.of("value", unit),
                    Map.of(),
                    decimals == null ? Map.of() : Map.of("value", decimals),
                    List.of(),
                    function,
                    List.of()),
                Map.of("r1", row))),
        List.of(),
        "What is it?");
  }

  /** Two amounts in EUR, 1000.00 and 1151.00, their column declaring {@code decimals}. */
  private static Grounding amounts(Integer decimals) {
    // As tool rows arrive: a map that may be asked for a label the result does not have
    Map<String, Object> first = new LinkedHashMap<>(Map.of("amount", new BigDecimal("1000.00")));
    Map<String, Object> second = new LinkedHashMap<>(Map.of("amount", new BigDecimal("1151.00")));
    return new Grounding(
        List.of(
            new Grounding.Rows(
                "ev-1",
                new GroundedAnswer.Call("read_records", Map.of()),
                "sales.contracts",
                List.of("amount"),
                new ToolEvidence.Labels(
                    null,
                    null,
                    null,
                    Map.of("amount", "EUR"),
                    Map.of(),
                    decimals == null ? Map.of() : Map.of("amount", decimals),
                    List.of(),
                    null,
                    List.of()),
                Map.of("r1", first, "r2", second))),
        List.of(),
        "How do they compare?");
  }

  private static Grounding salary(Object value) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("full_name", "Eric Lindholm");
    row.put("gross_salary", value);
    return new Grounding(
        List.of(
            new Grounding.Rows(
                "ev-1",
                new GroundedAnswer.Call("read_records", Map.of()),
                "hr.compensation",
                List.of("full_name", "gross_salary"),
                new ToolEvidence.Labels(
                    null,
                    "full_name",
                    null,
                    Map.of("gross_salary", "EUR"),
                    Map.of(),
                    Map.of(),
                    List.of(),
                    null,
                    List.of()),
                Map.of("r1", row))),
        List.of(),
        "What is my salary?");
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        "78000.00|78,000 EUR|78000",
        "1234.50|1,234.50 EUR|1234.5",
        "1234.567|1,234.567 EUR|1234.567",
        "0|0 EUR|0"
      })
  void givenCellValue_whenCited_thenWrittenInByAIsolateWithItsUnitAndExactValue(
      String held, String display, String value) {
    // Arrange
    Grounding grounding = salary(new BigDecimal(held));

    // Act
    GroundedAnswer answer =
        grounding.ground(
            AnswerFormat.parse(
                "{\"text\":\"{f1}\",\"figures\":[{\"id\":\"f1\",\"cell\":{\"evidence\":\"ev-1\","
                    + "\"row\":\"r1\",\"column\":\"gross_salary\"}}]}"));

    // Assert
    assertThat(answer.figures().getFirst().display()).isEqualTo(display);
    assertThat(answer.figures().getFirst().value()).isEqualTo(value);
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      nullValues = "NULL",
      value = {
        // integers
        "42|NULL|NULL|NULL|42|42",
        "120.0000000000000000|mmHg|NULL|avg|120 mmHg|120",
        // money: the declared decimals all show, zero cents go
        "1234.5|EUR|2|NULL|1,234.50 EUR|1234.5",
        "78000.00|EUR|2|NULL|78,000 EUR|78000",
        "74512.3456789|EUR|2|avg|74,512.35 EUR|74512.3456789",
        "1234.50|EUR|NULL|sum|1,234.50 EUR|1234.5",
        // averages: two decimals without a declared scale
        "119.2500000000000000|mmHg|NULL|avg|119.25 mmHg|119.25",
        "38.25|ng/L|1|NULL|38.3 ng/L|38.25",
        // percentages
        "33.3333333333333333|%|NULL|avg|33.33%|33.3333333333333333",
        "33.5000000000000000|%|NULL|avg|33.5%|33.5",
        "12.345|%|NULL|NULL|12.345%|12.345"
      })
  void givenCellWithManyDecimals_whenCited_thenShownToItsDecimalsAndItsValueKeptExact(
      String held, String unit, Integer decimals, String function, String display, String exact) {
    // Arrange
    Grounding grounding = value(new BigDecimal(held), unit, decimals, function);

    // Act
    GroundedAnswer answer =
        grounding.ground(
            AnswerFormat.parse(
                "{\"text\":\"{f1}\",\"figures\":[{\"id\":\"f1\",\"cell\":{\"evidence\":\"ev-1\","
                    + "\"row\":\"r1\",\"column\":\"value\"}}]}"));

    // Assert
    GroundedAnswer.Figure figure = answer.figures().getFirst();
    assertThat(figure.status()).isEqualTo("sourced");
    assertThat(figure.display()).isEqualTo(display);
    assertThat(figure.value()).isEqualTo(exact);
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      nullValues = "NULL",
      value = {
        "average|2|1,075.50 EUR|1075.5",
        "average|NULL|1,075.5 EUR|1075.5",
        "sum|2|2,151 EUR|2151",
        // a percentage is not in the cells' unit, nor their decimals
        "percent_change|2|15.1%|15.1",
        "divide|NULL|0.87|0.8688097307"
      })
  void givenComputedFigure_whenGrounded_thenShownToTheCellsDecimalsAndItsValueKeptExact(
      String op, Integer decimals, String display, String exact) {
    // Arrange
    Grounding grounding = amounts(decimals);

    // Act
    GroundedAnswer answer =
        grounding.ground(
            AnswerFormat.parse(
                "{\"text\":\"{f1}\",\"figures\":[{\"id\":\"f1\",\"computed\":{\"op\":\""
                    + op
                    + "\",\"of\":[{\"evidence\":\"ev-1\",\"row\":\"r1\",\"column\":\"amount\"},"
                    + "{\"evidence\":\"ev-1\",\"row\":\"r2\",\"column\":\"amount\"}]}}]}"));

    // Assert
    GroundedAnswer.Figure figure = answer.figures().getFirst();
    assertThat(figure.status()).isEqualTo("computed");
    assertThat(figure.display()).isEqualTo(display);
    assertThat(figure.value()).isEqualTo(exact);
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        // an argument the schema does not declare
        "{\"filters\":[{\"column\":\"status\",\"op\":\"eq\",\"value\":\"overdue\"}],"
            + "\"sort\":\"due_on\"}"
            + "|{\"filters\":[{\"column\":\"status\",\"op\":\"eq\",\"value\":\"overdue\"}]}",
        // an empty one, and a limit the two rows did not reach
        "{\"filters\":[],\"group_by\":null,\"column\":\" \",\"limit\":10}|{}",
        // a limit the rows reached may have cut them
        "{\"limit\":2,\"group_by\":\"department\"}|{\"limit\":2,\"group_by\":\"department\"}"
      })
  void givenToolArguments_whenShownInTheCaption_thenOnlyThoseDeclaredAndApplied(
      String sent, String shown) {
    // Arrange
    Set<String> declared = Set.of("filters", "group_by", "column", "limit");

    // Act
    Map<String, Object> applied = ChatExchange.applied(JSON.readValue(sent, MAP), declared, 2);

    // Assert
    assertThat(applied).isEqualTo(JSON.readValue(shown, MAP));
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        // evidence ids the model still writes are no figures
        "Your salary is on file [ev-1].|0",
        // the question's own number
        "What is my salary? Not 2026 alone.|1",
        "Your salary is 78,000 EUR.|1",
        "1. first\\n2. second|0",
        "Invoices INV-2026-0412 and INV-2026-0433.|2"
      })
  void givenTextNotInTheFormat_whenGroundedAsWritten_thenEveryNumberIsUnsourced(
      String text, int unsourced) {
    // Arrange
    Grounding grounding = salary(78000);

    // Act
    GroundedAnswer answer = grounding.raw(text.replace("\\n", "\n"));

    // Assert
    assertThat(answer.figures()).hasSize(unsourced).allMatch(f -> f.status().equals("unsourced"));
    assertThat(answer.answer()).isEqualTo(text.replace("\\n", "\n"));
  }

  @Test
  void givenTypedNumberTheEvidenceHolds_whenGroundedAsWritten_thenUnsourcedNamingTheMatch() {
    // Arrange
    Grounding grounding = salary(new BigDecimal("78000.00"));

    // Act
    GroundedAnswer answer = grounding.raw("Your salary is 78,000 EUR.");

    // Assert: a hint for the reader, never a green
    GroundedAnswer.Figure typed = answer.figures().getFirst();
    assertThat(typed.status()).isEqualTo("unsourced");
    assertThat(typed.reason()).contains("ev-1 r1 gross_salary holds the same value");
    assertThat(answer.provenance())
        .isEqualTo(new GroundedAnswer.Provenance("has_unsourced", 0, 0, 1, 0));
  }

  /** An answer with no placeholder: whatever it states, it typed. */
  private static AnswerFormat.ModelAnswer typed(String text, List<Map<String, Object>> quotes) {
    return AnswerFormat.parse(
        JSON.writeValueAsString(
            Map.of("text", text, "figures", List.of(), "quotes", quotes, "dont_know", false)));
  }

  /** {@code a,b} as a list; null as none. */
  private static List<String> list(String values) {
    return values == null ? List.of() : List.of(values.split(","));
  }

  /** A read of ehr.medications as evidence {@code id}, its rows in handle order. */
  private static Grounding.Rows read(
      String id, List<String> columns, List<String> facts, Map<String, Map<String, Object>> rows) {
    return new Grounding.Rows(
        id,
        new GroundedAnswer.Call("read_medications", Map.of()),
        "ehr.medications",
        columns,
        new ToolEvidence.Labels(
            null, null, null, Map.of(), Map.of(), Map.of(), List.of(), null, facts),
        new TreeMap<>(rows));
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {"{\"facts\":[\"drug\",\"dose\"]}|drug,dose", "{\"facts\":null}|", "{}|"})
  void givenToolResultLabels_whenRead_thenFactsOrEmpty(String labels, String facts) {
    // Arrange: labels as a tool server writes them; an older one writes no facts

    // Act
    ToolEvidence.Labels read = JSON.readValue(labels, ToolEvidence.Labels.class);

    // Assert
    assertThat(read.facts()).isEqualTo(list(facts));
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      quoteCharacter = '"',
      value = {"Is Julian on insulin glargine?|", "Is Julian's insulinoma treated?|insulin"})
  void givenQuestionHoldingTheFactInAPhraseOrAWord_whenTyped_thenExemptOnlyInThePhrase(
      String question, String typed) {
    // Arrange
    AnswerFormat.ModelAnswer answer = typed("Julian takes insulin.", List.of());

    // Act
    GroundedAnswer grounded = new Grounding(List.of(DRUGS), List.of(), question).ground(answer);

    // Assert
    assertThat(grounded.figures())
        .extracting(GroundedAnswer.Figure::display)
        .isEqualTo(list(typed));
  }

  @Test
  void givenValueHeldBySeveralCells_whenTyped_thenTheFirstByResultFactColumnAndRow() {
    // Arrange: brand is ev-1's first fact column, so r2 brand comes before r1 drug
    Grounding.Rows first =
        read(
            "ev-1",
            List.of("drug", "brand"),
            List.of("brand", "drug"),
            Map.of(
                "r1", Map.of("drug", "Aspirin", "brand", "Bayer"),
                "r2", Map.of("drug", "Ibuprofen", "brand", "Aspirin")));
    Grounding.Rows second =
        read(
            "ev-2",
            List.of("drug"),
            List.of("drug"),
            Map.of("r3", Map.of("drug", "Ibuprofen"), "r4", Map.of("drug", "Bayer")));

    // Act
    GroundedAnswer grounded =
        new Grounding(List.of(first, second), List.of(), "What does Julian take?")
            .ground(typed("Aspirin, Ibuprofen, Bayer.", List.of()));

    // Assert
    assertThat(grounded.figures())
        .extracting(GroundedAnswer.Figure::reason)
        .containsExactly(
            TYPED + "; ev-1 r2 brand holds the same value",
            TYPED + "; ev-1 r2 drug holds the same value",
            TYPED + "; ev-1 r1 brand holds the same value");
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        "Julian takes insulin glargine.|insulin glargine",
        "Julian takes Insulin.|Insulin",
        "Julian takes insulinum.|",
        "Julian takes ab.|"
      })
  void givenFactValues_whenTyped_thenTheLongestWholeWordsOfThreeCharactersOrMore(
      String text, String typed) {
    // Arrange: " ab " is under three characters once trimmed, no fact
    AnswerFormat.ModelAnswer answer = typed(text, List.of());

    // Act
    GroundedAnswer grounded =
        new Grounding(List.of(DRUGS), List.of(), "What does Julian take?").ground(answer);

    // Assert
    assertThat(grounded.figures())
        .extracting(GroundedAnswer.Figure::display)
        .isEqualTo(list(typed));
  }

  @Test
  void givenValueOfTwoCharactersOutsideTheBasicPlane_whenTyped_thenNoFact() {
    // Arrange: two characters, four UTF-16 units; characters are counted, as Python counts them
    Grounding grounding =
        new Grounding(
            List.of(
                read(
                    "ev-1",
                    List.of("drug"),
                    List.of("drug"),
                    Map.of("r1", Map.of("drug", "𝔸𝔹")))),
            List.of(),
            "What does Julian take?");

    // Act
    GroundedAnswer grounded = grounding.ground(typed("Julian takes 𝔸𝔹.", List.of()));

    // Assert
    assertThat(grounded.figures()).isEmpty();
  }

  @Test
  void givenDontKnow_whenGrounded_thenExactlyIDontKnowWithNoFigures() {
    // Arrange
    Grounding grounding = salary(78000);

    // Act
    GroundedAnswer answer =
        grounding.ground(AnswerFormat.parse("{\"text\":\"about 70,000\",\"dont_know\":true}"));

    // Assert
    assertThat(answer.answer()).isEqualTo(ChatExchange.I_DONT_KNOW);
    assertThat(answer.provenance().status()).isEqualTo("no_figures");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "Your salary is 78,000 EUR.",
        "[1, 2]",
        "{\"figures\":[]}",
        "{\"text\":\"x\",\"figures\":{}}",
        "{\"text\":\"x\",\"figures\":[{\"cell\":{}}]}"
      })
  void givenTextNotInTheFormat_whenParsed_thenRefusedWithAReasonThatQuotesNothing(String said) {
    // Act
    Throwable thrown = catchThrowable(() -> AnswerFormat.parse(said));

    // Assert: the reason goes back to the model; it never repeats the answer, which holds rows
    assertThat(thrown).isInstanceOf(IllegalArgumentException.class);
    assertThat(thrown.getMessage()).doesNotContain("78,000").doesNotContain("[1, 2]");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{\"table\":{\"evidence\":\"ev-1\",\"rows\":[\"r1\",null],\"columns\":[\"full_name\"]}}",
        "{\"table\":{\"evidence\":\"ev-1\",\"rows\":[\"r1\"],\"columns\":[\"full_name\",null]}}",
        "{\"table\":{\"evidence\":\"ev-1\",\"rows\":[\"r1\"],\"columns\":[{}]}}",
        "{\"table\":{\"evidence\":\"ev-1\",\"rows\":[\"r1\"],\"columns\":[\"full_name\"],"
            + "\"rank\":{\"op\":\"top_n\",\"by\":null}}}",
        "{\"computed\":{\"op\":\"top_n\",\"by\":{\"evidence\":\"ev-1\",\"column\":\"gross_salary\"},"
            + "\"rows\":[null]}}",
        "{\"computed\":{\"op\":\"max\",\"by\":{\"evidence\":\"ev-1\",\"column\":\"gross_salary\"},"
            + "\"rows\":[[\"r1\"]]}}",
        "{\"cell\":{\"evidence\":\"ev-1\",\"row\":null,\"column\":\"gross_salary\"}}"
      })
  void givenReferenceNamingNoRowOrColumn_whenGrounded_thenAMismatchNotAFailure(String reference) {
    // Arrange
    Grounding grounding = salary(78000);

    // Act
    GroundedAnswer answer =
        grounding.ground(
            AnswerFormat.parse(
                "{\"text\":\"{x1}\",\"figures\":[{\"id\":\"x1\"," + reference.substring(1) + "]}"));

    // Assert
    assertThat(answer.provenance())
        .isEqualTo(new GroundedAnswer.Provenance("has_mismatch", 0, 0, 0, 1));
  }

  @Test
  void givenFencedJson_whenParsed_thenReadAsItsContent() {
    // Act
    AnswerFormat.ModelAnswer answer =
        AnswerFormat.parse("```json\n{\"text\":\"Hi.\",\"figures\":[],\"quotes\":[]}\n```");

    // Assert
    assertThat(answer.text()).isEqualTo("Hi.");
  }

  @Test
  void givenTheSameRowsInAnotherKeyOrderAndScale_whenHashed_thenTheSameHash() {
    // Arrange
    Map<String, Object> one = new LinkedHashMap<>();
    one.put("full_name", "Eric Lindholm");
    one.put("gross_salary", new BigDecimal("78000.00"));
    Map<String, Object> other = new LinkedHashMap<>();
    other.put("gross_salary", 78000);
    other.put("full_name", "Eric Lindholm");

    // Act
    String first = ChatExchange.rowsHash(List.of(one));
    String second = ChatExchange.rowsHash(List.of(other));

    // Assert: what an auditor re-running the query compares; a changed value changes it
    assertThat(first).isEqualTo(second).matches("[0-9a-f]{64}");
    assertThat(ChatExchange.rowsHash(List.of(Map.of("gross_salary", 78001)))).isNotEqualTo(first);
  }

  @Test
  void givenRowsAsTheEvidenceHoldsThem_whenHashed_thenTheHashTheAuditorsScriptRecomputes() {
    // Arrange
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("self", true);
    row.put("full_name", "Søren Ørsted");
    row.put("gross_salary", new BigDecimal("78000.00"));
    row.put("bonus", null);
    row.put("year", 2026);
    row.put("rate", new BigDecimal("-0.0"));
    row.put("big", new BigDecimal("1E+3"));

    // Act
    String hash = ChatExchange.rowsHash(List.of(row));

    // Assert
    assertThat(hash).isEqualTo("900634e46048bb5653237677339da64b0a436180fc1662664d9e21292ce5fb8e");
  }
}
