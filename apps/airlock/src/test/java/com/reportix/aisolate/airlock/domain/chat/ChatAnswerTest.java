package com.reportix.aisolate.airlock.domain.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.reportix.aisolate.airlock.domain.chat.audit.AuditEvent;
import com.reportix.aisolate.sdk.Verdict;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ChatAnswerTest {

  private static ChatEvent.Evidence evidence(String id, String tool, String entity, int rows) {
    return new ChatEvent.Evidence(
        id, null, tool, entity, rows, List.of(), "1", List.of(), "", null, null);
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        "I don't know|true",
        "I don't know.|true",
        "  i don't know  |true",
        "I don't know. The medication list does not provide the reason.|true",
        "I DON'T KNOW! [ev-1]|true",
        "I don’t know why he takes it|true",
        "Nobody knows, I don't know why|false",
        "I don't knowingly guess|false"
      })
  void givenAnswerText_whenCollected_thenDontKnowWhenItBeginsWithTheSentence(
      String text, boolean dontKnow) {
    // Arrange
    List<ChatEvent> events = List.of(new ChatEvent.AnswerChunk(text));

    // Act
    ChatAnswer answer = ChatAnswer.from(events, "r1", null);

    // Assert
    assertThat(answer.dontKnow()).isEqualTo(dontKnow);
  }

  @Test
  void givenToolAndPassageEvidence_whenCollected_thenProvenanceWithoutRows() {
    // Arrange
    List<ChatEvent> events =
        List.of(
            evidence("ev-1", "hr-documents", "hr-documents", 2),
            new ChatEvent.ToolCall("call-1", "read_records", Map.of()),
            new ChatEvent.Evidence(
                "ev-2",
                "call-1",
                "read_records",
                "hr.compensation",
                1,
                List.of(),
                "1",
                List.of(),
                "",
                "acme_hr",
                "9f86d0"),
            new ChatEvent.AnswerChunk("78,000"),
            ChatEvent.Done.of(List.of("ev-1", "ev-2"), true, GroundedAnswer.plain("78,000")));

    // Act
    ChatAnswer answer = ChatAnswer.from(events, "a2cf258ece3d", "4bf92f3577b34da6a3ce929d0e0e4736");

    // Assert
    assertThat(answer.evidence())
        .containsExactly(
            new ChatAnswer.EvidenceSummary("ev-1", "hr-documents", "hr-documents", 2, null),
            new ChatAnswer.EvidenceSummary(
                "ev-2", "read_records", "hr.compensation", 1, "acme_hr"));
    assertThat(answer.revision()).isEqualTo("a2cf258ece3d");
    assertThat(answer.traceId()).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
  }

  @Test
  void givenNoVerifierRan_whenCollected_thenNotVerifiedAndNoVerdicts() {
    // Arrange
    List<ChatEvent> events = List.of(new ChatEvent.AnswerChunk("Yes."));

    // Act
    ChatAnswer answer = ChatAnswer.from(events, "r1", null);

    // Assert: nothing checked it
    assertThat(answer.verification().verified()).isFalse();
    assertThat(answer.verification().verdicts()).isEmpty();
  }

  @ParameterizedTest
  @CsvSource({"PASS,PASS,true", "PASS,WARN,false"})
  void givenVerdicts_whenCollected_thenVerifiedOnlyWhenEveryOneIsPass(
      Verdict.Status first, Verdict.Status second, boolean verified) {
    // Arrange
    List<ChatEvent> events =
        List.of(
            new ChatEvent.AnswerChunk("Yes."),
            new ChatEvent.Verification(
                List.of(new Verdict("A", first, ""), new Verdict("B", second, "note"))));

    // Act
    ChatAnswer answer = ChatAnswer.from(events, "r1", null);

    // Assert
    assertThat(answer.verification().verified()).isEqualTo(verified);
    assertThat(answer.verification().verdicts())
        .containsExactly(new AuditEvent.Verifier("A", first), new AuditEvent.Verifier("B", second));
  }

  @Test
  void givenErrorEvent_whenCollected_thenTheExchangeFailsWithItsSentence() {
    // Arrange
    List<ChatEvent> events = List.of(ChatEvent.Error.MODEL_UNAVAILABLE);

    // Act
    Throwable thrown = catchThrowable(() -> ChatAnswer.from(events, "r1", null));

    // Assert
    assertThat(thrown)
        .isInstanceOf(ExchangeFailedException.class)
        .hasMessage(ChatEvent.Error.MODEL_UNAVAILABLE.message());
  }
}
