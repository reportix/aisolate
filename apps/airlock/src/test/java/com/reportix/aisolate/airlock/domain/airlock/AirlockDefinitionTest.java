package com.reportix.aisolate.airlock.domain.airlock;

import static org.assertj.core.api.Assertions.assertThat;

import com.reportix.aisolate.airlock.domain.airlock.AirlockDefinition.HandlerSelection;
import com.reportix.aisolate.config.Configuration;
import com.reportix.aisolate.config.ConfigurationLoader;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The one mapping of an airlock, fed the documents the way the documents catalog feeds them. */
class AirlockDefinitionTest {

  private static Configuration parse(String documents) {
    return ConfigurationLoader.parse(Map.of("documents.yaml", documents));
  }

  @Test
  void givenSmallestAirlock_whenMapped_thenItsSpaceAndTheFixedDefaults() {
    // Arrange: the smallest airlock that runs, no code, no data source
    Configuration configuration =
        parse(
            """
            apiVersion: aisolate.dev/v1
            kind: Space
            metadata: {name: hr-acme}
            spec:
              access: {users: [eric@example.com]}
              llms: [{id: granite, provider: ollama, url: http://localhost:11434, model: m}]
            ---
            apiVersion: aisolate.dev/v1
            kind: Airlock
            metadata: {name: hr-app}
            spec:
              space: hr-acme
              displayName: HR app
              access: {users: [eric@example.com], callers: ["*"]}
              llm: granite
              prompt: p
            """);

    // Act
    AirlockDefinition airlock =
        AirlockDefinition.from(configuration, configuration.airlocks().get("hr-app"));

    // Assert
    assertThat(airlock.id()).isEqualTo("hr-app");
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
  void givenLlmAndDataSourcesOnSpaceAndAirlock_whenMapped_thenResolvedAndAccessInherited() {
    // Arrange: the main LLM on the airlock; data sources on both, hr-documents twice;
    // the space lists eric, the airlock leo and eric again
    Configuration configuration =
        parse(
            """
            apiVersion: aisolate.dev/v1
            kind: Space
            metadata: {name: hr-acme}
            spec:
              access: {users: [eric@example.com]}
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
              description: The pilot.
              access: {users: [leo@example.com, eric@example.com], callers: [acme-chat]}
              preLLM:
                contextPreparator: {handlers: [HandbookPreparator]}
                conversationMemory: {handlers: [SessionMemory]}
              llm: qwen
              prompt: p
              tools: [export_payslips]
              postLLM:
                answerVerifier: {handlers: [SalaryVerifier]}
              llms: [{id: qwen, provider: ollama, url: http://ollama.example.com:11434, model: qwen3.6:27b-q4_K_M}]
              dataSources:
                - {id: hr-documents, url: "jdbc:postgresql://docs.example.com:5432/documents", secretRef: s3}
                - {id: payroll, url: "jdbc:postgresql://db.example.com:5432/payroll", secretRef: s4}
                - {id: archive, url: "jdbc:postgresql://db.example.com:5432/archive", secretRef: s5}
              jar: {digest: "sha256:9f2c", tools: [read_records, export_payslips]}
            """);

    // Act
    AirlockDefinition airlock =
        AirlockDefinition.from(configuration, configuration.airlocks().get("hr-app"));

    // Assert: the space's provider, each data source id once, sorted, the
    // space's users then the airlock's, each once, the airlock's callers, its tools and handlers
    // from its jar
    assertThat(airlock.description()).isEqualTo("The pilot.");
    assertThat(airlock.model()).isEqualTo("ollama");
    assertThat(airlock.dataSources())
        .containsExactly("acme_hr", "archive", "hr-documents", "payroll");
    assertThat(airlock.access().users()).containsExactly("eric@example.com", "leo@example.com");
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
