package com.reportix.aisolate.backend.domain.airlock;

import static org.assertj.core.api.Assertions.assertThat;

import com.reportix.aisolate.backend.domain.deploy.JarInspector;
import com.reportix.aisolate.backend.domain.space.Space;
import com.reportix.aisolate.backend.domain.space.SpaceFormDTO;
import com.reportix.aisolate.config.AirlockDocument;
import com.reportix.aisolate.config.Configuration;
import com.reportix.aisolate.config.ConfigurationLoader;
import com.reportix.aisolate.config.DataSource;
import com.reportix.aisolate.config.GlobalDocument;
import com.reportix.aisolate.config.Llm;
import com.reportix.aisolate.config.Metadata;
import com.reportix.aisolate.config.SpaceDocument;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DocumentsTest {

  private static final String DB = "jdbc:postgresql://db:5432/hr";

  private static final Llm GRANITE =
      new Llm("granite", "ollama", "http://ollama:11434", "granite4.1:8b");
  private static final Llm NOMIC =
      new Llm("nomic", "ollama", "http://ollama:11434", "nomic-embed-text");

  private static final GlobalDocument GLOBAL =
      new GlobalDocument(
          new Metadata("aisolate"),
          new GlobalDocument.Spec(
              List.of(GRANITE), List.of(new DataSource("audit", DB, "aisolate-ds-audit-e5f6a7"))));

  private static final Space SPACE =
      new Space(
          "hr-space",
          "HR",
          "People data.",
          List.of(
              new SpaceFormDTO.Member("eric@example.com", SpaceFormDTO.USER),
              new SpaceFormDTO.Member("nora@example.com", SpaceFormDTO.DEVELOPER)),
          List.of(LlmFormDTO.of(NOMIC)),
          List.of(new DataSourceFormDTO("acme_hr", DB, "hr-space-ds-acme-hr-a1b2c3", null, null)),
          "jasmine",
          null,
          null,
          null,
          null);

  /** A stored airlock of hr-space, its form flat as the row holds it. */
  private static Airlock airlock(AirlockFormDTO form, JarInspector.Contents jar) {
    return new Airlock(
        form.id(),
        "hr-space",
        5,
        "http://localhost:8080/v1/spaces/hr-space/airlocks/" + form.id() + "/chat",
        "jasmine",
        null,
        null,
        null,
        form,
        null,
        jar);
  }

  @Test
  void
      givenGlobalSpaceAndAirlocksWithAndWithoutCode_whenToDocuments_thenRefsLlmsAndDataSourcesResolveAndJarOnTheDeployedOne() {
    // Arrange: hr-app was deployed; hr-idle was made in the UI and runs nothing yet
    JarInspector.Contents jar =
        new JarInspector.Contents(
            "sha256:ab",
            10,
            List.of(
                new JarInspector.Tool("read_records", "", "hr-tools", "acme_hr"),
                new JarInspector.Tool("aggregate_records", "", "hr-tools", "acme_hr")),
            List.of(new JarInspector.Handler("SalaryVerifier", "verifier", 1, null, List.of())),
            List.of(new JarInspector.RuleSet("hr.compensation", 2)),
            "java");
    AirlockFormDTO deployed =
        new AirlockFormDTO(
            "hr-app",
            "HR app",
            "",
            List.of("eric@example.com"),
            List.of("acme-chat"),
            List.of("acme-batch"),
            null,
            null,
            "granite",
            "p",
            List.of("read_records"),
            List.of("SalaryVerifier"),
            List.of(
                LlmFormDTO.of(
                    new Llm("granite", "ollama", "http://ollama-hr:11434", "granite4.1:8b"))),
            List.of(
                new DataSourceFormDTO(
                    "acme_hr", DB, "hr-space-hr-app-ds-acme-hr-b2c3d4", null, null),
                new DataSourceFormDTO(
                    "hr-documents", DB, "hr-space-hr-app-ds-hr-documents-d4e5f6", null, null)));
    AirlockFormDTO idle =
        new AirlockFormDTO(
            "hr-idle",
            "HR idle",
            "",
            List.of("eric@example.com", "nora@example.com"),
            List.of("*"),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null);

    // Act
    List<Object> documents = new ArrayList<>();
    documents.add(GLOBAL);
    documents.addAll(
        Documents.toDocuments(SPACE, List.of(airlock(deployed, jar), airlock(idle, null))));
    Configuration configuration =
        ConfigurationLoader.parse(Map.of("documents.yaml", ConfigurationLoader.toYaml(documents)));

    // Assert: the loader accepts the three kinds, an airlock that runs nothing included
    assertThat(configuration.global()).isEqualTo(GLOBAL);
    SpaceDocument.Spec space = configuration.spaces().get("hr-space").spec();
    assertThat(space.displayName()).isEqualTo("HR");
    // Every member asks, the developer nora too; no role reaches the cluster.
    assertThat(space.access())
        .isEqualTo(new SpaceDocument.Access(List.of("eric@example.com", "nora@example.com")));
    assertThat(space.llms()).containsExactly(NOMIC);
    assertThat(space.dataSources())
        .containsExactly(new DataSource("acme_hr", DB, "hr-space-ds-acme-hr-a1b2c3"));
    AirlockDocument app = configuration.airlocks().get("hr-app");
    assertThat(app.spec().space()).isEqualTo("hr-space");
    assertThat(app.spec().access())
        .isEqualTo(
            new AirlockDocument.Access(
                List.of("eric@example.com"), List.of("acme-chat"), List.of("acme-batch")));
    assertThat(app.spec().llm()).isEqualTo("granite");
    assertThat(app.spec().prompt()).isEqualTo("p");
    assertThat(app.tools()).containsExactly("read_records");
    assertThat(app.verifierHandlers()).containsExactly("SalaryVerifier");
    assertThat(app.spec().jar())
        .isEqualTo(
            new AirlockDocument.Jar(
                "sha256:ab", List.of("read_records", "aggregate_records"), null));
    // The airlock's granite replaces the global one
    assertThat(configuration.mainLlmOf("hr-app").url()).isEqualTo("http://ollama-hr:11434");
    assertThat(configuration.llmsOf("hr-app"))
        .extracting(Llm::id)
        .containsExactly("granite", "nomic");
    assertThat(configuration.dataSourcesOf("hr-app"))
        .containsExactly(
            new DataSource("acme_hr", DB, "hr-space-hr-app-ds-acme-hr-b2c3d4"),
            new DataSource("audit", DB, "aisolate-ds-audit-e5f6a7"),
            new DataSource("hr-documents", DB, "hr-space-hr-app-ds-hr-documents-d4e5f6"));
    AirlockDocument idleDocument = configuration.airlocks().get("hr-idle");
    assertThat(idleDocument.spec().access())
        .isEqualTo(
            new AirlockDocument.Access(
                List.of("eric@example.com", "nora@example.com"), List.of("*")));
    assertThat(idleDocument.spec().llms()).isEmpty();
    assertThat(idleDocument.spec().llm()).isNull();
    assertThat(idleDocument.spec().prompt()).isNull();
    assertThat(idleDocument.hasCode()).isFalse();
    assertThat(configuration.mainLlmOf("hr-idle")).isNull();
  }
}
