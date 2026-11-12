package com.reportix.aisolate.backend.domain.airlock;

import static org.assertj.core.api.Assertions.assertThat;

import com.reportix.aisolate.backend.domain.deploy.JarInspector;
import com.reportix.aisolate.backend.domain.space.Space;
import com.reportix.aisolate.config.AirlockDocument;
import com.reportix.aisolate.config.AirlockVersionDocument;
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
import tools.jackson.databind.json.JsonMapper;

class DocumentsTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
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
          List.of("nora@example.com"),
          List.of("ceo", "hr"),
          List.of(LlmFormDTO.of(NOMIC)),
          List.of(new DataSourceFormDTO("acme_hr", DB, "hr-space-ds-acme-hr-a1b2c3", null, null)),
          "jasmine",
          null,
          null,
          null,
          null);

  /** A stored airlock of hr-space, its form flat as the row holds it. */
  private static Airlock airlock(AirlockFormDTO form, int versions) {
    return new Airlock(form.id(), "hr-space", 5, versions, "jasmine", null, null, null, form);
  }

  /** A stored version of hr-app, its definition flat as the row holds it. */
  private static AirlockVersionRepository.Row version(
      String version, String definition, JarInspector.Contents jar) {
    return new AirlockVersionRepository.Row(
        "hr-app",
        "hr-space",
        version,
        "Version " + version + ".",
        2,
        "jasmine",
        null,
        null,
        null,
        JSON.readTree(definition),
        jar);
  }

  @Test
  void
      givenSpaceAirlocksAndVersions_whenToDocuments_thenRefsLlmsAndDataSourcesResolveAndJarOnTheDeployedVersion() {
    // Arrange: hr-app has a deployed v1 and a v2 saved in the UI; hr-idle has no version yet
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
    AirlockFormDTO app =
        new AirlockFormDTO(
            "hr-app",
            "HR app",
            "",
            List.of("employee"),
            List.of("acme-chat"),
            List.of("acme-batch"),
            List.of(
                LlmFormDTO.of(
                    new Llm("granite", "ollama", "http://ollama-hr:11434", "granite4.1:8b"))),
            List.of(
                new DataSourceFormDTO(
                    "acme_hr", DB, "hr-space-hr-app-ds-acme-hr-b2c3d4", null, null),
                new DataSourceFormDTO(
                    "hr-documents", DB, "hr-space-hr-app-ds-hr-documents-d4e5f6", null, null)));
    AirlockFormDTO idle =
        new AirlockFormDTO("hr-idle", "HR idle", "", List.of(), List.of("*"), null, null, null);
    String deployed =
        """
        {"llm":"granite","prompt":"p","tools":["read_records"],"verifiers":["SalaryVerifier"],
         "context_preparators":[],"memory_handlers":[],"llms":[]}
        """;
    String drafted =
        """
        {"llm":"qwen","prompt":"q","tools":[],"verifiers":[],"context_preparators":[],
         "memory_handlers":[],
         "llms":[{"id":"qwen","provider":"ollama","url":"http://ollama:11434","model":"qwen3"}]}
        """;

    // Act
    List<Object> documents = new ArrayList<>();
    documents.add(GLOBAL);
    documents.addAll(
        Documents.toDocuments(
            SPACE,
            List.of(airlock(app, 2), airlock(idle, 0)),
            List.of(version("v1", deployed, jar), version("v2", drafted, null))));
    Configuration configuration =
        ConfigurationLoader.parse(Map.of("documents.yaml", ConfigurationLoader.toYaml(documents)));

    // Assert: the loader accepts the four kinds, an airlock without a version included
    assertThat(configuration.global()).isEqualTo(GLOBAL);
    SpaceDocument.Spec space = configuration.spaces().get("hr-space").spec();
    assertThat(space.displayName()).isEqualTo("HR");
    // The roles ask; developers are the UI's and never reach the cluster.
    assertThat(space.access()).isEqualTo(new SpaceDocument.Access(List.of("ceo", "hr")));
    assertThat(space.llms()).containsExactly(NOMIC);
    assertThat(space.dataSources())
        .containsExactly(new DataSource("acme_hr", DB, "hr-space-ds-acme-hr-a1b2c3"));
    AirlockDocument airlock = configuration.airlocks().get("hr-app");
    assertThat(airlock.spec().space()).isEqualTo("hr-space");
    assertThat(airlock.spec().access())
        .isEqualTo(
            new AirlockDocument.Access(
                List.of("employee"), List.of("acme-chat"), List.of("acme-batch")));
    AirlockVersionDocument v1 = configuration.airlockVersions().get("hr-app-v1");
    assertThat(v1.spec().airlock()).isEqualTo("hr-app");
    assertThat(v1.spec().version()).isEqualTo("v1");
    assertThat(v1.spec().description()).isEqualTo("Version v1.");
    assertThat(v1.spec().llm()).isEqualTo("granite");
    assertThat(v1.spec().prompt()).isEqualTo("p");
    assertThat(v1.tools()).containsExactly("read_records");
    assertThat(v1.verifierHandlers()).containsExactly("SalaryVerifier");
    assertThat(v1.spec().jar())
        .isEqualTo(
            new AirlockVersionDocument.Jar(
                "sha256:ab", List.of("read_records", "aggregate_records"), null));
    // The airlock's granite replaces the global one; v2's qwen is its own, keyless
    assertThat(configuration.mainLlmOf("hr-app-v1").url()).isEqualTo("http://ollama-hr:11434");
    assertThat(configuration.llmsOf("hr-app-v1"))
        .extracting(Llm::id)
        .containsExactly("granite", "nomic");
    assertThat(configuration.mainLlmOf("hr-app-v2").id()).isEqualTo("qwen");
    assertThat(configuration.mainLlmOf("hr-app-v2").secretRef()).isNull();
    assertThat(configuration.airlockVersions().get("hr-app-v2").hasCode()).isFalse();
    assertThat(configuration.dataSourcesOf("hr-app-v1"))
        .containsExactly(
            new DataSource("acme_hr", DB, "hr-space-hr-app-ds-acme-hr-b2c3d4"),
            new DataSource("audit", DB, "aisolate-ds-audit-e5f6a7"),
            new DataSource("hr-documents", DB, "hr-space-hr-app-ds-hr-documents-d4e5f6"));
    AirlockDocument idleDocument = configuration.airlocks().get("hr-idle");
    assertThat(idleDocument.spec().access())
        .isEqualTo(new AirlockDocument.Access(List.of(), List.of("*")));
    assertThat(idleDocument.spec().llms()).isEmpty();
    assertThat(configuration.versionsOf("hr-idle")).isEmpty();
  }
}
