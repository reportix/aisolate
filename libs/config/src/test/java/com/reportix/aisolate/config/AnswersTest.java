package com.reportix.aisolate.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class AnswersTest {

  static String[][] cases() {
    return new String[][] {
      {"I don't know", "true"},
      {"I don't know.", "true"},
      {"  i don't know  ", "true"},
      {"I don't know. The medication list does not provide the reason.", "true"},
      {"I DON'T KNOW! [ev-1]", "true"},
      {"I don’t know why he takes it", "true"},
      {"**I don't know**", "true"},
      {"> \"I don't know.\"", "true"},
      {"“I don't know”", "true"},
      {"_I don't know_", "true"},
      {"", "true"},
      {"   ", "true"},
      {"Nobody knows, I don't know why", "false"},
      {"I don't knowingly guess", "false"},
      {"78,000 EUR [ev-1]", "false"},
      {"**78,000 EUR** [ev-1]", "false"}
    };
  }

  @ParameterizedTest
  @MethodSource("cases")
  void givenAnswer_whenClassified_thenDontKnowWhenBlankOrItBeginsWithTheSentence(
      String answer, String dontKnow) {
    // Arrange
    boolean expected = Boolean.parseBoolean(dontKnow);

    // Act
    boolean actual = Answers.isDontKnow(answer);

    // Assert
    assertThat(actual).isEqualTo(expected);
  }
}
