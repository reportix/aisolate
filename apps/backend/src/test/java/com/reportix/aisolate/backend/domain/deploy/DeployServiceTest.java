package com.reportix.aisolate.backend.domain.deploy;

import static org.assertj.core.api.Assertions.assertThat;

import com.reportix.aisolate.backend.domain.airlock.Airlock;
import com.reportix.aisolate.backend.domain.airlock.AirlockFormDTO;
import com.reportix.aisolate.backend.domain.airlock.DataSourceFormDTO;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

class DeployServiceTest {

  private static final YAMLMapper YAML = new YAMLMapper();

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

  /** The version as the scan reads it, listing these tools, context preparators and verifiers. */
  private static JsonNode version(String tools, String preparators, String verifiers) {
    return YAML.readTree(
        """
        apiVersion: aisolate.dev/v1
        kind: AirlockVersion
        metadata: {name: hr-assistant-v1}
        spec:
          airlock: hr-assistant
          version: v1
          preLLM:
            contextPreparator: {handlers: [%s]}
          llm: granite
          prompt: You are the HR assistant.
          tools: [%s]
          postLLM:
            answerVerifier: {handlers: [%s]}
        """
            .formatted(preparators, tools, verifiers));
  }

  @Test
  void givenVerifierListedNowhere_whenWarnings_thenOneWarning() {
    // Arrange
    JsonNode version =
        version("read_records, aggregate_records", "HrDocumentsContextPreparator", "");

    // Act
    List<String> warnings = DeployService.warnings(version, CODE);

    // Assert
    assertThat(warnings)
        .containsExactly(
            "verifier SalaryVerifier of this project is listed nowhere and runs nowhere");
  }

  @Test
  void givenToolAndPreparatorListedNowhere_whenWarnings_thenOneWarningEach() {
    // Arrange
    JsonNode version = version("read_records", "", "SalaryVerifier");

    // Act
    List<String> warnings = DeployService.warnings(version, CODE);

    // Assert
    assertThat(warnings)
        .containsExactly(
            "tool aggregate_records of this project is not in tools, so the model cannot call it",
            "context preparator HrDocumentsContextPreparator of this project is listed nowhere and"
                + " runs nowhere");
  }

  /** The airlock as stored: one role, any caller, one data source with its Secret. */
  private static final Airlock STORED =
      new Airlock(
          "hr",
          "acme",
          1,
          1,
          "seed",
          null,
          null,
          null,
          new AirlockFormDTO(
              "hr",
              "HR",
              "Pay and leave.",
              List.of("employee"),
              List.of("*"),
              null,
              List.of(),
              List.of(
                  new DataSourceFormDTO(
                      "hr", "jdbc:postgresql://db:5432/hr", "acme-hr-ds-hr-ab12cd", null, null))));

  @ParameterizedTest
  @CsvSource({
    "HR,Pay and leave.,'',false",
    "HR,Pay leave and contracts.,'',true",
    "People,Pay and leave.,'',true",
    "HR,Pay and leave.,s3cret,true"
  })
  void givenFormFromTheFile_whenChanges_thenTrueOnlyWhenASaveWouldDiffer(
      String name, String description, String password, boolean expected) {
    // Arrange: the file's rows carry no Secret name; the stored one is kept, so it is no change
    AirlockFormDTO form =
        STORED
            .form()
            .withName(name)
            .withDescription(description)
            .withDataSources(
                List.of(
                    new DataSourceFormDTO(
                        "hr",
                        "jdbc:postgresql://db:5432/hr",
                        null,
                        password.isEmpty() ? null : "reader",
                        password.isEmpty() ? null : password)));

    // Act
    boolean changes = DeployService.changes(STORED, form);

    // Assert
    assertThat(changes).isEqualTo(expected);
  }
}
