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

class PatientVerifierTest {

  private static final String JULIAN = "Julian Marek";
  private static final String AMIRA = "Amira Haddad";
  private static final String BLOOD_PRESSURE = "What was Julian's blood pressure on 2026-10-02?";
  private static final String AMIRA_FOR_JULIAN =
      "the figure 128 mmHg is about Amira Haddad; the question asks about Julian Marek";

  // The evidence as the runtime hands it to a verifier, the golden set's: passages are a JSON
  // list, a tool result is an object.
  private static final Evidence PASSAGES =
      new Evidence(
          "ev-1",
          "clinical-notes",
          """
          [{"title": "Cardiology note Julian Marek", "section": "2026-10-03",
            "body": "Chest pain resolved overnight; troponin trending down."}]
          """);

  private static final Evidence VITALS =
      new Evidence(
          "ev-4",
          "read_cardiology_records",
          """
          {"entity": "cardiology.vitals", "row_count": 3,
           "columns": ["patient", "patient_id", "national_id", "age_group", "vital_id",
                       "measured_on", "systolic_bp", "diastolic_bp", "heart_rate"],
           "rows": [
             {"patient": "Julian Marek", "patient_id": 17, "national_id": "****",
              "age_group": "60-79", "vital_id": 901, "measured_on": "2026-10-02",
              "systolic_bp": 142, "diastolic_bp": 88, "heart_rate": 76},
             {"patient": "Amira Haddad", "patient_id": 23, "national_id": "****",
              "age_group": "40-59", "vital_id": 902, "measured_on": "2026-10-02",
              "systolic_bp": 128, "diastolic_bp": 82, "heart_rate": 64},
             {"patient": "Julian Marek", "patient_id": 17, "national_id": "****",
              "age_group": "60-79", "vital_id": 903, "measured_on": "2026-10-03",
              "systolic_bp": 135, "diastolic_bp": 84, "heart_rate": 72}],
           "labels": {"row_key": "vital_id", "subject": "patient", "period": "measured_on",
                      "units": {"systolic_bp": "mmHg", "diastolic_bp": "mmHg",
                                "heart_rate": "bpm"},
                      "unit_columns": {}, "decimals": {}, "masked": ["national_id"],
                      "function": null}}
          """);

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

  // A tool the project wrote itself builds its result without labels.
  private static final Evidence OWN_TOOL =
      new Evidence(
          "ev-6",
          "find_notes",
          """
          {"entity": "notes", "row_count": 1, "columns": ["note"],
           "rows": [{"note": "Call"}], "labels": null}
          """);

  // A result that lacks its rows reads as having none.
  private static final Evidence NO_ROWS =
      new Evidence(
          "ev-7",
          "read_cardiology_records",
          """
          {"entity": "cardiology.labs",
           "labels": {"row_key": "lab_id", "subject": "patient", "period": "taken_at",
                      "units": {}, "unit_columns": {}, "decimals": {}, "masked": [],
                      "function": null}}
          """);

  private static final List<Evidence> EVIDENCE =
      List.of(PASSAGES, VITALS, AVERAGES, OWN_TOOL, NO_ROWS);

  private static final Map<String, Object> JULIAN_901 = systolic("901", JULIAN, "2026-10-02");
  private static final Map<String, Object> AMIRA_902 = systolic("902", AMIRA, "2026-10-02");
  private static final Map<String, Object> JULIAN_903 = systolic("903", JULIAN, "2026-10-03");

  private final PatientVerifier verifier = new PatientVerifier();
  private final FakeAIsolateManager noah = new FakeAIsolateManager();

  @Test
  void givenJuliansPressureForJulian_whenVerified_thenPass() {
    // Arrange
    Answer answer =
        answer(
            "Julian's systolic pressure was 142 mmHg.",
            BLOOD_PRESSURE,
            grounded(List.of(figure("f1", "142 mmHg", JULIAN_901)), List.of()));

    // Act
    Verdict verdict = verifier.verify(answer, EVIDENCE, noah);

    // Assert
    assertThat(verdict).isEqualTo(Verdict.pass("every figure is about Julian Marek"));
  }

  @Test
  void givenJuliansReadingsAsATable_whenVerified_thenPass() {
    // Arrange
    String readings =
        grounded(
            List.of(),
            List.of(
                table(
                    List.of(
                        List.of(cell("2026-10-02", JULIAN_901), cell("142 mmHg", JULIAN_901)),
                        List.of(cell("2026-10-03", JULIAN_903), cell("135 mmHg", JULIAN_903))))));
    Answer answer =
        answer("Julian's pressure:\n{t1}", "Which pressures does Julian have?", readings);

    // Act
    Verdict verdict = verifier.verify(answer, EVIDENCE, noah);

    // Assert
    assertThat(verdict).isEqualTo(Verdict.pass("every figure is about Julian Marek"));
  }

  @Test
  void givenQuestionNamingNoPatient_whenVerified_thenPass() {
    // Arrange: an average by age group is about no patient
    Map<String, Object> group =
        source("ev-5", "cardiology.vitals", "value", "60-79", null, null, "mmHg");
    Answer answer =
        answer(
            "The average systolic pressure at 60-79 is 139.11 mmHg.",
            "Average systolic by age group?",
            grounded(List.of(figure("f1", "139.11 mmHg", group)), List.of()));

    // Act
    Verdict verdict = verifier.verify(answer, EVIDENCE, noah);

    // Assert
    assertThat(verdict).isEqualTo(Verdict.pass("the question names no patient"));
  }

  @Test
  void givenNoGrounding_whenVerified_thenPass() {
    // Arrange
    Answer answer = answer("I don't know", BLOOD_PRESSURE, "");

    // Act
    Verdict verdict = verifier.verify(answer, List.of(), noah);

    // Assert
    assertThat(verdict).isEqualTo(Verdict.pass("no figure to check"));
  }

  @Test
  void givenComparisonOfTwoNamedPatients_whenVerified_thenPassNamingBothSorted() {
    // Arrange
    Map<String, Object> computed =
        object(
            "op",
            "percent_change",
            "of",
            List.of(operand("128 mmHg", AMIRA_902), operand("142 mmHg", JULIAN_901)));
    Answer answer =
        answer(
            "Julian's pressure is 10.9% higher.",
            "How does Julian's systolic pressure compare with Amira's?",
            grounded(List.of(figure("f1", "10.9%", "computed", "computed", computed)), List.of()));

    // Act
    Verdict verdict = verifier.verify(answer, EVIDENCE, noah);

    // Assert
    assertThat(verdict).isEqualTo(Verdict.pass("every figure is about Amira Haddad, Julian Marek"));
  }

  static Stream<Arguments> amirasFigure() {
    return Stream.of(
        argumentSet(
            "figure",
            BLOOD_PRESSURE,
            "Julian's systolic pressure was 128 mmHg.",
            grounded(List.of(figure("f1", "128 mmHg", AMIRA_902)), List.of())),
        argumentSet(
            "operand shown as itself",
            "How much did Julian's systolic pressure change?",
            "Julian's systolic pressure changed by 7 mmHg.",
            grounded(
                List.of(
                    figure(
                        "f1",
                        "7 mmHg",
                        "computed",
                        "computed",
                        object(
                            "op",
                            "subtract",
                            "of",
                            List.of(
                                operand("135 mmHg", JULIAN_903), operand("128 mmHg", AMIRA_902))))),
                List.of())),
        argumentSet(
            "row shown as the figure",
            "What was Julian's highest systolic pressure?",
            "Julian's highest systolic pressure was 128 mmHg.",
            grounded(
                List.of(
                    figure(
                        "f1",
                        "128 mmHg",
                        "computed",
                        "computed",
                        object(
                            "op",
                            "max",
                            "by",
                            object(
                                "evidence",
                                "ev-4",
                                "entity",
                                "cardiology.vitals",
                                "column",
                                "systolic_bp"),
                            "row",
                            AMIRA_902))),
                List.of())),
        argumentSet(
            "ranked row shown as itself",
            "What are Julian's two highest systolic pressures?",
            "Julian's two highest systolic pressures are 142 mmHg and 128 mmHg.",
            grounded(
                List.of(
                    figure(
                        "f1",
                        "142 mmHg, 128 mmHg",
                        "computed",
                        "computed",
                        object(
                            "op",
                            "top_n",
                            "n",
                            2,
                            "by",
                            object(
                                "evidence",
                                "ev-4",
                                "entity",
                                "cardiology.vitals",
                                "column",
                                "systolic_bp"),
                            "rows",
                            List.of(
                                operand("142 mmHg", JULIAN_901), operand("128 mmHg", AMIRA_902))))),
                List.of())),
        argumentSet(
            "table cell",
            "Which pressures does Julian have?",
            "Julian's pressures:\n{t1}",
            grounded(
                List.of(),
                List.of(
                    table(
                        List.of(
                            List.of(cell("142 mmHg", JULIAN_901)),
                            List.of(cell("128 mmHg", AMIRA_902))))))));
  }

  @ParameterizedTest
  @MethodSource("amirasFigure")
  void givenAnotherPatientsFigureOrCell_whenVerified_thenFail(
      String question, String text, String groundingJson) {
    // Arrange
    Answer answer = answer(text, question, groundingJson);

    // Act
    Verdict verdict = verifier.verify(answer, EVIDENCE, noah);

    // Assert
    assertThat(verdict).isEqualTo(Verdict.fail(AMIRA_FOR_JULIAN));
  }

  // An average is about a group, not a patient.
  static Stream<Arguments> citingNoPatient() {
    Map<String, Object> group =
        source("ev-5", "cardiology.vitals", "value", "60-79", null, null, "mmHg");
    return Stream.of(
        argumentSet(
            "figure without a subject",
            grounded(List.of(figure("f1", "139.11 mmHg", group)), List.of())),
        argumentSet("no figures or tables", grounded(List.of(), List.of())));
  }

  @ParameterizedTest
  @MethodSource("citingNoPatient")
  void givenAnswerCitingNoPatient_whenVerified_thenNoFigureToCheck(String groundingJson) {
    // Arrange
    Answer answer =
        answer(
            "The average systolic pressure at 60-79 is 139.11 mmHg.",
            "What is the average systolic pressure of Julian's age group?",
            groundingJson);

    // Act
    Verdict verdict = verifier.verify(answer, EVIDENCE, noah);

    // Assert
    assertThat(verdict).isEqualTo(Verdict.pass("no figure to check"));
  }

  // Grounding keeps the source of a mismatch, which can be about another patient all the same.
  static Stream<Arguments> flaggedMismatch() {
    return Stream.of(
        argumentSet(
            "figure",
            grounded(
                List.of(figure("f1", "128 mmHg", "mismatch", "source", AMIRA_902)), List.of())),
        argumentSet(
            "table cell",
            grounded(
                List.of(),
                List.of(table(List.of(List.of(cell("128 mmHg", "mismatch", AMIRA_902))))))));
  }

  @ParameterizedTest
  @MethodSource("flaggedMismatch")
  void givenAFigureOrCellFlaggedMismatch_whenVerified_thenItsSourceIsStillJudged(
      String groundingJson) {
    // Arrange
    Answer answer =
        answer("Julian's systolic pressure was 128 mmHg.", BLOOD_PRESSURE, groundingJson);

    // Act
    Verdict verdict = verifier.verify(answer, EVIDENCE, noah);

    // Assert
    assertThat(verdict).isEqualTo(Verdict.fail(AMIRA_FOR_JULIAN));
  }

  @ParameterizedTest
  @ValueSource(strings = {"ev-1", "ev-9"})
  void givenASourceThatIsNoToolResult_whenVerified_thenNoFigureToCheck(String evidenceId) {
    // Arrange: ev-1 holds passages and ev-9 is not in the exchange: neither says whose row it is
    Map<String, Object> cited =
        source(evidenceId, "cardiology.vitals", "systolic_bp", "902", AMIRA, null, "mmHg");
    Answer answer =
        answer(
            "Julian's systolic pressure was 128 mmHg.",
            BLOOD_PRESSURE,
            grounded(List.of(figure("f1", "128 mmHg", cited)), List.of()));

    // Act
    Verdict verdict = verifier.verify(answer, EVIDENCE, noah);

    // Assert
    assertThat(verdict).isEqualTo(Verdict.pass("no figure to check"));
  }

  static Stream<Arguments> resemblingName() {
    return Stream.of(
        argumentSet("word that only contains the name", "Ann Berg", "Is the annual check done?"),
        argumentSet("leading word of under three letters", "Al Haddad", "Is Al's pressure high?"));
  }

  @ParameterizedTest
  @MethodSource("resemblingName")
  void givenQuestionThatOnlyResemblesAPatientsName_whenVerified_thenItNamesNone(
      String patient, String question) {
    // Arrange
    Evidence other =
        new Evidence(
            "ev-8",
            "read_cardiology_records",
            """
            {"entity": "cardiology.vitals", "row_count": 1, "columns": ["patient", "vital_id"],
             "rows": [{"patient": "%s", "vital_id": 905}],
             "labels": {"row_key": "vital_id", "subject": "patient", "period": null, "units": {},
                        "unit_columns": {}, "decimals": {}, "masked": [], "function": null}}
            """
                .formatted(patient));
    List<Evidence> evidence = Stream.concat(EVIDENCE.stream(), Stream.of(other)).toList();
    Answer answer =
        answer(
            "142 mmHg is high.",
            question,
            grounded(List.of(figure("f1", "142 mmHg", JULIAN_901)), List.of()));

    // Act
    Verdict verdict = verifier.verify(answer, evidence, noah);

    // Assert
    assertThat(verdict).isEqualTo(Verdict.pass("the question names no patient"));
  }

  private static Answer answer(String text, String question, String groundingJson) {
    return new Answer(text, List.of(), question, groundingJson);
  }

  /** The systolic cell of a reading of cardiology.vitals, as the grounding cites it. */
  private static Map<String, Object> systolic(String vitalId, String patient, String measuredOn) {
    return source("ev-4", "cardiology.vitals", "systolic_bp", vitalId, patient, measuredOn, "mmHg");
  }
}
