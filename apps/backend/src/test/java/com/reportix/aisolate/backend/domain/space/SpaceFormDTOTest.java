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
        "' '               | user      | members: must not contain a blank e-mail",
        "eric@example.com  | owner     | members: eric@example.com needs the role developer or"
            + " user",
        "eric@example.com  | ''        | members: eric@example.com needs the role developer or"
            + " user",
        "nora              | developer | members: nora is no e-mail"
      })
  void givenBadMember_whenValidate_thenExactlyItsError(String email, String role, String error) {
    // Arrange: the e-mail check is the airlock's users'; the role is a space's own
    SpaceFormDTO form =
        new SpaceFormDTO(
            "hr-space",
            "HR",
            null,
            List.of(
                new SpaceFormDTO.Member("jasmine@example.com", SpaceFormDTO.DEVELOPER),
                new SpaceFormDTO.Member(email, role)),
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
            List.of(new SpaceFormDTO.Member("jasmine@example.com", SpaceFormDTO.DEVELOPER)),
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
