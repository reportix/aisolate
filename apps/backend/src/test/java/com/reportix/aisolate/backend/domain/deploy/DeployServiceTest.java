package com.reportix.aisolate.backend.domain.deploy;

import static org.assertj.core.api.Assertions.assertThat;

import com.reportix.aisolate.backend.domain.airlock.AirlockFormDTO;
import java.util.List;
import org.junit.jupiter.api.Test;

class DeployServiceTest {

  /** The project's code: two tools, a context preparator and a verifier. */
  private static final JarInspector.Contents CODE =
      new JarInspector.Contents(
          "sha256:ab",
          10,
          List.of(
              new JarInspector.Tool("read_records", "", "hr-acme-tools", "acme_hr"),
              new JarInspector.Tool("aggregate_records", "", "hr-acme-tools", "acme_hr")),
          List.of(
              new JarInspector.Handler(
                  "HrDocumentsContextPreparator", "context_prep", 1, null, List.of()),
              new JarInspector.Handler("SalaryVerifier", "verifier", 1, null, List.of())),
          List.of(),
          "java");

  /** The airlock as the deploy saves it, listing these tools, context preparators and verifiers. */
  private static AirlockFormDTO form(
      List<String> tools, List<String> preparators, List<String> verifiers) {
    return new AirlockFormDTO(
        "hr-assistant",
        null,
        null,
        null,
        List.of("*"),
        null,
        preparators,
        null,
        "granite",
        "You are the HR assistant.",
        tools,
        verifiers,
        null,
        null);
  }

  @Test
  void givenVerifierListedNowhere_whenWarnings_thenOneWarning() {
    // Arrange
    AirlockFormDTO form =
        form(
            List.of("read_records", "aggregate_records"),
            List.of("HrDocumentsContextPreparator"),
            List.of());

    // Act
    List<String> warnings = DeployService.warnings(form, CODE);

    // Assert
    assertThat(warnings)
        .containsExactly(
            "verifier SalaryVerifier of this project is listed nowhere and runs nowhere");
  }

  @Test
  void givenToolAndPreparatorListedNowhere_whenWarnings_thenOneWarningEach() {
    // Arrange
    AirlockFormDTO form = form(List.of("read_records"), List.of(), List.of("SalaryVerifier"));

    // Act
    List<String> warnings = DeployService.warnings(form, CODE);

    // Assert
    assertThat(warnings)
        .containsExactly(
            "tool aggregate_records of this project is not in tools, so the model cannot call it",
            "context preparator HrDocumentsContextPreparator of this project is listed nowhere and"
                + " runs nowhere");
  }
}
