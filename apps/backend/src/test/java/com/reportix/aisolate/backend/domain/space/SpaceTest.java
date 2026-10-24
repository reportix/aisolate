package com.reportix.aisolate.backend.domain.space;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class SpaceTest {

  @ParameterizedTest
  @CsvSource(
      nullValues = "null",
      value = {
        "jasmine@example.com, true",
        "Jasmine@Example.COM, true",
        "nora@example.com,    false",
        "null,                false"
      })
  void givenADeveloper_whenDevelops_thenTheEmailMatchesWithoutCase(String email, boolean expected) {
    // Arrange
    Space space =
        new Space(
            "hr",
            "HR",
            "",
            List.of("jasmine@example.com"),
            List.of("ceo"),
            List.of(),
            List.of(),
            null,
            null,
            null,
            null,
            null);

    // Act
    boolean develops = space.develops(email);

    // Assert
    assertThat(develops).isEqualTo(expected);
  }
}
