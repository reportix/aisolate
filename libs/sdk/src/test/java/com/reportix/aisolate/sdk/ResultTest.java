package com.reportix.aisolate.sdk;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ResultTest {

  @Test
  void givenTheLabelsConstructorBeforeFacts_whenLabelsAreBuilt_thenTheyListNoFacts() {
    // Arrange: the eight arguments a plugin compiled against the SDK before facts passes

    // Act
    Result.Labels labels =
        new Result.Labels(
            "id", "name", null, Map.of("amount", "EUR"), Map.of(), Map.of(), List.of("iban"), null);

    // Assert
    assertThat(labels)
        .isEqualTo(
            new Result.Labels(
                "id",
                "name",
                null,
                Map.of("amount", "EUR"),
                Map.of(),
                Map.of(),
                List.of("iban"),
                null,
                List.of()));
  }
}
