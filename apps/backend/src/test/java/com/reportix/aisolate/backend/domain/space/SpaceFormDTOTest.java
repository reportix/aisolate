package com.reportix.aisolate.backend.domain.space;

import static org.assertj.core.api.Assertions.assertThat;

import com.reportix.aisolate.backend.domain.airlock.DataSourceFormDTO;
import com.reportix.aisolate.backend.domain.airlock.LlmFormDTO;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class SpaceFormDTOTest {

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        "' '                | ceo  | developers: must not contain a blank e-mail",
        "nora               | ceo  | developers: nora is no e-mail",
        "JASMINE@example.com | ceo  | developers: lists jasmine@example.com twice",
        "nora@example.com   | ' ceo' | roles: \" ceo\" has a space in it",
        "nora@example.com   | *    | roles: * is no role: list each role; only callers take * for"
            + " any"
      })
  void givenBadDeveloperOrRole_whenValidate_thenExactlyItsError(
      String developer, String role, String error) {
    // Arrange: a developer is an e-mail compared without case; a role is matched exactly
    SpaceFormDTO form =
        new SpaceFormDTO(
            "hr-space",
            "HR",
            null,
            List.of("jasmine@example.com", developer),
            List.of(role),
            null,
            null);

    // Act
    List<String> errors = form.validate();

    // Assert
    assertThat(errors).containsExactly(error);
  }

  @Test
  void givenBrokenRows_whenValidate_thenTheRowErrorsComeBackPrefixed() {
    // Arrange: everything else on the form is valid, so only the delegated rows can fail
    SpaceFormDTO form =
        new SpaceFormDTO(
            "hr-space",
            "HR",
            null,
            List.of("jasmine@example.com"),
            List.of("ceo"),
            List.of(
                new LlmFormDTO(
                    "granite", "mistral", "http://ollama:11434", "granite4.1:8b", null, null)),
            List.of(
                new DataSourceFormDTO("acme_hr", "jdbc:postgresql://db/hr", null, "reader", null)));

    // Act
    List<String> errors = form.validate();

    // Assert: one error each from the row validators, prefixed with their place; what the rows
    // say is the row tests'
    assertThat(errors)
        .satisfiesExactly(
            llm -> assertThat(llm).startsWith("llms[0]."),
            dataSource -> assertThat(dataSource).startsWith("data_sources[0]."));
  }
}
