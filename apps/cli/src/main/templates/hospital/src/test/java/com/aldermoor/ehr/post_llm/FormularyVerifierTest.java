package com.aldermoor.ehr.post_llm;

import static com.aldermoor.ehr.support.GroundingJson.cell;
import static com.aldermoor.ehr.support.GroundingJson.figure;
import static com.aldermoor.ehr.support.GroundingJson.grounded;
import static com.aldermoor.ehr.support.GroundingJson.object;
import static com.aldermoor.ehr.support.GroundingJson.quote;
import static com.aldermoor.ehr.support.GroundingJson.source;
import static com.aldermoor.ehr.support.GroundingJson.table;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;

import com.aldermoor.ehr.support.FakeAIsolateManager;
import com.aldermoor.ehr.support.RecordingDataSource;
import com.reportix.aisolate.sdk.AirlockRef;
import com.reportix.aisolate.sdk.Answer;
import com.reportix.aisolate.sdk.Evidence;
import com.reportix.aisolate.sdk.Principal;
import com.reportix.aisolate.sdk.Verdict;
import java.lang.reflect.Proxy;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class FormularyVerifierTest {

  private static final String JULIAN = "Julian Marek";

  // SELECT DISTINCT drug FROM ehr.medications: the one query the verifier asks, and nothing else.
  private static final String FORMULARY_QUERY = "SELECT DISTINCT drug FROM ehr.medications";

  // The formulary, one row a drug, Metformin among them although nobody of this record takes it.
  private static final List<Object[]> FORMULARY =
      List.of(
          new Object[] {"Apixaban"},
          new Object[] {"Aspirin"},
          new Object[] {"Sertraline"},
          new Object[] {"Metformin"});

  // read_medications as the tool server returns it for Julian, the golden set's ev-3.
  private static final Evidence MEDICATIONS =
      medications(
          medication(41, "Apixaban", "Anticoagulant", "5 mg", "twice daily", "cardiology"),
          medication(42, "Aspirin", "Antiplatelet", "100 mg", "once daily", "cardiology"),
          medication(43, "Sertraline", "SSRI", "50 mg", "once daily", "psychiatry"));

  // The golden clinical note, a passage: passages are a JSON list, no record the tools returned.
  private static final String NOTE_WORDS = "Continue apixaban 5 mg twice daily.";
  private static final Evidence NOTE =
      passages("Chest pain resolved overnight; troponin trending down. " + NOTE_WORDS);
  private static final Evidence NOTE_ON_METFORMIN = passages("Metformin stopped.");

  private static final Map<String, Object> APIXABAN =
      source("ev-3", "ehr.medications", "drug", "41", JULIAN, "2026-09-30", null);
  private static final Map<String, Object> APIXABAN_DOSE =
      source("ev-3", "ehr.medications", "dose", "41", JULIAN, "2026-09-30", null);
  private static final Map<String, Object> ASPIRIN =
      source("ev-3", "ehr.medications", "drug", "42", JULIAN, "2026-09-30", null);

  // The cells grounding writes in carry the drugs of the records, but are not the answer's words.
  private static final Map<String, Object> DOSE = figure("f1", "5 mg", APIXABAN_DOSE);
  private static final Map<String, Object> DRUGS =
      table(List.of(List.of(cell("Apixaban", APIXABAN)), List.of(cell("Aspirin", ASPIRIN))));

  private static final Pattern PLACEHOLDER = Pattern.compile("\\{([ft]\\d+)}");

  private final FormularyVerifier verifier = new FormularyVerifier();
  private final RecordingDataSource ehr = new RecordingDataSource(FORMULARY);
  private final FakeAIsolateManager noah = noahWith(ehr.dataSource());

  @ParameterizedTest
  @ValueSource(
      strings = {
        "Julian takes Metformin {f1}",
        "Julian takes metformin {f1}",
        "JULIAN TAKES METFORMIN, {f1}",
        "Julian takes (metformin) twice daily",
        "Julian takes Metformin-XR"
      })
  void givenADrugTheRecordsDoNotHold_whenTypedInAnySpelling_thenFailNamingItAsTheFormularyDoes(
      String text) {
    // Arrange
    Answer answer = answer(text, groundedText(text, List.of(DOSE), List.of(), List.of()));

    // Act
    Verdict verdict = verifier.verify(answer, List.of(MEDICATIONS), noah);

    // Assert
    assertThat(verdict)
        .isEqualTo(Verdict.fail("names a drug the returned records do not hold: Metformin"));
  }

  // Bisoprolol is in the formulary, but no returned row holds it: nobody of this record takes it.
  private static final List<Object[]> WITH_BISOPROLOL =
      Stream.concat(FORMULARY.stream(), Stream.<Object[]>of(new Object[] {"Bisoprolol"})).toList();
  private static final String NOT_TAKEN =
      "No, Julian does not take Bisoprolol; his medications: {t1}";

  @Test
  void givenADrugTheQuestionNames_whenTheAnswerSaysItIsNotTaken_thenPass() {
    // Arrange
    Answer answer =
        new Answer(
            "...",
            List.of(),
            "Is Julian on Bisoprolol?",
            groundedText(NOT_TAKEN, List.of(), List.of(DRUGS), List.of()));

    // Act
    Verdict verdict =
        verifier.verify(
            answer,
            List.of(MEDICATIONS),
            noahWith(new RecordingDataSource(WITH_BISOPROLOL).dataSource()));

    // Assert
    assertThat(verdict).isEqualTo(Verdict.pass("no drug named outside the records"));
  }

  @Test
  void givenADrugTheQuestionDoesNotName_whenTheAnswerTypesIt_thenFail() {
    // Arrange: the same answer, to a question that never said Bisoprolol
    Answer answer = answer("...", groundedText(NOT_TAKEN, List.of(), List.of(DRUGS), List.of()));

    // Act
    Verdict verdict =
        verifier.verify(
            answer,
            List.of(MEDICATIONS),
            noahWith(new RecordingDataSource(WITH_BISOPROLOL).dataSource()));

    // Assert
    assertThat(verdict)
        .isEqualTo(Verdict.fail("names a drug the returned records do not hold: Bisoprolol"));
  }

  static Stream<Arguments> formularies() {
    return Stream.of(argumentSet("drugs", FORMULARY), argumentSet("none", List.<Object[]>of()));
  }

  @ParameterizedTest
  @MethodSource("formularies")
  void givenNoDrugTyped_whenVerified_thenNoDrugIsNamedOutsideTheRecords(List<Object[]> formulary) {
    // Arrange: the table shows Apixaban and Aspirin, but a table is not the answer's own words
    Answer answer =
        answer(
            "Julian takes:\n| Apixaban |",
            groundedText("Julian takes {t1}", List.of(), List.of(DRUGS), List.of()));

    // Act
    Verdict verdict =
        verifier.verify(
            answer,
            List.of(MEDICATIONS),
            noahWith(new RecordingDataSource(formulary).dataSource()));

    // Assert
    assertThat(verdict).isEqualTo(Verdict.pass("no drug named outside the records"));
  }

  @Test
  void givenADrugOnlyInAFigure_whenVerified_thenNoDrugIsNamedOutsideTheRecords() {
    // Arrange: nor is a figure's display
    Map<String, Object> drug = figure("f1", "Apixaban", APIXABAN);
    Answer answer =
        answer(
            "Julian takes Apixaban",
            groundedText("Julian takes {f1}", List.of(drug), List.of(), List.of()));

    // Act
    Verdict verdict = verifier.verify(answer, List.of(MEDICATIONS), noah);

    // Assert
    assertThat(verdict).isEqualTo(Verdict.pass("no drug named outside the records"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"Julian takes Apixaban", "julian takes apixaban and Aspirin."})
  void givenDrugsTheRecordsHold_whenTypedInTheText_thenPass(String text) {
    // Arrange
    Answer answer = answer(text, typed(text));

    // Act
    Verdict verdict = verifier.verify(answer, List.of(MEDICATIONS), noah);

    // Assert
    assertThat(verdict).isEqualTo(Verdict.pass("every drug named is in the records"));
  }

  @Test
  void givenADrugBesideOneTheRecordsDoNotHold_whenTyped_thenFailNamingTheOtherOne() {
    // Arrange
    Answer answer =
        answer("Julian takes Apixaban and Metformin", typed("Julian takes Apixaban and Metformin"));

    // Act
    Verdict verdict = verifier.verify(answer, List.of(MEDICATIONS), noah);

    // Assert
    assertThat(verdict)
        .isEqualTo(Verdict.fail("names a drug the returned records do not hold: Metformin"));
  }

  @Test
  void givenTheDrugInTheTextAfterAFigure_whenVerified_thenFailNamingIt() {
    // Arrange
    Map<String, Object> drug = figure("f1", "Apixaban", APIXABAN);
    String text = "Julian takes {f1} and Metformin.";
    Answer answer = answer(text, groundedText(text, List.of(drug), List.of(), List.of()));

    // Act
    Verdict verdict = verifier.verify(answer, List.of(MEDICATIONS), noah);

    // Assert
    assertThat(verdict)
        .isEqualTo(Verdict.fail("names a drug the returned records do not hold: Metformin"));
  }

  static Stream<Arguments> twoDrugsTheRecordsLack() {
    return Stream.of(
        argumentSet(
            "by name",
            List.of(
                new Object[] {"Warfarin"}, new Object[] {"Metformin"}, new Object[] {"Aspirin"}),
            "Julian takes Warfarin and Metformin"),
        // Sorted as the names are spelled, by Java's natural order: upper case first
        argumentSet(
            "upper case first",
            List.of(new Object[] {"atorvastatin"}, new Object[] {"Metformin"}),
            "Julian takes atorvastatin and metformin"),
        argumentSet(
            "whatever the order the database answers in",
            List.of(new Object[] {"Sertraline"}, new Object[] {"Metformin"}),
            "Julian takes Sertraline and Metformin"));
  }

  @ParameterizedTest
  @MethodSource("twoDrugsTheRecordsLack")
  void givenTwoDrugsTheRecordsLack_whenTyped_thenFailNamingTheFirstSorted(
      List<Object[]> formulary, String text) {
    // Arrange: only Apixaban is in the records, the formulary lists the others as the database does
    Evidence apixabanOnly =
        medications(
            medication(41, "Apixaban", "Anticoagulant", "5 mg", "twice daily", "cardiology"));
    Answer answer = answer(text, typed(text));

    // Act
    Verdict verdict =
        verifier.verify(
            answer,
            List.of(apixabanOnly),
            noahWith(new RecordingDataSource(formulary).dataSource()));

    // Assert
    assertThat(verdict)
        .isEqualTo(Verdict.fail("names a drug the returned records do not hold: Metformin"));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"Amoxicillin+clavulanate", "Co(trimoxazole", "Sodium|valproate", "Vitamin.B12"})
  void givenADrugNameWithRegexCharacters_whenTypedAsWritten_thenItIsMatchedAsWritten(String name) {
    // Arrange: written into a pattern as it stands, "+" would repeat and "(" would not compile
    String text = "Julian takes " + name.toLowerCase(Locale.ROOT);
    Answer answer = answer(text, typed(text));
    RecordingDataSource formulary = new RecordingDataSource(List.<Object[]>of(new Object[] {name}));

    // Act
    Verdict verdict =
        verifier.verify(answer, List.of(MEDICATIONS), noahWith(formulary.dataSource()));

    // Assert
    assertThat(verdict)
        .isEqualTo(Verdict.fail("names a drug the returned records do not hold: " + name));
  }

  @ParameterizedTest
  @ValueSource(strings = {"Julian takes sodium", "Julian takes vitamin-b12"})
  void givenADrugNameWithRegexCharacters_whenOnlyALookAlikeIsTyped_thenItNamesNoDrug(String text) {
    // Arrange: "|" is no alternative and "." no wildcard
    Answer answer = answer(text, typed(text));
    RecordingDataSource formulary =
        new RecordingDataSource(
            List.of(new Object[] {"Sodium|valproate"}, new Object[] {"Vitamin.B12"}));

    // Act
    Verdict verdict =
        verifier.verify(answer, List.of(MEDICATIONS), noahWith(formulary.dataSource()));

    // Assert
    assertThat(verdict).isEqualTo(Verdict.pass("no drug named outside the records"));
  }

  @Test
  void givenADrugSplitByAFigure_whenVerified_thenItNamesNoDrug() {
    // Arrange: the text pieces are words apart, never one word
    String text = "Julian takes Aspir{f1}in";
    Answer answer = answer(text, groundedText(text, List.of(DOSE), List.of(), List.of()));

    // Act
    Verdict verdict = verifier.verify(answer, List.of(MEDICATIONS), noah);

    // Assert
    assertThat(verdict).isEqualTo(Verdict.pass("no drug named outside the records"));
  }

  @Test
  void givenBlankNamesInTheFormulary_whenVerified_thenTheyNameNoDrug() {
    // Arrange: a blank name would be a word between any two words, even two spaces apart
    RecordingDataSource blanks =
        new RecordingDataSource(
            List.of(new Object[] {""}, new Object[] {"  "}, new Object[] {null}));
    Answer answer = answer("Julian takes  Metformin", typed("Julian takes  Metformin"));

    // Act
    Verdict verdict = verifier.verify(answer, List.of(MEDICATIONS), noahWith(blanks.dataSource()));

    // Assert
    assertThat(verdict).isEqualTo(Verdict.pass("no drug named outside the records"));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "Julian takes Metformins",
        "Julian takes bimetformin",
        "Julian takes Metformin2",
        "Julian takes Metforminic acid",
        "Julian takes antimetformin"
      })
  void givenAWordThatOnlyContainsADrugsName_whenVerified_thenItNamesNoDrug(String text) {
    // Arrange
    Answer answer = answer(text, typed(text));

    // Act
    Verdict verdict = verifier.verify(answer, List.of(MEDICATIONS), noah);

    // Assert
    assertThat(verdict).isEqualTo(Verdict.pass("no drug named outside the records"));
  }

  // The records of a drug are the medication rows returned, and those alone: not the formulary,
  // which only says it is a drug, not a note that names it, not another entity with a column drug.
  static Stream<Arguments> recordsHoldingNoSuchDrug() {
    Evidence orders =
        new Evidence(
            "ev-6",
            "find_prescriptions",
            """
            {"entity": "pharmacy.orders", "row_count": 1, "columns": ["order_id", "drug"],
             "rows": [{"order_id": 1, "drug": "Metformin"}], "labels": null}
            """);
    return Stream.of(
        argumentSet("no medications returned", List.of(), "Apixaban"),
        argumentSet("a note that names it", List.of(NOTE_ON_METFORMIN, MEDICATIONS), "Metformin"),
        argumentSet("a result of another entity", List.of(orders, MEDICATIONS), "Metformin"),
        argumentSet(
            "a medication result with null rows",
            List.of(
                new Evidence(
                    "ev-3",
                    "read_medications",
                    "{\"entity\": \"ehr.medications\", \"rows\": null}")),
            "Apixaban"),
        argumentSet(
            "a medication result without rows",
            List.of(new Evidence("ev-3", "read_medications", "{\"entity\": \"ehr.medications\"}")),
            "Apixaban"));
  }

  @ParameterizedTest
  @MethodSource("recordsHoldingNoSuchDrug")
  void givenRecordsHoldingNoSuchDrug_whenTyped_thenFailNamingIt(
      List<Evidence> evidence, String drug) {
    // Arrange
    Answer answer = answer("Julian takes " + drug, typed("Julian takes " + drug));

    // Act
    Verdict verdict = verifier.verify(answer, evidence, noah);

    // Assert
    assertThat(verdict)
        .isEqualTo(Verdict.fail("names a drug the returned records do not hold: " + drug));
  }

  @Test
  void givenTheWordsOfANoteQuoted_whenVerified_thenNoDrugIsNamedOutsideTheRecords() {
    // Arrange: apixaban is the note's own word, and no medication list was returned
    String text = "The note says: \"" + NOTE_WORDS + "\"";
    Answer answer =
        answer(
            text,
            groundedText(text, List.of(), List.of(), List.of(quote("q1", "quoted", NOTE_WORDS))));

    // Act
    Verdict verdict = verifier.verify(answer, List.of(NOTE), noah);

    // Assert
    assertThat(verdict).isEqualTo(Verdict.pass("no drug named outside the records"));
  }

  @Test
  void givenTheWordsOfANoteTypedAndNotQuoted_whenVerified_thenFailNamingTheDrug() {
    // Arrange
    Answer answer = answer(NOTE_WORDS, typed(NOTE_WORDS));

    // Act
    Verdict verdict = verifier.verify(answer, List.of(NOTE), noah);

    // Assert
    assertThat(verdict)
        .isEqualTo(Verdict.fail("names a drug the returned records do not hold: Apixaban"));
  }

  @Test
  void givenAQuoteTheNoteDoesNotHold_whenVerified_thenItsWordsAreScanned() {
    // Arrange: words that are not in the passage are the model's own
    String text = "The note says: \"" + NOTE_WORDS + "\"";
    Answer answer =
        answer(
            text,
            groundedText(text, List.of(), List.of(), List.of(quote("q1", "mismatch", NOTE_WORDS))));

    // Act
    Verdict verdict = verifier.verify(answer, List.of(NOTE), noah);

    // Assert
    assertThat(verdict)
        .isEqualTo(Verdict.fail("names a drug the returned records do not hold: Apixaban"));
  }

  @Test
  void givenADrugTypedBesideAQuotedNote_whenVerified_thenOnlyTheTypedOneFails() {
    // Arrange: Apixaban comes first in the formulary, so it would be named if it were counted
    String text = "Julian takes Metformin. The note says: \"" + NOTE_WORDS + "\"";
    Answer answer =
        answer(
            text,
            groundedText(text, List.of(), List.of(), List.of(quote("q1", "quoted", NOTE_WORDS))));

    // Act
    Verdict verdict = verifier.verify(answer, List.of(NOTE), noah);

    // Assert
    assertThat(verdict)
        .isEqualTo(Verdict.fail("names a drug the returned records do not hold: Metformin"));
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      textBlock =
          """
          Apixaban | The note says: "Apixaban" and Julian takes Apixaban | Apixaban
          pirin    | The note says: "pirin" and Julian takes Aspirin      | Aspirin
          """)
  void givenAShortQuote_whenTheSameWordsAreTypedElsewhere_thenTheyAreStillNamed(
      String quoted, String text, String drug) {
    // Arrange: the quote exempts the words between its marks, not the word wherever it stands
    Answer answer =
        answer(
            text, groundedText(text, List.of(), List.of(), List.of(quote("q1", "quoted", quoted))));

    // Act
    Verdict verdict = verifier.verify(answer, List.of(NOTE), noah);

    // Assert
    assertThat(verdict)
        .isEqualTo(Verdict.fail("names a drug the returned records do not hold: " + drug));
  }

  @Test
  void givenAQuoteInsideAnotherQuote_whenVerified_thenBothAreLeftAlone() {
    // Arrange: the note's own double quotes put one quote's form inside the other's, and taking
    // the short one out first would leave the long one's aspirin behind; the quotes come short
    // first, so the order is the verifier's
    String outer = "now stop \"apixaban\" and aspirin";
    String text = "The note says: \"" + outer + "\" and \"apixaban\".";
    List<?> quotes = List.of(quote("q2", "quoted", "apixaban"), quote("q1", "quoted", outer));
    Answer answer = answer(text, groundedText(text, List.of(), List.of(), quotes));

    // Act
    Verdict verdict = verifier.verify(answer, List.of(NOTE), noah);

    // Assert
    assertThat(verdict).isEqualTo(Verdict.pass("no drug named outside the records"));
  }

  @Test
  void givenAQuoteBetweenTwoPiecesOfAWord_whenVerified_thenItJoinsNothing() {
    // Arrange: Aspir, a quote, in: a quote leaves a space, never the word Aspirin
    String text = "Julian takes Aspir\"x\"in";
    Answer answer =
        answer(text, groundedText(text, List.of(), List.of(), List.of(quote("q1", "quoted", "x"))));

    // Act
    Verdict verdict = verifier.verify(answer, List.of(NOTE), noah);

    // Assert
    assertThat(verdict).isEqualTo(Verdict.pass("no drug named outside the records"));
  }

  @Test
  void givenNoGrounding_whenVerified_thenNoFigureToCheckAndTheHealthRecordIsNotOpened() {
    // Arrange
    Answer answer = answer("I don't know", "");

    // Act
    Verdict verdict = verifier.verify(answer, List.of(), noah);

    // Assert
    assertThat(verdict).isEqualTo(Verdict.pass("no figure to check"));
    assertThat(noah.dataSourcesAsked).isEmpty();
    assertThat(ehr.statements).isEmpty();
  }

  @ParameterizedTest
  @MethodSource("formularies")
  void givenTwoAnswers_whenVerifiedByOneInstance_thenTheFormularyIsReadOnceAndAlone(
      List<Object[]> formulary) {
    // Arrange: an empty formulary is read once too
    RecordingDataSource health = new RecordingDataSource(formulary);
    FakeAIsolateManager manager = noahWith(health.dataSource());
    Answer answer = answer("Julian takes Apixaban", typed("Julian takes Apixaban"));

    // Act
    verifier.verify(answer, List.of(MEDICATIONS), manager);
    verifier.verify(answer, List.of(MEDICATIONS), manager);

    // Assert: the drug names, never a patient's row
    assertThat(health.statements)
        .extracting(RecordingDataSource.Statement::sql)
        .containsExactly(FORMULARY_QUERY);
    assertThat(manager.dataSourcesAsked).containsExactly("ehr");
  }

  @Test
  void
      givenAnUnreachableHealthRecord_whenVerified_thenAnIllegalStateExceptionNotEchoingTheDatabase() {
    // Arrange: the airlock turns any exception into a WARN; the SQLException's message can echo the
    // database's address
    Answer answer = answer("Julian takes Apixaban", typed("Julian takes Apixaban"));

    // Act
    Throwable thrown =
        catchThrowable(
            () -> verifier.verify(answer, List.of(MEDICATIONS), noahWith(unreachable())));

    // Assert
    assertThat(thrown)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageNotContaining("db.internal")
        .hasCauseInstanceOf(SQLException.class);
  }

  @Test
  void givenTheFormularyUnreadableOnce_whenVerifiedAgain_thenItIsReadThen() {
    // Arrange: a failed read caches nothing
    Answer answer = answer("Julian takes Metformin", typed("Julian takes Metformin"));
    assertThat(
            catchThrowable(
                () -> verifier.verify(answer, List.of(MEDICATIONS), noahWith(unreachable()))))
        .isInstanceOf(IllegalStateException.class);

    // Act
    Verdict verdict = verifier.verify(answer, List.of(MEDICATIONS), noah);

    // Assert
    assertThat(verdict)
        .isEqualTo(Verdict.fail("names a drug the returned records do not hold: Metformin"));
  }

  @Test
  void givenNoDataSourceEhr_whenVerified_thenTheUnknownIdSurfaces() {
    // Arrange: the fake defines no data source, as a space without ehr would
    Answer answer = answer("Julian takes Apixaban", typed("Julian takes Apixaban"));

    // Act
    Throwable thrown =
        catchThrowable(
            () -> verifier.verify(answer, List.of(MEDICATIONS), new FakeAIsolateManager()));

    // Assert
    assertThat(thrown)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("test-space/cardiology has no data source ehr");
  }

  /** A health record that fails every connection, as a database that went away does. */
  private static DataSource unreachable() {
    return (DataSource)
        Proxy.newProxyInstance(
            DataSource.class.getClassLoader(),
            new Class<?>[] {DataSource.class},
            (self, method, args) -> {
              throw new SQLException("connection to db.internal:5432 refused");
            });
  }

  private static FakeAIsolateManager noahWith(DataSource ehr) {
    return new FakeAIsolateManager(
        new Principal("noah", "noah@example.com", Set.of("doctor")),
        new AirlockRef("test-space", "cardiology", "test"),
        ehr);
  }

  private static Answer answer(String text, String groundingJson) {
    return new Answer(text, List.of(), "What does Julian take?", groundingJson);
  }

  /** The grounded answer of a text with no figure, table or quote in it. */
  private static String typed(String text) {
    return groundedText(text, List.of(), List.of(), List.of());
  }

  /**
   * The grounded answer as the airlock writes it: the text in segments, a figure or a table where
   * the text places one ({f1}, {t1}); a quote is in the text between double quotes.
   */
  private static String groundedText(String text, List<?> figures, List<?> tables, List<?> quotes) {
    List<Object> segments = new ArrayList<>();
    Matcher placeholder = PLACEHOLDER.matcher(text);
    int at = 0;
    while (placeholder.find()) {
      if (placeholder.start() > at) {
        segments.add(object("text", text.substring(at, placeholder.start())));
      }
      String id = placeholder.group(1);
      segments.add(id.startsWith("t") ? object("table", id) : object("figure", id));
      at = placeholder.end();
    }
    if (at < text.length()) {
      segments.add(object("text", text.substring(at)));
    }
    return grounded(segments, figures, tables, quotes);
  }

  private static Evidence passages(String body) {
    return new Evidence(
        "ev-1",
        "clinical-notes",
        """
        [{"title": "Cardiology note Julian Marek", "section": "2026-10-03", "body": "%s"}]
        """
            .formatted(body));
  }

  private static String medication(
      int id, String drug, String drugClass, String dose, String frequency, String prescribedBy) {
    return """
    {"patient": "Julian Marek", "patient_id": 17, "national_id": "****", "age_group": "60-79",
     "medication_id": %d, "drug": "%s", "drug_class": "%s", "dose": "%s", "frequency": "%s",
     "prescribed_by": "%s", "indication": "unknown: the medication list records no reason",
     "started_on": "2026-09-30", "stopped_on": null}\
    """
        .formatted(id, drug, drugClass, dose, frequency, prescribedBy);
  }

  private static Evidence medications(String... rows) {
    return new Evidence(
        "ev-3",
        "read_medications",
        """
        {"entity": "ehr.medications", "row_count": %d,
         "columns": ["patient", "patient_id", "national_id", "age_group", "medication_id", "drug",
                     "drug_class", "dose", "frequency", "prescribed_by", "indication",
                     "started_on", "stopped_on"],
         "rows": [%s],
         "labels": {"row_key": "medication_id", "subject": "patient", "period": "started_on",
                    "units": {}, "unit_columns": {}, "decimals": {}, "masked": ["national_id"],
                    "function": null}}
        """
            .formatted(rows.length, String.join(", ", rows)));
  }
}
