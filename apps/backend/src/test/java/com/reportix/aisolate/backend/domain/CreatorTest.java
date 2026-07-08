package com.reportix.aisolate.backend.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class CreatorTest {

  @ParameterizedTest
  @CsvSource(
      nullValues = "-",
      value = {
        "Jasmine Keller, jasmine, jasmine, Jasmine Keller",
        "-, jasmine, jasmine, jasmine",
        "'', jasmine, jasmine, jasmine",
        "-, -, 4f1c-sub, -",
      })
  void givenTokenClaims_whenCreatorOf_thenUsernameAndTheNameShown(
      String name, String preferredUsername, String expectedUsername, String expectedName) {
    // Arrange
    Map<String, Object> claims = new HashMap<>(Map.of("sub", "4f1c-sub"));
    if (name != null) {
      claims.put("name", name);
    }
    if (preferredUsername != null) {
      claims.put("preferred_username", preferredUsername);
    }

    // Act
    Creator creator = Creator.of(claims);

    // Assert
    assertThat(creator.username()).isEqualTo(expectedUsername);
    assertThat(creator.name()).isEqualTo(expectedName);
  }
}
