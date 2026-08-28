package com.reportix.aisolate.auditreport;

import static org.assertj.core.api.Assertions.assertThat;

import com.reportix.aisolate.config.Answers;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** The report classifies an answer as the airlock does, older airlocks' lines included. */
class AuditOutcomeTest {

  @ParameterizedTest
  @ValueSource(
      strings = {
        "I don't know",
        "  i don't know  ",
        "I don't know. The medication list does not provide the reason.",
        "I DON'T KNOW! [ev-1]",
        "I don’t know why he takes it",
        "**I don't know**",
        "> \"I don't know.\"",
        "“I don't know”",
        "_I don't know_",
        "",
        "   ",
        "Nobody knows, I don't know why",
        "I don't knowingly guess",
        "78,000 EUR [ev-1]",
        "**78,000 EUR** [ev-1]"
      })
  void givenAnswer_whenClassified_thenAsTheAirlockClassifiesIt(String answer) {
    // Arrange
    boolean airlock = Answers.isDontKnow(answer);

    // Act
    boolean report = AuditReport.isDontKnow(answer);

    // Assert
    assertThat(report).isEqualTo(airlock);
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        "answered|I don't know. The medication list does not provide the reason.|dont_know",
        "answered|78,000 EUR [ev-1]|answered",
        "dont_know|I don't know|dont_know",
        "error||error"
      })
  void givenLoggedOutcome_whenParsed_thenTheOutcomeTheCurrentAirlockWouldLog(
      String logged, String answer, String expected) {
    // Arrange
    String line =
        "{\"ts\":\"2026-10-03T09:00:00Z\",\"event\":\"exchange\",\"space\":\"aldermoor\","
            + "\"airlock\":\"cardiology\",\"answer\":"
            + (answer == null ? "null" : "\"" + answer + "\"")
            + ",\"outcome\":\""
            + logged
            + "\",\"reason\":null}";

    // Act
    AuditLine parsed = AuditLine.parse(line);

    // Assert
    assertThat(parsed.outcome()).isEqualTo(expected);
  }
}
