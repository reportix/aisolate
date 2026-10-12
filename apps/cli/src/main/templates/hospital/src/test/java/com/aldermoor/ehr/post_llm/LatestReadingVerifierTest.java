package com.aldermoor.ehr.post_llm;

import static com.aldermoor.ehr.support.GroundingJson.cell;
import static com.aldermoor.ehr.support.GroundingJson.figure;
import static com.aldermoor.ehr.support.GroundingJson.grounded;
import static com.aldermoor.ehr.support.GroundingJson.object;
import static com.aldermoor.ehr.support.GroundingJson.operand;
import static com.aldermoor.ehr.support.GroundingJson.source;
import static com.aldermoor.ehr.support.GroundingJson.table;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;

import com.aldermoor.ehr.support.FakeAIsolateManager;
import com.reportix.aisolate.sdk.Answer;
import com.reportix.aisolate.sdk.Evidence;
import com.reportix.aisolate.sdk.Verdict;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class LatestReadingVerifierTest {

  private static final String JULIAN = "Julian Marek";
  private static final String AMIRA = "Amira Haddad";
  private static final String LAST_TROPONIN = "What was Julian's last troponin?";
  private static final String LAST_THREE_SESSIONS =
      "How has Julian's mood changed over his last three sessions?";
  private static final String EARLIER_TROPONIN =
      "the answer cites the 2026-10-01 06:10 Troponin T;"
          + " the latest in the records is 2026-10-03 07:40";

  // The evidence as the runtime hands it to a verifier: passages are a JSON
  // list, a tool result is an object.
  private static final Evidence PASSAGES =
      new Evidence(
          "ev-1",
          "clinical-notes",
          """
          [{"title": "Cardiology note Julian Marek", "section": "2026-10-03",
            "body": "Chest pain resolved overnight; troponin trending down."}]
          """);

  private static final String TROPONIN_FIRST =
      lab(301, "2026-10-01 06:10", "Troponin T", "52", "ng/L", "<14");
  private static final String TROPONIN_LAST =
      lab(305, "2026-10-03 07:40", "Troponin T", "38", "ng/L", "<14");
  private static final String POTASSIUM =
      lab(306, "2026-10-03 07:40", "Potassium", "4.1", "mmol/L", "3.5-5.1");
  private static final Evidence LABS = labs(TROPONIN_FIRST, TROPONIN_LAST, POTASSIUM);
  private static final Evidence LABS_WITHOUT_PERIOD =
      new Evidence(
          "ev-2",
          "read_cardiology_records",
          LABS.contentJson().replace("\"period\": \"taken_at\"", "\"period\": null"));

  private static final String SESSION_501 = session(501, "2026-09-12", 7, 17, "low");
  private static final String SESSION_502 = session(502, "2026-09-19", 8, 14, "low");
  private static final String SESSION_503 = session(503, "2026-09-26", 9, 11, "improving");
  private static final String SESSION_504 = session(504, "2026-10-03", 10, 9, "stable");
  private static final Evidence SESSIONS = sessions(SESSION_501, SESSION_502, SESSION_503);

  private static final Evidence VITALS =
      vitals(
          vital(901, JULIAN, 17, "2026-10-02", 142, 88, 76),
          vital(902, AMIRA, 23, "2026-10-02", 128, 82, 64),
          vital(903, JULIAN, 17, "2026-10-03", 135, 84, 72));

  private static final Evidence AVERAGES =
      new Evidence(
          "ev-5",
          "aggregate_cardiology_records",
          """
          {"entity": "cardiology.vitals", "row_count": 3, "columns": ["age_group", "value", "n"],
           "rows": [{"age_group": "18-39", "value": 119.25, "n": 8},
                    {"age_group": "40-59", "value": 137.33, "n": 9},
                    {"age_group": "60-79", "value": 139.11, "n": 9}],
           "labels": {"row_key": "age_group", "subject": null, "period": null,
                      "units": {"value": "mmHg"}, "unit_columns": {}, "decimals": {},
                      "masked": [], "function": "avg"}}
          """);

  // No entity labels an aggregate with a period, but nothing in the rule may rely on that.
  private static final Evidence DATED_AGGREGATE =
      new Evidence(
          "ev-7",
          "aggregate_cardiology_records",
          """
          {"entity": "cardiology.labs", "row_count": 2, "columns": ["lab_id", "taken_at", "value"],
           "rows": [{"lab_id": 301, "taken_at": "2026-10-01 06:10", "value": 52},
                    {"lab_id": 305, "taken_at": "2026-10-03 07:40", "value": 38}],
           "labels": {"row_key": "lab_id", "subject": null, "period": "taken_at", "units": {},
                      "unit_columns": {}, "decimals": {}, "masked": [], "function": "max"}}
          """);

  // A tool the project wrote itself builds its result without labels.
  private static final Evidence OWN_TOOL =
      new Evidence(
          "ev-6",
          "find_notes",
          """
          {"entity": "notes", "row_count": 1, "columns": ["note"],
           "rows": [{"note": "Call"}], "labels": null}
          """);

  private static final Map<String, Object> LAB_301 =
      source("ev-2", "cardiology.labs", "value", "301", JULIAN, "2026-10-01 06:10", "ng/L");
  private static final Map<String, Object> LAB_305 =
      source("ev-2", "cardiology.labs", "value", "305", JULIAN, "2026-10-03 07:40", "ng/L");
  private static final Map<String, Object> LAB_306 =
      source("ev-2", "cardiology.labs", "value", "306", JULIAN, "2026-10-03 07:40", "mmol/L");

  private final LatestReadingVerifier verifier = new LatestReadingVerifier();
  private final FakeAIsolateManager aisolate = new FakeAIsolateManager();

  @Test
  void givenTheEarlierTroponinCited_whenAskedForTheLast_thenFailNamingBothTimes() {
    // Arrange
    Answer answer =
        answer(
            "Julian's last troponin T was 52 ng/L.",
            LAST_TROPONIN,
            grounded(List.of(figure("f1", "52 ng/L", LAB_301)), List.of()));

    // Act
    Verdict verdict = verifier.verify(answer, List.of(PASSAGES, LABS), aisolate);

    // Assert
    assertThat(verdict).isEqualTo(Verdict.fail(EARLIER_TROPONIN));
  }

  @Test
  void givenTheLatestTroponinCited_whenAskedForTheLast_thenPass() {
    // Arrange
    Answer answer =
        answer(
            "Julian's last troponin T was 38 ng/L.",
            LAST_TROPONIN,
            grounded(List.of(figure("f1", "38 ng/L", LAB_305)), List.of()));

    // Act
    Verdict verdict = verifier.verify(answer, List.of(PASSAGES, LABS), aisolate);

    // Assert
    assertThat(verdict).isEqualTo(Verdict.pass("the latest reading is cited"));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "What were Julian's last 3 troponins?",
        "What is Julian's latest troponin?",
        "What is Julian's most recent troponin?",
        "What is Julian's MOST   RECENT troponin?",
        "What is Julian's current troponin?"
      })
  void givenAnyWordingOfTheLatest_whenTheEarlierReadingIsCited_thenFail(String question) {
    // Arrange
    Answer answer =
        answer(
            "Julian's troponin T is 52 ng/L.",
            question,
            grounded(List.of(figure("f1", "52 ng/L", LAB_301)), List.of()));

    // Act
    Verdict verdict = verifier.verify(answer, List.of(LABS), aisolate);

    // Assert
    assertThat(verdict).isEqualTo(Verdict.fail(EARLIER_TROPONIN));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "What was Julian's troponin on 1 October?",
        "What lasting damage does Julian's troponin show?",
        "Was an elastic bandage applied to Julian?",
        "Which concurrent medications does Julian take?",
        // "last" as a date or a sigh, not as the latest
        "What was the troponin last Thursday?",
        "What was Julian's blood pressure last night?",
        "At last, what was Julian's troponin?"
      })
  void givenAQuestionThatDoesNotAskForTheLatest_whenTheEarlierReadingIsCited_thenPass(
      String question) {
    // Arrange
    Answer answer =
        answer(
            "Julian's troponin T was 52 ng/L.",
            question,
            grounded(List.of(figure("f1", "52 ng/L", LAB_301)), List.of()));

    // Act
    Verdict verdict = verifier.verify(answer, List.of(LABS), aisolate);

    // Assert
    assertThat(verdict).isEqualTo(Verdict.pass("the question does not ask for the latest"));
  }

  @Test
  void givenATableOfTheThreeLabs_whenAskedForTheLast_thenNoFigureToCheck() {
    // Arrange: a table lists readings, it does not claim one is the latest
    String labs =
        grounded(
            List.of(),
            List.of(
                table(
                    List.of(
                        List.of(cell("Troponin T", LAB_301), cell("52 ng/L", LAB_301)),
                        List.of(cell("Troponin T", LAB_305), cell("38 ng/L", LAB_305)),
                        List.of(cell("Potassium", LAB_306), cell("4.1 mmol/L", LAB_306))))));
    Answer answer = answer("Julian's labs:\n{t1}", LAST_TROPONIN, labs);

    // Act
    Verdict verdict = verifier.verify(answer, List.of(LABS), aisolate);

    // Assert
    assertThat(verdict).isEqualTo(Verdict.pass("no figure to check"));
  }

  @Test
  void givenNoGrounding_whenVerified_thenNoFigureToCheck() {
    // Arrange
    Answer answer = answer("I don't know", LAST_TROPONIN, "");

    // Act
    Verdict verdict = verifier.verify(answer, List.of(), aisolate);

    // Assert
    assertThat(verdict).isEqualTo(Verdict.pass("no figure to check"));
  }

  @Test
  void givenAComputedFigureOverTheEarlierReading_whenVerified_thenNoFigureToCheck() {
    // Arrange: only a cell cited as the figure is a reading the answer gives
    Map<String, Object> computed = object("op", "sum", "of", List.of(operand("52 ng/L", LAB_301)));
    Answer answer =
        answer(
            "Julian's last troponin T was 52 ng/L.",
            LAST_TROPONIN,
            grounded(
                List.of(figure("f1", "52 ng/L", "computed", "computed", computed)), List.of()));

    // Act
    Verdict verdict = verifier.verify(answer, List.of(LABS), aisolate);

    // Assert
    assertThat(verdict).isEqualTo(Verdict.pass("no figure to check"));
  }

  @Test
  void givenTheEarlierReadingFlaggedMismatch_whenVerified_thenItsSourceIsStillJudged() {
    // Arrange: grounding keeps the source of a mismatch
    Answer answer =
        answer(
            "Julian's last troponin T was 52 ng/L.",
            LAST_TROPONIN,
            grounded(List.of(figure("f1", "52 ng/L", "mismatch", "source", LAB_301)), List.of()));

    // Act
    Verdict verdict = verifier.verify(answer, List.of(LABS), aisolate);

    // Assert
    assertThat(verdict).isEqualTo(Verdict.fail(EARLIER_TROPONIN));
  }

  static Stream<Arguments> figureTheRuleCannotJudge() {
    List<Evidence> exchange = List.of(PASSAGES, LABS, AVERAGES, OWN_TOOL, DATED_AGGREGATE);
    return Stream.of(
        argumentSet(
            "typed in the text",
            grounded(
                List.of(object("id", "u1", "display", "52", "status", "unsourced")), List.of()),
            exchange),
        argumentSet(
            "source in a passage",
            cited(source("ev-1", "cardiology.labs", "value", "301", JULIAN, null, null)),
            exchange),
        argumentSet(
            "source in an evidence the exchange lacks",
            cited(source("ev-9", "cardiology.labs", "value", "301", JULIAN, null, null)),
            exchange),
        argumentSet(
            "row the evidence lacks",
            cited(source("ev-2", "cardiology.labs", "value", "999", JULIAN, null, null)),
            exchange),
        argumentSet(
            "aggregate",
            cited(source("ev-5", "cardiology.vitals", "value", "60-79", null, null, "mmHg")),
            exchange),
        argumentSet(
            "aggregate that names a period",
            cited(source("ev-7", "cardiology.labs", "value", "301", null, null, null)),
            exchange),
        argumentSet(
            "result a tool built itself",
            cited(source("ev-6", "notes", "note", "1", null, null, null)),
            exchange),
        argumentSet(
            "figure the grounding could not resolve",
            grounded(
                List.of(
                    object(
                        "id", "f1",
                        "display", "…",
                        "status", "mismatch",
                        "reason", "ev-2 has no column dose")),
                List.of()),
            exchange),
        argumentSet("result without a period label", cited(LAB_301), List.of(LABS_WITHOUT_PERIOD)),
        argumentSet(
            "result without rows or labels",
            cited(LAB_301),
            List.of(
                new Evidence(
                    "ev-2",
                    "read_cardiology_records",
                    "{\"entity\": \"cardiology.labs\", \"rows\": null, \"labels\": null}"))),
        argumentSet(
            "result that has only an entity",
            cited(LAB_301),
            List.of(
                new Evidence(
                    "ev-2", "read_cardiology_records", "{\"entity\": \"cardiology.labs\"}"))));
  }

  @ParameterizedTest
  @MethodSource("figureTheRuleCannotJudge")
  void givenAFigureThatIsNoReading_whenAskedForTheLast_thenNoFigureToCheck(
      String groundingJson, List<Evidence> evidence) {
    // Arrange
    Answer answer = answer("Julian's last troponin T was 52 ng/L.", LAST_TROPONIN, groundingJson);

    // Act
    Verdict verdict = verifier.verify(answer, evidence, aisolate);

    // Assert
    assertThat(verdict).isEqualTo(Verdict.pass("no figure to check"));
  }

  @Test
  void givenAnEarlierBloodPressure_whenAskedForTheLatest_thenFailNamingTheEntity() {
    // Arrange: not a lab, so the readings are told apart by patient alone
    Map<String, Object> systolic =
        source("ev-4", "cardiology.vitals", "systolic_bp", "901", JULIAN, "2026-10-02", "mmHg");
    Answer answer =
        answer(
            "Julian's latest systolic pressure was 142 mmHg.",
            "What is Julian's latest blood pressure?",
            grounded(List.of(figure("f1", "142 mmHg", systolic)), List.of()));

    // Act
    Verdict verdict = verifier.verify(answer, List.of(VITALS), aisolate);

    // Assert
    assertThat(verdict)
        .isEqualTo(
            Verdict.fail(
                "the answer cites the 2026-10-02 vitals; the latest in the records is 2026-10-03"));
  }

  @Test
  void givenAnotherPatientsLaterReading_whenAskedForJuliansLatest_thenPass() {
    // Arrange: Amira's reading of the 9th is nobody's latest of Julian's
    Evidence vitals =
        vitals(
            vital(903, JULIAN, 17, "2026-10-03", 135, 84, 72),
            vital(904, AMIRA, 23, "2026-10-09", 131, 80, 66));
    Map<String, Object> systolic =
        source("ev-4", "cardiology.vitals", "systolic_bp", "903", JULIAN, "2026-10-03", "mmHg");
    Answer answer =
        answer(
            "Julian's latest systolic pressure was 135 mmHg.",
            "What is Julian's latest blood pressure?",
            grounded(List.of(figure("f1", "135 mmHg", systolic)), List.of()));

    // Act
    Verdict verdict = verifier.verify(answer, List.of(vitals), aisolate);

    // Assert
    assertThat(verdict).isEqualTo(Verdict.pass("the latest reading is cited"));
  }

  @Test
  void givenALaterReadingOfAnotherTest_whenAskedForTheLastTroponin_thenPass() {
    // Arrange: a potassium of the 5th does not make the troponin of the 3rd an old one
    Evidence labs =
        labs(
            TROPONIN_FIRST,
            TROPONIN_LAST,
            lab(310, "2026-10-05 08:00", "Potassium", "4.4", "mmol/L", "3.5-5.1"));
    Answer answer =
        answer(
            "Julian's last troponin T was 38 ng/L.",
            LAST_TROPONIN,
            grounded(List.of(figure("f1", "38 ng/L", LAB_305)), List.of()));

    // Act
    Verdict verdict = verifier.verify(answer, List.of(labs), aisolate);

    // Assert
    assertThat(verdict).isEqualTo(Verdict.pass("the latest reading is cited"));
  }

  @Test
  void givenTheLatestAndAnEarlierReadingOfOneTest_whenAskedForTheLast_thenPass() {
    // Arrange: a comparison cites the earlier reading beside the latest
    Answer answer =
        answer(
            "Julian's troponin T fell from 52 ng/L to 38 ng/L.",
            LAST_TROPONIN,
            grounded(
                List.of(figure("f1", "52 ng/L", LAB_301), figure("f2", "38 ng/L", LAB_305)),
                List.of()));

    // Act
    Verdict verdict = verifier.verify(answer, List.of(LABS), aisolate);

    // Assert
    assertThat(verdict).isEqualTo(Verdict.pass("the latest reading is cited"));
  }

  @Test
  void givenAnEarlierTroponinAndTheLatestPotassium_whenAskedForTheLast_thenFailNamingTheTroponin() {
    // Arrange: each test is a series of its own, the potassium's latest does not make up for it
    Answer answer =
        answer(
            "Julian's last troponin T was 52 ng/L and his last potassium 4.1 mmol/L.",
            "What were Julian's last troponin and potassium?",
            grounded(
                List.of(figure("f1", "52 ng/L", LAB_301), figure("f2", "4.1 mmol/L", LAB_306)),
                List.of()));

    // Act
    Verdict verdict = verifier.verify(answer, List.of(LABS), aisolate);

    // Assert
    assertThat(verdict).isEqualTo(Verdict.fail(EARLIER_TROPONIN));
  }

  @Test
  void givenAnEarlierReadingOfOnePatientAndTheLatestOfAnother_whenAskedForTheLatest_thenFail() {
    // Arrange: each patient's readings are a series of their own
    Evidence vitals =
        vitals(
            vital(901, JULIAN, 17, "2026-10-02", 142, 88, 76),
            vital(902, AMIRA, 23, "2026-10-02", 128, 82, 64),
            vital(903, JULIAN, 17, "2026-10-03", 135, 84, 72),
            vital(904, AMIRA, 23, "2026-10-09", 131, 80, 66));
    Map<String, Object> julian =
        source("ev-4", "cardiology.vitals", "systolic_bp", "903", JULIAN, "2026-10-03", "mmHg");
    Map<String, Object> amira =
        source("ev-4", "cardiology.vitals", "systolic_bp", "902", AMIRA, "2026-10-02", "mmHg");
    Answer answer =
        answer(
            "Julian's latest systolic pressure was 135 mmHg, Amira's 128 mmHg.",
            "What is the latest blood pressure of Julian and Amira?",
            grounded(
                List.of(figure("f1", "135 mmHg", julian), figure("f2", "128 mmHg", amira)),
                List.of()));

    // Act
    Verdict verdict = verifier.verify(answer, List.of(vitals), aisolate);

    // Assert
    assertThat(verdict)
        .isEqualTo(
            Verdict.fail(
                "the answer cites the 2026-10-02 vitals; the latest in the records is 2026-10-09"));
  }

  @Test
  void givenAnEarlierReadingOfOneResultAndTheLatestOfAnother_whenAskedForTheLast_thenFail() {
    // Arrange: a series is what one tool result holds, a second read does not make up for it
    Evidence filtered =
        new Evidence("ev-8", "read_cardiology_records", labs(TROPONIN_LAST).contentJson());
    Map<String, Object> latest =
        source("ev-8", "cardiology.labs", "value", "305", JULIAN, "2026-10-03 07:40", "ng/L");
    Answer answer =
        answer(
            "Julian's troponin T fell from 52 ng/L to 38 ng/L.",
            LAST_TROPONIN,
            grounded(
                List.of(figure("f1", "52 ng/L", LAB_301), figure("f2", "38 ng/L", latest)),
                List.of()));

    // Act
    Verdict verdict = verifier.verify(answer, List.of(LABS, filtered), aisolate);

    // Assert
    assertThat(verdict).isEqualTo(Verdict.fail(EARLIER_TROPONIN));
  }

  @ParameterizedTest
  @ValueSource(strings = {"305", "309"})
  void givenTwoReadingsTiedForTheLatest_whenEitherIsCited_thenPass(String labId) {
    // Arrange: two troponins taken the same minute, both the latest
    Evidence labs =
        labs(
            TROPONIN_FIRST,
            TROPONIN_LAST,
            lab(309, "2026-10-03 07:40", "Troponin T", "36", "ng/L", "<14"));
    Map<String, Object> cited =
        source("ev-2", "cardiology.labs", "value", labId, JULIAN, "2026-10-03 07:40", "ng/L");
    Answer answer =
        answer(
            "Julian's last troponin T was 38 ng/L.",
            LAST_TROPONIN,
            grounded(List.of(figure("f1", "38 ng/L", cited)), List.of()));

    // Act
    Verdict verdict = verifier.verify(answer, List.of(labs), aisolate);

    // Assert
    assertThat(verdict).isEqualTo(Verdict.pass("the latest reading is cited"));
  }

  static Stream<Arguments> twoStaleSeries() {
    Map<String, Object> troponin = figure("f1", "52 ng/L", LAB_301);
    Map<String, Object> potassium = figure("f2", "4.1 mmol/L", LAB_306);
    return Stream.of(
        argumentSet(
            "the troponin first",
            List.of(troponin, potassium),
            "the answer cites the 2026-10-01 06:10 Troponin T;"
                + " the latest in the records is 2026-10-03 07:40"),
        argumentSet(
            "the potassium first",
            List.of(potassium, troponin),
            "the answer cites the 2026-10-03 07:40 Potassium;"
                + " the latest in the records is 2026-10-04 09:00"));
  }

  @ParameterizedTest
  @MethodSource("twoStaleSeries")
  void givenTwoStaleSeries_whenVerified_thenFailNamingTheOneTheAnswerCitesFirst(
      List<Map<String, Object>> figures, String note) {
    // Arrange: a later potassium of the 4th, and the troponin of the 3rd that outdates the 1st
    Evidence labs =
        labs(
            TROPONIN_FIRST,
            TROPONIN_LAST,
            POTASSIUM,
            lab(307, "2026-10-04 09:00", "Potassium", "4.4", "mmol/L", "3.5-5.1"));
    Answer answer =
        answer(
            "Julian's last troponin T and potassium ...",
            "What were Julian's last troponin and potassium?",
            grounded(figures, List.of()));

    // Act
    Verdict verdict = verifier.verify(answer, List.of(labs), aisolate);

    // Assert
    assertThat(verdict).isEqualTo(Verdict.fail(note));
  }

  @Test
  void givenAStaleSeriesBesideACurrentOne_whenVerified_thenFailNamingTheStaleOne() {
    // Arrange: a later troponin does not excuse a potassium that a later one, taken before it,
    // outdates
    Evidence labs =
        labs(
            TROPONIN_FIRST,
            POTASSIUM,
            lab(307, "2026-10-03 09:00", "Potassium", "4.4", "mmol/L", "3.5-5.1"),
            lab(308, "2026-10-03 12:00", "Troponin T", "41", "ng/L", "<14"));
    Map<String, Object> troponin =
        source("ev-2", "cardiology.labs", "value", "308", JULIAN, "2026-10-03 12:00", "ng/L");
    Answer answer =
        answer(
            "Julian's last troponin T was 41 ng/L and his last potassium 4.1 mmol/L.",
            "What were Julian's last troponin and potassium?",
            grounded(
                List.of(figure("f1", "41 ng/L", troponin), figure("f2", "4.1 mmol/L", LAB_306)),
                List.of()));

    // Act
    Verdict verdict = verifier.verify(answer, List.of(labs), aisolate);

    // Assert
    assertThat(verdict)
        .isEqualTo(
            Verdict.fail(
                "the answer cites the 2026-10-03 07:40 Potassium;"
                    + " the latest in the records is 2026-10-03 09:00"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"123", "321", "231"})
  void givenSessionsCitedInAnyOrder_whenALaterOneIsLeftOut_thenTheNoteNamesTheLatestCited(
      String order) {
    // Arrange: the fourth session is the latest; the third is the latest the answer cites
    String[] dates = {"2026-09-12", "2026-09-19", "2026-09-26"};
    List<Map<String, Object>> figures =
        order
            .chars()
            .map(digit -> digit - '0')
            .mapToObj(n -> figure("f" + n, "score", phq9(500 + n, dates[n - 1])))
            .toList();
    Evidence sessions = sessions(SESSION_501, SESSION_502, SESSION_503, SESSION_504);
    Answer answer =
        answer(
            "Over his last three sessions ...", LAST_THREE_SESSIONS, grounded(figures, List.of()));

    // Act
    Verdict verdict = verifier.verify(answer, List.of(sessions), aisolate);

    // Assert
    assertThat(verdict)
        .isEqualTo(
            Verdict.fail(
                "the answer cites the 2026-09-26 sessions; the latest in the records is"
                    + " 2026-10-03"));
  }

  static Stream<Arguments> rowsWithoutAPeriod() {
    return Stream.of(
        argumentSet(
            "the undated row is left out of the series",
            LAB_305,
            Verdict.pass("the latest reading is cited")),
        argumentSet(
            "the cited row is undated",
            source("ev-2", "cardiology.labs", "value", "307", JULIAN, null, "ng/L"),
            Verdict.pass("no figure to check")));
  }

  @ParameterizedTest
  @MethodSource("rowsWithoutAPeriod")
  void givenARowWithoutAPeriod_whenVerified_thenItIsNotCompared(
      Map<String, Object> cited, Verdict expected) {
    // Arrange: a troponin of which no one wrote the time
    Evidence labs =
        labs(TROPONIN_FIRST, TROPONIN_LAST, lab(307, null, "Troponin T", "41", "ng/L", "<14"));
    Answer answer =
        answer(
            "Julian's last troponin T was 38 ng/L.",
            LAST_TROPONIN,
            grounded(List.of(figure("f1", "38 ng/L", cited)), List.of()));

    // Act
    Verdict verdict = verifier.verify(answer, List.of(labs), aisolate);

    // Assert
    assertThat(verdict).isEqualTo(expected);
  }

  @Test
  void givenTheLastThreeSessionsCited_whenAskedForTheLastThree_thenPass() {
    // Arrange: a trend cites its sessions from the earliest to the latest
    Answer answer =
        answer(
            "Over his last three sessions his depression score went from 17 to 14 to 11.",
            LAST_THREE_SESSIONS,
            grounded(
                List.of(
                    figure("f1", "17", phq9(501, "2026-09-12")),
                    figure("f2", "14", phq9(502, "2026-09-19")),
                    figure("f3", "11", phq9(503, "2026-09-26")),
                    figure("f4", "low", mood(501, "2026-09-12")),
                    figure("f5", "improving", mood(503, "2026-09-26"))),
                List.of()));

    // Act
    Verdict verdict = verifier.verify(answer, List.of(SESSIONS), aisolate);

    // Assert
    assertThat(verdict).isEqualTo(Verdict.pass("the latest reading is cited"));
  }

  @Test
  void givenAnEarlierEcg_whenAskedForTheMostRecent_thenFailNamingTheEntity() {
    // Arrange
    Evidence ecgs =
        new Evidence(
            "ev-2",
            "read_cardiology_records",
            """
            {"entity": "cardiology.ecgs", "row_count": 2,
             "columns": ["patient", "ecg_id", "recorded_on", "rhythm"],
             "rows": [{"patient": "Julian Marek", "ecg_id": 801, "recorded_on": "2026-10-01",
                       "rhythm": "atrial fibrillation"},
                      {"patient": "Julian Marek", "ecg_id": 802, "recorded_on": "2026-10-03",
                       "rhythm": "sinus rhythm"}],
             "labels": {"row_key": "ecg_id", "subject": "patient", "period": "recorded_on",
                        "units": {}, "unit_columns": {}, "decimals": {}, "masked": [],
                        "function": null}}
            """);
    Map<String, Object> rhythm =
        source("ev-2", "cardiology.ecgs", "rhythm", "801", JULIAN, "2026-10-01", null);
    Answer answer =
        answer(
            "Julian's most recent ECG shows atrial fibrillation.",
            "What does Julian's most recent ECG show?",
            grounded(List.of(figure("f1", "atrial fibrillation", rhythm)), List.of()));

    // Act
    Verdict verdict = verifier.verify(answer, List.of(ecgs), aisolate);

    // Assert
    assertThat(verdict)
        .isEqualTo(
            Verdict.fail(
                "the answer cites the 2026-10-01 ecgs; the latest in the records is 2026-10-03"));
  }

  static Stream<Arguments> entitiesWithoutReadings() {
    return Stream.of(
        argumentSet(
            "medications: the sertraline started before the apixaban",
            "What is Julian's current dose of sertraline?",
            grounded(
                List.of(
                    figure(
                        "f1",
                        "50 mg",
                        source(
                            "ev-3", "ehr.medications", "dose", "43", JULIAN, "2026-06-12", null))),
                List.of()),
            new Evidence(
                "ev-3",
                "read_medications",
                """
                {"entity": "ehr.medications", "row_count": 3,
                 "columns": ["patient", "medication_id", "drug", "dose", "frequency", "started_on"],
                 "rows": [{"patient": "Julian Marek", "medication_id": 41, "drug": "Apixaban",
                           "dose": "5 mg", "frequency": "twice daily", "started_on": "2026-09-30"},
                          {"patient": "Julian Marek", "medication_id": 42, "drug": "Aspirin",
                           "dose": "100 mg", "frequency": "once daily", "started_on": "2026-09-30"},
                          {"patient": "Julian Marek", "medication_id": 43, "drug": "Sertraline",
                           "dose": "50 mg", "frequency": "once daily", "started_on": "2026-06-12"}],
                 "labels": {"row_key": "medication_id", "subject": "patient",
                            "period": "started_on", "units": {}, "unit_columns": {},
                            "decimals": {}, "masked": [], "function": null}}
                """)),
        argumentSet(
            "diagnoses: the depressive episode diagnosed before the anxiety",
            "What is Julian's current diagnosis?",
            grounded(
                List.of(
                    figure(
                        "f1",
                        "Moderate depressive episode",
                        source(
                            "ev-3",
                            "psychiatry.diagnoses",
                            "diagnosis",
                            "601",
                            JULIAN,
                            "2026-06-12",
                            null))),
                List.of()),
            new Evidence(
                "ev-3",
                "read_psychiatry_records",
                """
                {"entity": "psychiatry.diagnoses", "row_count": 2,
                 "columns": ["patient", "diagnosis_id", "icd10", "diagnosis", "diagnosed_on"],
                 "rows": [{"patient": "Julian Marek", "diagnosis_id": 601, "icd10": "F32.1",
                           "diagnosis": "Moderate depressive episode",
                           "diagnosed_on": "2026-06-12"},
                          {"patient": "Julian Marek", "diagnosis_id": 602, "icd10": "F41.1",
                           "diagnosis": "Generalized anxiety disorder",
                           "diagnosed_on": "2026-09-26"}],
                 "labels": {"row_key": "diagnosis_id", "subject": "patient",
                            "period": "diagnosed_on", "units": {}, "unit_columns": {},
                            "decimals": {}, "masked": [], "function": null}}
                """)));
  }

  @ParameterizedTest
  @MethodSource("entitiesWithoutReadings")
  void givenAnEntityThatHoldsNoReadings_whenAskedForTheCurrent_thenNoFigureToCheck(
      String question, String groundingJson, Evidence evidence) {
    // Arrange: what holds now is not the last one recorded
    Answer answer = answer("Julian's current one is the earlier row.", question, groundingJson);

    // Act
    Verdict verdict = verifier.verify(answer, List.of(evidence), aisolate);

    // Assert
    assertThat(verdict).isEqualTo(Verdict.pass("no figure to check"));
  }

  private static Answer answer(String text, String question, String groundingJson) {
    return new Answer(text, List.of(), question, groundingJson);
  }

  /** One figure citing {@code source} as it is, which the question's wording decides on. */
  private static String cited(Map<String, Object> source) {
    return grounded(List.of(figure("f1", "52 ng/L", source)), List.of());
  }

  private static String lab(
      int id, String takenAt, String test, String value, String unit, String range) {
    return """
    {"patient": "Julian Marek", "patient_id": 17, "national_id": "****", "age_group": "60-79",
     "lab_id": %d, "taken_at": %s, "test": "%s", "value": %s, "unit": "%s",
     "reference_range": "%s"}\
    """
        .formatted(id, takenAt == null ? "null" : '"' + takenAt + '"', test, value, unit, range);
  }

  private static Evidence labs(String... rows) {
    return new Evidence(
        "ev-2",
        "read_cardiology_records",
        """
        {"entity": "cardiology.labs", "row_count": %d,
         "columns": ["patient", "patient_id", "national_id", "age_group", "lab_id", "taken_at",
                     "test", "value", "unit", "reference_range"],
         "rows": [%s],
         "labels": {"row_key": "lab_id", "subject": "patient", "period": "taken_at", "units": {},
                    "unit_columns": {"value": "unit"}, "decimals": {}, "masked": ["national_id"],
                    "function": null}}
        """
            .formatted(rows.length, String.join(", ", rows)));
  }

  private static String vital(
      int id,
      String patient,
      int patientId,
      String measuredOn,
      int systolic,
      int diastolic,
      int bpm) {
    return """
    {"patient": "%s", "patient_id": %d, "national_id": "****", "age_group": "60-79",
     "vital_id": %d, "measured_on": "%s", "systolic_bp": %d, "diastolic_bp": %d,
     "heart_rate": %d}\
    """
        .formatted(patient, patientId, id, measuredOn, systolic, diastolic, bpm);
  }

  private static Evidence vitals(String... rows) {
    return new Evidence(
        "ev-4",
        "read_cardiology_records",
        """
        {"entity": "cardiology.vitals", "row_count": %d,
         "columns": ["patient", "patient_id", "national_id", "age_group", "vital_id", "measured_on",
                     "systolic_bp", "diastolic_bp", "heart_rate"],
         "rows": [%s],
         "labels": {"row_key": "vital_id", "subject": "patient", "period": "measured_on",
                    "units": {"systolic_bp": "mmHg", "diastolic_bp": "mmHg", "heart_rate": "bpm"},
                    "unit_columns": {}, "decimals": {}, "masked": ["national_id"],
                    "function": null}}
        """
            .formatted(rows.length, String.join(", ", rows)));
  }

  private static String session(int id, String date, int number, int phq9, String mood) {
    return """
    {"patient": "Julian Marek", "patient_id": 17, "national_id": "****", "age_group": "60-79",
     "session_id": %d, "session_date": "%s", "session_no": %d, "phq9_score": %d,
     "mood": "%s", "note": null}\
    """
        .formatted(id, date, number, phq9, mood);
  }

  private static Evidence sessions(String... rows) {
    return new Evidence(
        "ev-2",
        "read_psychiatry_records",
        """
        {"entity": "psychiatry.sessions", "row_count": %d,
         "columns": ["patient", "patient_id", "national_id", "age_group", "session_id",
                     "session_date", "session_no", "phq9_score", "mood", "note"],
         "rows": [%s],
         "labels": {"row_key": "session_id", "subject": "patient", "period": "session_date",
                    "units": {}, "unit_columns": {}, "decimals": {}, "masked": ["national_id"],
                    "function": null}}
        """
            .formatted(rows.length, String.join(", ", rows)));
  }

  /** The depression score of a session, as the grounding cites it. */
  private static Map<String, Object> phq9(int sessionId, String date) {
    return source(
        "ev-2", "psychiatry.sessions", "phq9_score", String.valueOf(sessionId), JULIAN, date, null);
  }

  private static Map<String, Object> mood(int sessionId, String date) {
    return source(
        "ev-2", "psychiatry.sessions", "mood", String.valueOf(sessionId), JULIAN, date, null);
  }
}
