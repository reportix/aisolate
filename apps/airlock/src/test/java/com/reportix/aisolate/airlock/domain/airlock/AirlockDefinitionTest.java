package com.reportix.aisolate.airlock.domain.airlock;

import static org.assertj.core.api.Assertions.assertThat;

import com.reportix.aisolate.airlock.domain.airlock.AirlockDefinition.HandlerSelection;
import com.reportix.aisolate.config.Configuration;
import com.reportix.aisolate.config.ConfigurationLoader;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The one mapping of a version, fed the documents the way the documents catalog feeds them. */
class AirlockDefinitionTest {

  private static Configuration parse(String documents) {
    return ConfigurationLoader.parse(Map.of("documents.yaml", documents));
  }

  @Test
  void givenSmallestVersion_whenMapped_thenItsAirlockAndSpaceAndTheFixedDefaults() {
    // Arrange: the smallest version a document may carry, no code, no data source
    Configuration configuration =
        parse(
            """
            apiVersion: aisolate.dev/v1
            kind: Space
            metadata: {name: hr-acme}
            spec:
              access: {roles: [employee]}
              llms: [{id: granite, provider: ollama, url: http://localhost:11434, model: m}]
            ---
            apiVersion: aisolate.dev/v1
            kind: Airlock
            metadata: {name: hr-app}
            spec:
              space: hr-acme
              displayName: HR app
              access: {roles: [employee], callers: ["*"]}
            ---
            apiVersion: aisolate.dev/v1
            kind: AirlockVersion
            metadata: {name: hr-app-v1}
            spec:
              airlock: hr-app
              version: v1
              llm: granite
              prompt: p
            """);

    // Act
    AirlockDefinition airlock =
        AirlockDefinition.from(configuration, configuration.airlockVersions().get("hr-app-v1"));

    // Assert
    assertThat(airlock.id()).isEqualTo("hr-app");
    assertThat(airlock.version()).isEqualTo("v1");
    assertThat(airlock.description()).isEmpty();
    assertThat(airlock.revision()).isEqualTo(configuration.revision());
    assertThat(airlock.spaceId()).isEqualTo("hr-acme");
    assertThat(airlock.name()).isEqualTo("HR app");
    assertThat(airlock.model()).isEqualTo("ollama");
    assertThat(airlock.systemPrompt()).isEqualTo("p");
    assertThat(airlock.tools()).isEmpty();
    assertThat(airlock.dataSources()).isEmpty();
    assertThat(airlock.handlers()).isEqualTo(new HandlerSelection(null, null, null, null));
  }

  @Test
  void givenLlmAndDataSourcesOnSpaceAirlockAndVersion_whenMapped_thenResolvedAndAccessInherited() {
    // Arrange: the main LLM on the version; data sources on the space and the airlock, hr-documents
    // twice; the space lists employee, the airlock founder and employee again
    Configuration configuration =
        parse(
            """
            apiVersion: aisolate.dev/v1
            kind: Space
            metadata: {name: hr-acme}
            spec:
              access: {roles: [employee]}
              llms: [{id: granite, provider: ollama, url: http://ollama.example.com:11434, model: granite4.1:8b}]
              dataSources:
                - {id: acme_hr, url: "jdbc:postgresql://db.example.com:5432/acme_hr", secretRef: s1}
                - {id: hr-documents, url: "jdbc:postgresql://db.example.com:5432/acme_hr", secretRef: s2}
            ---
            apiVersion: aisolate.dev/v1
            kind: Airlock
            metadata: {name: hr-app}
            spec:
              space: hr-acme
              displayName: HR app
              access: {roles: [founder, employee], callers: [acme-chat]}
              dataSources:
                - {id: hr-documents, url: "jdbc:postgresql://docs.example.com:5432/documents", secretRef: s3}
                - {id: payroll, url: "jdbc:postgresql://db.example.com:5432/payroll", secretRef: s4}
            ---
            apiVersion: aisolate.dev/v1
            kind: AirlockVersion
            metadata: {name: hr-app-pilot}
            spec:
              airlock: hr-app
              version: pilot
              description: The pilot.
              preLLM:
                contextPreparator: {handlers: [HandbookPreparator]}
                conversationMemory: {handlers: [SessionMemory]}
              llm: qwen
              prompt: p
              tools: [export_payslips]
              postLLM:
                answerVerifier: {handlers: [SalaryVerifier]}
              llms: [{id: qwen, provider: ollama, url: http://ollama.example.com:11434, model: qwen3.6:27b-q4_K_M}]
              jar: {digest: "sha256:9f2c", tools: [read_records, export_payslips]}
            """);

    // Act
    AirlockDefinition airlock =
        AirlockDefinition.from(configuration, configuration.airlockVersions().get("hr-app-pilot"));

    // Assert: the version's own provider, each data source id once, sorted, the space's roles then
    // the airlock's, each once, the airlock's callers, the version's tools and handlers from its
    // jar
    assertThat(airlock.version()).isEqualTo("pilot");
    assertThat(airlock.description()).isEqualTo("The pilot.");
    assertThat(airlock.model()).isEqualTo("ollama");
    assertThat(airlock.dataSources()).containsExactly("acme_hr", "hr-documents", "payroll");
    assertThat(airlock.access().roles()).containsExactly("employee", "founder");
    assertThat(airlock.access().callers()).containsExactly("acme-chat");
    assertThat(airlock.tools()).containsExactly("export_payslips");
    assertThat(airlock.handlers())
        .isEqualTo(
            new HandlerSelection(
                "sha256:9f2c",
                List.of("HandbookPreparator"),
                List.of("SessionMemory"),
                List.of("SalaryVerifier")));
  }
}
