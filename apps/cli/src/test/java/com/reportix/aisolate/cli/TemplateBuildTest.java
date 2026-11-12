package com.reportix.aisolate.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.reportix.aisolate.config.AirlockVersionDocument;
import com.reportix.aisolate.config.Configuration;
import com.reportix.aisolate.config.ConfigurationLoader;
import com.reportix.aisolate.config.DataSource;
import com.reportix.aisolate.config.Llm;
import com.reportix.aisolate.config.rules.Rule;
import com.reportix.aisolate.config.rules.RuleSet;
import com.reportix.aisolate.sdk.Entity;
import com.reportix.aisolate.sdk.Tool;
import com.reportix.aisolate.sdk.ToolPlugin;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.dataformat.yaml.YAMLMapper;

/** The templates as {@code create} writes them. */
class TemplateBuildTest {

  private static final String SERVICES = "META-INF/services/" + ToolPlugin.class.getName();

  private static final YAMLMapper YAML = YAMLMapper.builder().build();

  /** Where the admin's LLMs and data source live, as far as the test cares: URLs only. */
  private static final String OLLAMA = "http://ollama.aisolate.svc:11434";

  private static final String DATABASE = "jdbc:postgresql://db.aisolate.svc:5432/hr";

  /**
   * What a template promises: its airlock, its code, the tools its version offers and the one data
   * source its code reads, which the admin defines.
   *
   * @param plugin the code's tool plugin, null when it has no tools
   * @param pluginTools the tools of the code
   * @param tools the tools the version's {@code tools} offers
   * @param retrieval the handler that reads the data source itself
   */
  record TemplateSpec(
      String template,
      String airlock,
      String plugin,
      List<String> pluginTools,
      List<String> tools,
      List<String> handlers,
      List<String> contextPreparators,
      List<String> verifiers,
      String promptStart,
      String dataSource,
      String retrieval,
      List<String> rules) {
    @Override
    public String toString() {
      return template + " --airlock=" + airlock;
    }
  }

  static Stream<TemplateSpec> templates() {
    List<String> ledger =
        List.of(
            "read_records",
            "aggregate_records",
            "related_party_payments",
            "invoices_without_contract");
    List<String> ehr =
        List.of(
            "read_cardiology_records",
            "aggregate_cardiology_records",
            "read_psychiatry_records",
            "aggregate_psychiatry_records",
            "read_medications");
    List<String> ehrHandlers =
        List.of(
            "com.aldermoor.ehr.pre_llm.ClinicalNotesContextPreparator",
            "com.aldermoor.ehr.pre_llm.SessionMemory",
            "com.aldermoor.ehr.post_llm.LatestReadingVerifier",
            "com.aldermoor.ehr.post_llm.FormularyVerifier",
            "com.aldermoor.ehr.post_llm.PatientVerifier");
    List<String> ehrRules =
        List.of(
            "cardiology.ecgs",
            "cardiology.labs",
            "cardiology.vitals",
            "ehr.medications",
            "psychiatry.diagnoses",
            "psychiatry.sessions");
    return Stream.of(
        new TemplateSpec(
            "company-hr",
            "hr-contracts",
            null,
            List.of(),
            List.of(),
            List.of(
                "com.acme.hr.pre_llm.ContractsContextPreparator",
                "com.acme.hr.pre_llm.SessionMemory"),
            List.of("ContractsContextPreparator"),
            List.of(),
            "You are the Acme contracts assistant.",
            "hr",
            "com.acme.hr.pre_llm.ContractsContextPreparator",
            List.of()),
        new TemplateSpec(
            "company-sales",
            "sales",
            "com.acme.sales.tools.SalesTools",
            List.of("read_records", "aggregate_records"),
            List.of("read_records", "aggregate_records"),
            List.of(
                "com.acme.sales.pre_llm.TodayContext",
                "com.acme.sales.pre_llm.SalesDocumentsContextPreparator",
                "com.acme.sales.pre_llm.SessionMemory",
                "com.acme.sales.post_llm.CustomerVerifier"),
            List.of("TodayContext", "SalesDocumentsContextPreparator"),
            List.of("CustomerVerifier"),
            "You are the Acme sales assistant for the founders.",
            "clients",
            "com.acme.sales.pre_llm.SalesDocumentsContextPreparator",
            List.of("clients.accounts", "clients.invoices")),
        new TemplateSpec(
            "auditor",
            "northbeck",
            "com.halvardfinch.audit.tools.LedgerTools",
            ledger,
            ledger,
            List.of(
                "com.halvardfinch.audit.pre_llm.AuditDocumentsContextPreparator",
                "com.halvardfinch.audit.pre_llm.SessionMemory",
                "com.halvardfinch.audit.post_llm.PeriodVerifier"),
            List.of("AuditDocumentsContextPreparator"),
            List.of("PeriodVerifier"),
            "You are the Halvard & Finch audit assistant",
            "ledger",
            "com.halvardfinch.audit.pre_llm.AuditDocumentsContextPreparator",
            List.of(
                "ledger.contracts",
                "ledger.invoices",
                "ledger.journal_entries",
                "ledger.related_parties",
                "ledger.reported_figures")),
        new TemplateSpec(
            "hospital",
            "cardiology",
            "com.aldermoor.ehr.tools.EhrTools",
            ehr,
            List.of("read_cardiology_records", "aggregate_cardiology_records", "read_medications"),
            ehrHandlers,
            List.of("ClinicalNotesContextPreparator"),
            List.of("LatestReadingVerifier", "FormularyVerifier", "PatientVerifier"),
            "You are the cardiology assistant of Aldermoor Hospital",
            "ehr",
            "com.aldermoor.ehr.pre_llm.ClinicalNotesContextPreparator",
            ehrRules),
        new TemplateSpec(
            "hospital",
            "psychiatry",
            "com.aldermoor.ehr.tools.EhrTools",
            ehr,
            List.of("read_psychiatry_records", "aggregate_psychiatry_records", "read_medications"),
            ehrHandlers,
            List.of("ClinicalNotesContextPreparator"),
            List.of("LatestReadingVerifier", "FormularyVerifier", "PatientVerifier"),
            "You are the psychiatry assistant of Aldermoor Hospital",
            "ehr",
            "com.aldermoor.ehr.pre_llm.ClinicalNotesContextPreparator",
            ehrRules));
  }

  @TempDir Path parent;

  /** Opt-in: the project's own wrapper builds it against Maven Central, so it needs the network. */
  @ParameterizedTest
  @MethodSource("templates")
  @EnabledIfSystemProperty(named = "aisolate.template.build", matches = "true")
  @Timeout(value = 10, unit = TimeUnit.MINUTES)
  void givenTemplateProject_whenBuilt_thenTheJarHoldsTheClassesAndTheRulesAndNoServicesEntry(
      TemplateSpec spec) throws Exception {
    // Arrange
    Path project = createProject(spec);

    // Act
    Path jar = GradleBuild.build(project);

    // Assert
    assertThat(jar.getFileName()).hasToString(spec.template() + ".jar");
    try (JarFile file = new JarFile(jar.toFile())) {
      Attributes manifest = file.getManifest().getMainAttributes();
      assertThat(manifest.getValue("Implementation-Title")).isEqualTo(spec.template());
      assertThat(manifest.getValue("Implementation-Version")).isEqualTo("0.1.0");
      assertThat(file.getJarEntry(SERVICES)).isNull();
      for (String entity : spec.rules()) {
        assertThat(file.getJarEntry("aisolate/rules/" + entity + ".json")).isNotNull();
      }
      if (spec.plugin() != null) {
        assertThat(file.getJarEntry(spec.plugin().replace('.', '/') + ".class")).isNotNull();
      }
    }
    // What the backend's scan does: the classes over this SDK, each through its no-arg constructor.
    try (URLClassLoader loader =
        new URLClassLoader(new URL[] {jar.toUri().toURL()}, ToolPlugin.class.getClassLoader())) {
      if (spec.plugin() != null) {
        ToolPlugin plugin =
            (ToolPlugin) loader.loadClass(spec.plugin()).getDeclaredConstructor().newInstance();
        assertThat(plugin.tools())
            .extracting(Tool::name)
            .containsExactlyElementsOf(spec.pluginTools());
        assertThat(plugin.dataSource()).isEqualTo(spec.dataSource());
      }
      for (String handler : spec.handlers()) {
        assertThat(loader.loadClass(handler).getDeclaredConstructor().newInstance()).isNotNull();
      }
    }
  }

  @ParameterizedTest
  @MethodSource("templates")
  void
      givenTemplateDocuments_whenSplitAndLoaded_thenOneAirlockAndItsV1NameTheAdminsLlmAndDataSource(
          TemplateSpec spec) throws Exception {
    // Arrange: the file as deploy sends it, and what the backend puts around its version: the code
    // it scanned, the Global, the Space and the Airlock the AIsolate UI keeps, with the LLMs and
    // the data source the template names by id
    Path project = createProject(spec);
    String file = Files.readString(project.resolve(ProjectFile.FILE));
    Map<String, String> variables = ProjectFile.readEnvFile(project.resolve(".env.example"));
    List<String> documents = List.of(file.split("(?m)^---\n"));
    String airlock =
        documents.stream().filter(d -> d.contains("kind: Airlock\n")).findFirst().orElseThrow();
    String version =
        documents.stream()
            .filter(d -> d.contains("kind: AirlockVersion\n"))
            .findFirst()
            .orElseThrow()
            .replace(
                "spec:\n  airlock: " + spec.airlock() + "\n",
                "spec:\n"
                    + "  jar: {digest: \"sha256:0f1e\", tools: ["
                    + String.join(", ", spec.pluginTools())
                    + "]}\n"
                    + "  airlock: "
                    + spec.airlock()
                    + "\n");
    String adminsDocuments =
        """
        apiVersion: aisolate.dev/v1
        kind: Global
        metadata: {name: aisolate}
        spec:
          llms:
            - {id: granite, provider: ollama, url: "%1$s", model: "granite4.1:8b"}
            - {id: nomic, provider: ollama, url: "%1$s", model: nomic-embed-text}
          dataSources: [{id: %2$s, url: "%3$s", secretRef: ds-secret}]
        ---
        apiVersion: aisolate.dev/v1
        kind: Space
        metadata: {name: hr-lab}
        spec:
          access: {roles: [employee]}
        ---
        apiVersion: aisolate.dev/v1
        kind: Airlock
        metadata: {name: %4$s}
        spec:
          space: hr-lab
          displayName: %4$s
          access: {roles: [doctor], callers: ["*"]}
        """
            .formatted(OLLAMA, spec.dataSource(), DATABASE, spec.airlock());

    // Act: the backend saves the project's Airlock as the airlock's row; only the version is loaded
    JsonNode airlockDocument = YAML.readTree(airlock);
    Configuration loaded =
        ConfigurationLoader.parse(Map.of("admin.yaml", adminsDocuments, "aisolate.yaml", version));

    // Assert: the Airlock is the container, nothing that runs; the version, named <airlock>-v1,
    // runs it all, the LLM and the data source by id, no credential
    assertThat(documents).hasSize(2);
    assertThat(airlockDocument.path("metadata").path("name").asString()).isEqualTo(spec.airlock());
    JsonNode airlockSpec = airlockDocument.path("spec");
    assertThat(airlockSpec.path("displayName").asString()).isNotBlank();
    assertThat(airlockSpec.path("description").asString()).isNotBlank();
    assertThat(airlockSpec.propertyNames()).isSubsetOf("displayName", "description", "access");
    assertThat(loaded.airlockVersions()).hasSize(1);
    AirlockVersionDocument v1 = loaded.airlockVersions().values().iterator().next();
    assertThat(v1.name()).isEqualTo(spec.airlock() + "-v1");
    assertThat(v1.spec().version()).isEqualTo("v1");
    assertThat(v1.spec().description()).isNotBlank();
    assertThat(loaded.airlockOf(v1.name()).name()).isEqualTo(spec.airlock());
    assertThat(v1.contextPreparatorHandlers()).containsExactlyElementsOf(spec.contextPreparators());
    assertThat(v1.memoryHandlers()).containsExactly("SessionMemory");
    assertThat(v1.spec().llm()).isEqualTo("granite");
    assertThat(v1.spec().llms()).isEmpty();
    assertThat(loaded.mainLlmOf(v1.name()))
        .isEqualTo(new Llm("granite", "ollama", OLLAMA, "granite4.1:8b"));
    assertThat(v1.spec().prompt()).startsWith(spec.promptStart());
    assertThat(v1.tools()).containsExactlyElementsOf(spec.tools());
    assertThat(v1.verifierHandlers()).containsExactlyElementsOf(spec.verifiers());
    // The admin's, resolved by id: the project names no database and holds no credential.
    assertThat(loaded.dataSourcesOf(v1.name()))
        .containsExactly(new DataSource(spec.dataSource(), DATABASE, "ds-secret"));
    assertThat(variables).containsOnlyKeys("AISOLATE_ENDPOINT", "AISOLATE_SPACE");
    assertThat(file)
        .doesNotContain("${")
        .doesNotContain("username:", "password:", "secretRef", "jar:", "kind: Space");
    // The code sits by stage, the rules at the root, and the code reads the data source above
    Path sources = project.resolve("src/main/java");
    Stream.concat(Stream.ofNullable(spec.plugin()), spec.handlers().stream())
        .forEach(type -> assertThat(sources.resolve(type.replace('.', '/') + ".java")).exists());
    spec.rules()
        .forEach(entity -> assertThat(project.resolve("rules/" + entity + ".json")).exists());
    assertThat(project.resolve("src/main/resources")).doesNotExist();
    assertThat(
            Files.readString(project.resolve("build.gradle.kts"))
                .contains("from(\"rules\") { into(\"aisolate/rules\") }"))
        .isEqualTo(!spec.rules().isEmpty());
    if (spec.plugin() != null) {
      assertThat(Files.readString(sources.resolve(spec.plugin().replace('.', '/') + ".java")))
          .contains("return \"" + spec.dataSource() + "\";");
    }
    assertThat(Files.readString(sources.resolve(spec.retrieval().replace('.', '/') + ".java")))
        .contains("aisolate.dataSource(\"" + spec.dataSource() + "\")")
        .contains("aisolate.llm(\"nomic\").embed(");
  }

  @ParameterizedTest
  @MethodSource("templates")
  void givenTemplateRules_whenReadAgainstItsPlugin_thenOneRuleSetPerEntityNamingOnlyItsColumns(
      TemplateSpec spec) throws Exception {
    // Arrange: the tool server refuses at start a rule set whose entity or column the code lacks
    String classes = System.getProperty("aisolate.templates.classes");
    assumeTrue(classes != null, "the build passes the templates' classes");
    assumeTrue(spec.plugin() != null, "no tools, no rules");
    Map<String, Entity> entities;
    try (URLClassLoader loader =
        new URLClassLoader(urls(classes), ToolPlugin.class.getClassLoader())) {
      ToolPlugin plugin =
          (ToolPlugin) loader.loadClass(spec.plugin()).getDeclaredConstructor().newInstance();
      entities = plugin.entities().stream().collect(Collectors.toMap(Entity::name, e -> e));
    }

    // Act
    List<RuleSet> ruleSets = new ArrayList<>();
    for (String entity : spec.rules()) {
      ruleSets.add(
          RuleSet.parse(
              Files.readString(
                  Path.of("src/main/templates", spec.template(), "rules", entity + ".json"))));
    }

    // Assert
    assertThat(ruleSets).extracting(RuleSet::entity).containsExactlyElementsOf(spec.rules());
    assertThat(entities.keySet()).containsExactlyInAnyOrderElementsOf(spec.rules());
    for (RuleSet ruleSet : ruleSets) {
      for (Rule rule : ruleSet.rules()) {
        assertThat(entities.get(ruleSet.entity()).columns().keySet())
            .as("%s rule %s", ruleSet.entity(), rule.name())
            .containsAll(rule.columns().keySet());
      }
    }
  }

  @ParameterizedTest
  @MethodSource("templates")
  void givenGoldenSet_whenComparedWithTheTemplatesEntities_thenItsEvidenceCarriesTheirLabels(
      TemplateSpec spec) throws Exception {
    // Arrange: the airlock's golden set of this template, its tool results as the tool server sends
    String classes = System.getProperty("aisolate.templates.classes");
    assumeTrue(classes != null, "the build passes the templates' classes");
    assumeTrue(spec.plugin() != null, "no tools, no labelled evidence");
    JsonNode golden =
        JsonMapper.builder()
            .build()
            .readTree(Path.of("../../conformance/cases/grounding", spec.template() + ".json"));
    ToolPlugin plugin;
    try (URLClassLoader loader =
        new URLClassLoader(urls(classes), ToolPlugin.class.getClassLoader())) {
      plugin = (ToolPlugin) loader.loadClass(spec.plugin()).getDeclaredConstructor().newInstance();
    }

    for (JsonNode evidence : golden.path("evidence")) {
      JsonNode labels = evidence.path("labels");
      if (labels.isMissingNode() || !labels.path("function").isNull()) {
        continue; // passages, and aggregates: their labels are the group's
      }
      // Act: what the tool server labels a read of these columns with
      Entity entity = plugin.entity(evidence.path("entity").asString()).orElseThrow();
      List<String> shown = new ArrayList<>();
      evidence.path("columns").forEach(column -> shown.add(column.asString()));
      labels.path("masked").forEach(column -> shown.remove(column.asString()));
      Map<String, String> units = new HashMap<>();
      Map<String, String> unitColumns = new HashMap<>();
      List<String> facts = new ArrayList<>();
      for (String column : shown) {
        if (entity.columns().containsKey(column) && entity.column(column).fact()) {
          facts.add(column);
        }
        if (entity.columns().containsKey(column) && entity.column(column).unit() != null) {
          units.put(column, entity.column(column).unit());
        } else if (entity.columns().containsKey(column)
            && shown.contains(entity.column(column).unitColumn())) {
          unitColumns.put(column, entity.column(column).unitColumn());
        }
      }

      // Assert
      String what = spec.template() + " " + evidence.path("id").asString();
      assertThat(text(labels, "subject"))
          .as(what)
          .isEqualTo(shown.contains(entity.subjectName()) ? entity.subjectName() : null);
      assertThat(text(labels, "period"))
          .as(what)
          .isEqualTo(shown.contains(entity.period()) ? entity.period() : null);
      assertThat(strings(labels.path("units"))).as(what).isEqualTo(units);
      assertThat(strings(labels.path("unit_columns"))).as(what).isEqualTo(unitColumns);
      assertThat(list(labels.path("facts"))).as(what).isEqualTo(facts);
    }
  }

  private static String text(JsonNode node, String field) {
    return node.path(field).isNull() ? null : node.path(field).asString();
  }

  private static Map<String, String> strings(JsonNode object) {
    Map<String, String> strings = new HashMap<>();
    object.propertyNames().forEach(key -> strings.put(key, object.path(key).asString()));
    return strings;
  }

  /** An array's strings; none when the field is absent. */
  private static List<String> list(JsonNode array) {
    List<String> list = new ArrayList<>();
    array.forEach(item -> list.add(item.asString()));
    return list;
  }

  private static URL[] urls(String path) throws IOException {
    List<URL> urls = new ArrayList<>();
    for (String dir : path.split(File.pathSeparator)) {
      urls.add(Path.of(dir).toUri().toURL());
    }
    return urls.toArray(URL[]::new);
  }

  /** The project exactly as {@code create} writes it, SDK jar in {@code libs/}. */
  private Path createProject(TemplateSpec spec) throws IOException {
    Path project = parent.resolve(spec.template());
    Templates.extract(
        Language.java,
        spec.template(),
        CreateCommand.placeholders(spec.template(), "hr-lab", spec.airlock()),
        project);
    Templates.writeSdkJar(project.resolve("libs"));
    return project;
  }
}
