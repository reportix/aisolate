package com.reportix.aisolate.sdk;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class AnswerTest {

  @Test
  void givenTextAndVerdictsOnly_whenBuilt_thenQuestionAndGroundingAreEmpty() {
    // Arrange: an answer built without the question and the grounding

    // Act
    Answer answer = new Answer("t", List.of());

    // Assert
    assertThat(answer).extracting(Answer::question, Answer::groundingJson).containsExactly("", "");
  }

  @Test
  void givenNulls_whenBuilt_thenEveryComponentIsEmpty() {
    // Arrange: nothing to arrange, the nulls are the input

    // Act
    Answer answer = new Answer(null, null, null, null);

    // Assert
    assertThat(answer)
        .extracting(Answer::text, Answer::verdicts, Answer::question, Answer::groundingJson)
        .containsExactly("", List.of(), "", "");
  }

  @Test
  void givenAVerdictList_whenItChangesAfterwards_thenTheAnswerKeepsItsVerdicts() {
    // Arrange
    List<Verdict> verdicts = new ArrayList<>(List.of(Verdict.pass("first")));
    Answer answer = new Answer("t", verdicts);

    // Act
    verdicts.add(Verdict.warn("second"));

    // Assert
    assertThat(answer.verdicts()).containsExactly(Verdict.pass("first"));
  }
}
