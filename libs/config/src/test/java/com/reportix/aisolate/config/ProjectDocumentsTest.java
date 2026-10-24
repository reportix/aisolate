package com.reportix.aisolate.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ProjectDocumentsTest {

  @Test
  void givenCredentials_whenPrinted_thenNeitherValueShows() {
    // Arrange
    var split =
        new ProjectDocuments.Split(
            "", Map.of("hr/dataSources/hr", new ProjectDocuments.Credentials("reader", "hunter2")));

    // Act
    String printed = split.toString();

    // Assert
    assertThat(printed).doesNotContain("reader").doesNotContain("hunter2");
  }

  private static final String PROJECT =
      """
      apiVersion: aisolate.dev/v1
      kind: Space
      metadata: {name: hr-acme}
      spec:
        access: {roles: [employee]}
        llms: [{id: granite, provider: ollama, url: "http://ollama:11434", model: "granite4.1:8b"}]
        dataSources:
          - id: payroll
            url: jdbc:postgresql://payroll-db:5432/payroll
            username: payroll_reader
            password: ${PAYROLL_PASSWORD}
      ---
      apiVersion: aisolate.dev/v1
      kind: Airlock
      metadata: {name: hr-assistant}
      spec:
        space: hr-acme
        access: {roles: [employee], callers: ["*"]}
        llm: granite
        prompt: Answer from the tools.
        llms:
          - {id: granite, provider: ollama, url: "${OLLAMA_URL:-http://localhost:11434}", model: "granite4.1:8b"}
        dataSources:
          - id: acme_hr
            url: jdbc:postgresql://acme-db:5432/acme_hr
            username: ${HR_USERNAME}
            password: ${HR_PASSWORD}
          - id: hr-documents
            url: jdbc:postgresql://acme-db:5432/acme_hr
            username: preparator
            password: ${DOCS_PASSWORD}
      """;

  @Test
  void givenProjectWithVariables_whenSplit_thenCredentialsPerDataSourceIdAndTheRestLoads() {
    // Arrange
    Map<String, String> variables =
        Map.of(
            "HR_USERNAME", "reader",
            "HR_PASSWORD", "s3cret",
            "DOCS_PASSWORD", "d0cs",
            "PAYROLL_PASSWORD", "p4y");

    // Act
    ProjectDocuments.Split split = ProjectDocuments.split(PROJECT, variables);

    // Assert
    assertThat(ProjectDocuments.dataSourceKey("hr-assistant", "acme_hr"))
        .isEqualTo("hr-assistant/dataSources/acme_hr");
    assertThat(split.credentials())
        .containsOnly(
            entry(
                ProjectDocuments.dataSourceKey("hr-acme", "payroll"),
                new ProjectDocuments.Credentials("payroll_reader", "p4y")),
            entry(
                ProjectDocuments.dataSourceKey("hr-assistant", "acme_hr"),
                new ProjectDocuments.Credentials("reader", "s3cret")),
            entry(
                ProjectDocuments.dataSourceKey("hr-assistant", "hr-documents"),
                new ProjectDocuments.Credentials("preparator", "d0cs")));
    assertThat(split.documents())
        .doesNotContain("s3cret")
        .doesNotContain("password")
        .doesNotContain("username");
    Configuration loaded = ConfigurationLoader.parse(Map.of("deploy.yaml", split.documents()));
    assertThat(loaded.airlocks()).containsOnlyKeys("hr-assistant");
    assertThat(loaded.mainLlmOf("hr-assistant").url()).isEqualTo("http://localhost:11434");
    assertThat(loaded.dataSourcesOf("hr-assistant"))
        .extracting(DataSource::id)
        .containsExactly("acme_hr", "hr-documents", "payroll");
  }

  @ParameterizedTest
  @ValueSource(strings = {"p#ss", "${NOT_A_VARIABLE}", "  spaced  "})
  void givenPasswordWithYamlSyntax_whenSplit_thenKeptVerbatim(String password) {
    // Arrange: substitution happens in the parsed value, so nothing in it is read as YAML
    Map<String, String> variables =
        Map.of(
            "HR_USERNAME", "reader",
            "HR_PASSWORD", password,
            "DOCS_PASSWORD", "d0cs",
            "PAYROLL_PASSWORD", "p4y");

    // Act
    ProjectDocuments.Split split = ProjectDocuments.split(PROJECT, variables);

    // Assert
    assertThat(
            split
                .credentials()
                .get(ProjectDocuments.dataSourceKey("hr-assistant", "acme_hr"))
                .password())
        .isEqualTo(password);
  }

  @Test
  void givenUnsetVariables_whenSplit_thenEachRefusedByNameAndNoValueEchoed() {
    // Arrange
    Map<String, String> variables =
        Map.of("HR_USERNAME", "reader", "DOCS_PASSWORD", "", "PAYROLL_PASSWORD", "p4y");

    // Act / Assert
    assertThatThrownBy(() -> ProjectDocuments.split(PROJECT, variables))
        .isInstanceOfSatisfying(
            ConfigurationException.class,
            e ->
                assertThat(e.problems())
                    .containsExactly(
                        "variable DOCS_PASSWORD is not set", "variable HR_PASSWORD is not set"));
  }
}
