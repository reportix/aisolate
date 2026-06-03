package com.reportix.aisolate.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.tuple;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

class ConfigurationLoaderTest {

  private static final Path FIXTURES = Path.of("src/test/resources/documents");
  private static final String DB_URL = "jdbc:postgresql://acme-db.aisolate.svc:5432/acme_hr";
  private static final String LLM_URL = "http://ollama.aisolate.svc:11434";
  private static final String FOUNDERS_LLM_URL = "http://ollama-founders.aisolate.svc:11434";
  private static final Llm GRANITE = new Llm("granite", "ollama", LLM_URL, "granite4.1:8b");
  private static final Llm NOMIC = new Llm("nomic", "ollama", LLM_URL, "nomic-embed-text:v1.5");

  @Test
  void givenFixtureDirectory_whenLoad_thenThreeKindsResolved() {
    // Arrange / Act
    Configuration configuration = ConfigurationLoader.load(FIXTURES);

    // Assert
    assertThat(configuration.global().name()).isEqualTo("aisolate");
    assertThat(configuration.global().spec().llms())
        .containsExactly(GRANITE, new Llm("nomic", "ollama", LLM_URL, "nomic-embed-text"));
    assertThat(configuration.spaces()).containsOnlyKeys("hr-acme");
    assertThat(configuration.airlocks()).containsOnlyKeys("hr-assistant", "founders-finance");
    SpaceDocument space = configuration.spaces().get("hr-acme");
    assertThat(space.spec().description())
        .isEqualTo("Acme people and compensation data for the HR assistant.");
    assertThat(space.spec().access())
        .isEqualTo(new SpaceDocument.Access(List.of("eric@example.com", "nora@example.com")));
    assertThat(space.spec().llms()).containsExactly(NOMIC);
    assertThat(space.spec().dataSources())
        .containsExactly(new DataSource("acme_hr", DB_URL, "hr-acme-ds-acme-hr-a1b2c3"));
    AirlockDocument airlock = configuration.airlocks().get("hr-assistant");
    assertThat(airlock.displayName()).isEqualTo("Acme HR assistant");
    assertThat(airlock.spec().space()).isEqualTo("hr-acme");
    assertThat(airlock.spec().description())
        .isEqualTo("Answers people, pay and HR document questions.");
    assertThat(airlock.spec().access())
        .isEqualTo(
            new AirlockDocument.Access(
                List.of("eric@example.com", "nora@example.com"), List.of("*")));
    assertThat(airlock.spec().llms()).isEmpty();
    assertThat(configuration.spaceOf("hr-assistant").name()).isEqualTo("hr-acme");
    assertThat(airlock.contextPreparatorHandlers()).containsExactly("HrDocumentsContextPreparator");
    assertThat(airlock.memoryHandlers()).containsExactly("SessionMemory");
    assertThat(airlock.spec().llm()).isEqualTo("granite");
    assertThat(airlock.spec().prompt())
        .isEqualTo("You are the Acme HR assistant. All amounts are in EUR.\n");
    assertThat(airlock.tools()).containsExactly("read_records", "aggregate_records");
    assertThat(airlock.verifierHandlers()).containsExactly("SalaryVerifier");
    assertThat(airlock.spec().dataSources())
        .containsExactly(
            new DataSource("hr-documents", DB_URL, "hr-acme-hr-assistant-ds-hr-documents-d4e5f6"));
    assertThat(airlock.spec().jar())
        .isEqualTo(
            new AirlockDocument.Jar(
                "sha256:9f2c", List.of("read_records", "aggregate_records"), null));
    assertThat(airlock.hasCode()).isTrue();
    assertThat(configuration.llmsOf("hr-assistant")).containsExactly(GRANITE, NOMIC);
    assertThat(configuration.mainLlmOf("hr-assistant")).isEqualTo(GRANITE);
    assertThat(configuration.dataSourcesOf("hr-assistant"))
        .extracting(DataSource::id)
        .containsExactly("acme_hr", "audit", "hr-documents");
    AirlockDocument founders = configuration.airlocks().get("founders-finance");
    assertThat(founders.hasCode()).isFalse();
    assertThat(founders.tools()).isEmpty();
    assertThat(configuration.llmsOf("founders-finance"))
        .containsExactly(
            new Llm("granite", "ollama", FOUNDERS_LLM_URL, "granite4.1:8b"),
            NOMIC,
            new Llm("qwen", "ollama", FOUNDERS_LLM_URL, "qwen3.6:27b-q4_K_M"));
    assertThat(configuration.mainLlmOf("founders-finance").id()).isEqualTo("qwen");
  }

  @ParameterizedTest
  @CsvSource({"'',false", "', language: java',false", "', language: python',true"})
  void givenAJarLanguage_whenParsed_thenPythonOnlyForPython(String language, boolean python)
      throws IOException {
    // Arrange
    Map<String, String> files = read(FIXTURES);
    files.computeIfPresent(
        "airlocks.yaml",
        (file, text) -> text.replace("aggregate_records]}", "aggregate_records]" + language + "}"));

    // Act
    Configuration loaded = ConfigurationLoader.parse(files);

    // Assert
    assertThat(loaded.airlocks().get("hr-assistant").spec().jar().python()).isEqualTo(python);
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        "global.yaml|nomic-embed-text}|nomic-embed-text:v2}",
        "space.yaml|HR Acme|HR Acme!",
        "airlocks.yaml|Founders finance|Founders' finance",
        "airlocks.yaml|You answer the founders only.|You answer the founders.",
        "airlocks.yaml|aggregate_records]}|aggregate_records], language: python}"
      })
  void givenAChangedValueInAnyKind_whenParsed_thenAnotherRevision(
      String file, String from, String to) throws IOException {
    // Arrange
    Configuration original = ConfigurationLoader.load(FIXTURES);
    Map<String, String> files = read(FIXTURES);
    files.put(file, files.get(file).replace(from, to));

    // Act
    Configuration changed = ConfigurationLoader.parse(files);

    // Assert
    assertThat(changed.revision()).isNotEqualTo(original.revision());
  }

  @Test
  void givenSameDocumentsDifferentFormatting_whenParsed_thenSameRevision() throws IOException {
    // Arrange: the same documents, other file names, quotes, comments and blank lines; a release
    // rendered by the controller and one validated in CI must agree on the revision by name
    Configuration original = ConfigurationLoader.load(FIXTURES);
    Map<String, String> files = new LinkedHashMap<>();
    files.put(
        "zz-space.yaml",
        "# reformatted\n\n"
            + read(FIXTURES)
                .get("space.yaml")
                .replace("displayName: HR Acme", "displayName: \"HR Acme\""));
    files.put("a-airlocks.yaml", read(FIXTURES).get("airlocks.yaml"));
    files.put("b-global.yaml", read(FIXTURES).get("global.yaml"));

    // Act
    Configuration reformatted = ConfigurationLoader.parse(files);

    // Assert
    assertThat(reformatted.revision()).isEqualTo(original.revision()).matches("[0-9a-f]{12}");
  }

  @Test
  void givenDuplicateAirlockName_whenLoad_thenRejectedNamingBothFiles() throws IOException {
    // Arrange
    Map<String, String> files = read(FIXTURES);
    files.put("again.yaml", files.get("airlocks.yaml"));

    // Act / Assert
    assertThatThrownBy(() -> ConfigurationLoader.parse(files))
        .isInstanceOf(ConfigurationException.class)
        .hasMessageContaining("hr-assistant")
        .hasMessageContaining("airlocks.yaml")
        .hasMessageContaining("again.yaml");
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        "kind: Airlock|kind: Pipeline|unknown kind Pipeline",
        "apiVersion: aisolate.dev/v1|apiVersion: ''|apiVersion  is not aisolate.dev/v1",
        "apiVersion: aisolate.dev/v1|apiVersion: aisolate.dev/v1alpha1|apiVersion"
            + " aisolate.dev/v1alpha1 is not aisolate.dev/v1",
        "displayName: Founders finance|displayNam: Founders finance|displayNam",
        "name: hr-assistant}|name: Hr-Assistant}|metadata.name 'Hr-Assistant': use 2 to 41",
        // Kubernetes refuses the name, so every sync of its space would fail.
        "name: hr-assistant}|name: hr-assistant-}|metadata.name 'hr-assistant-': use 2 to 41",
        "space: hr-acme|space: \" \"|hr-assistant: spec.space is required",
        // The space's users sit in a block, the airlocks' in a flow: the block is the space's
        // alone.
        "'access:\n    users: [eric@example.com, nora@example.com]'"
            + "|'access:\n    users: [eric@example.com, Eric@Example.Com]'"
            + "|hr-acme: spec.access.users lists eric@example.com twice",
        // A removed field stays refused: the roles are gone.
        "callers: [\"*\"]}|callers: [\"*\"], roles: [ceo]}|hr-assistant: Unrecognized property"
            + " \"roles\"",
        // The airlocks come before the space in file order: the first match is hr-assistant's.
        "users: [eric@example.com, nora@example.com]|users: [eric@example.com,"
            + " Eric@Example.Com]|hr-assistant: spec.access.users lists eric@example.com"
            + " twice",
        "callers: [\"*\"]|callers: []|hr-assistant: spec.access.callers must name at least one"
            + " calling client",
        "callers: [\"*\"]|callers: [\"*\", \" \"]|hr-assistant: spec.access.callers must not"
            + " contain a blank client",
        // Machine clients match exactly: a * would read as any client
        "callers: [\"*\"]}|callers: [\"*\"], clients: [\"*\"]}|hr-assistant:"
            + " spec.access.clients * is no wildcard: list each client id",
        "callers: [\"*\"]}|callers: [\"*\"], clients: [\" \"]}|hr-assistant:"
            + " spec.access.clients must not contain a blank client id",
        "callers: [\"*\"]}|callers: [\"*\"], clients: [\"kn ime\"]}|hr-assistant:"
            + " spec.access.clients \"kn ime\" has a space in it",
        "provider: ollama|provider: gpt|founders-finance: spec.llms[0].provider gpt is not"
            + " one of [ollama, openai]",
        "provider: ollama|provider: \" \"|founders-finance: spec.llms[0].provider is required",
        "url: http://ollama.aisolate.svc:11434|url: http://ollama.aisolate.svc:11434/?apiKey=x"
            + "|Global aisolate: spec.llms[0].url carries the parameter apiKey",
        "url: http://ollama.aisolate.svc:11434|url: ollama|Global aisolate: spec.llms[0].url has"
            + " no host",
        "url: http://ollama.aisolate.svc:11434|url: \" \"|Global aisolate: spec.llms[0].url is"
            + " required",
        "- {id: nomic, provider|- {id: granite, provider|Global aisolate: spec.llms[1].id"
            + " granite is listed twice",
        "{id: qwen,|{id: Qwen,|founders-finance: spec.llms[0].id Qwen: 1 to 41 lowercase"
            + " letters, digits, _ or -, starting with a letter or digit",
        "{id: qwen, |{|founders-finance: spec.llms[0].id is required",
        // A document names the Secret of a key, never the key
        "model: nomic-embed-text}|model: nomic-embed-text, apiKey: x}|Unrecognized property"
            + " \"apiKey\"",
        "id: acme_hr|id: \" \"|hr-acme: spec.dataSources[0].id is required",
        "id: acme_hr|id: Acme_HR|hr-acme: spec.dataSources[0].id Acme_HR: 1 to 41"
            + " lowercase letters, digits, _ or -, starting with a letter or digit",
        "url: http://ollama-founders.aisolate.svc:11434, model: \"granite4.1:8b\"}|url:"
            + " http://ollama-founders.aisolate.svc:11434, model: \" \"}|founders-finance:"
            + " spec.llms[1].model is required",
        "handlers: [HrDocumentsContextPreparator]|handlers: [\" \"]|hr-assistant:"
            + " spec.preLLM.contextPreparator.handlers must not contain a blank name",
        "handlers: [SessionMemory]|handlers: [SessionMemory, SessionMemory]|hr-assistant:"
            + " spec.preLLM.conversationMemory.handlers lists SessionMemory twice",
        "handlers: [SalaryVerifier]|handlers: [\" \"]|hr-assistant:"
            + " spec.postLLM.answerVerifier.handlers must not contain a blank name",
        "url: http://ollama-founders.aisolate.svc:11434, model: \"qwen|url:"
            + " http://localhost:11434/?apiKey=x, model: \"qwen|founders-finance:"
            + " spec.llms[0].url carries the parameter apiKey",
        "tools: [read_records, aggregate_records]|tools: [read_records, \" \"]|hr-assistant:"
            + " spec.tools must not contain a blank name",
        "id: hr-documents|id: _hr-documents|hr-assistant: spec.dataSources[0].id _hr-documents:"
            + " 1 to 41 lowercase letters, digits, _ or -, starting with a letter or digit",
        "url: jdbc:postgresql://acme-db.aisolate.svc:5432/acme_hr|url: acme-db"
            + "|hr-assistant: spec.dataSources[0].url has no host",
        "digest: \"sha256:9f2c\"|digest: \" \"|hr-assistant: spec.jar.digest is required",
        "digest: \"sha256:9f2c\"|digest: \"sha256:9f2c\", language: ruby|hr-assistant:"
            + " spec.jar.language ruby is not java or python",
        "tools: [read_records, aggregate_records]|tools: [read_records, export_payslips]"
            + "|hr-assistant: spec.tools export_payslips is not a tool of this airlock's code",
        "tools: []|tools: [read_records]|founders-finance: lists tools or handlers but has no"
            + " jar",
        "llm: granite|llm: gemma|hr-assistant: llm gemma is no model of it, of Space hr-acme or"
            + " of AIsolate",
        // The last of two lists would load, silently
        "'    users: [eric@example.com, nora@example.com]'|'    users: [eric@example.com]\n"
            + "    users: [nora@example.com]'|Duplicate Object property \"users\"",
        // Never the token's e-mail, so it would admit no one
        "users: [eric@example.com, nora@example.com]|users: [\" eric@example.com\"]"
            + "|\" eric@example.com\" has a space in it"
      })
  void givenBrokenDocument_whenLoad_thenProblemNamesDocumentAndField(
      String from, String to, String expected) throws IOException {
    // Arrange: one edit to the fixtures, applied to the first file that carries the text
    Map<String, String> files = read(FIXTURES);
    for (Map.Entry<String, String> file : files.entrySet()) {
      if (file.getValue().contains(from)) {
        file.setValue(file.getValue().replaceFirst(Pattern.quote(from), to));
        break;
      }
    }

    // Act / Assert
    assertThatThrownBy(() -> ConfigurationLoader.parse(files))
        .isInstanceOf(ConfigurationException.class)
        .hasMessageContaining(expected);
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        "space.yaml|hr-acme-ds-acme-hr-a1b2c3|hr-acme",
        "airlocks.yaml|hr-acme-hr-assistant-ds-hr-documents-d4e5f6|hr-assistant"
      })
  void givenUrlWithPassword_whenLoad_thenTheProblemNeverEchoesIt(
      String file, String secretRef, String document) throws IOException {
    // Arrange: a data source URL that smuggles a password into the documents
    Map<String, String> files = read(FIXTURES);
    files.put(
        file,
        files
            .get(file)
            .replaceFirst(
                "5432/acme_hr(\\s+secretRef: " + secretRef + ")",
                "5432/acme_hr?password=hunter2$1"));

    // Act / Assert
    assertThatThrownBy(() -> ConfigurationLoader.parse(files))
        .isInstanceOf(ConfigurationException.class)
        .hasMessageContaining(document + ": spec.dataSources[0].url carries the parameter password")
        .hasMessageNotContaining("hunter2");
  }

  @Test
  void givenAirlockWithoutLlmOrPrompt_whenLoad_thenAcceptedAndRunsNothing() throws IOException {
    // Arrange: the AIsolate UI creates an airlock before its first deploy
    Map<String, String> files = read(FIXTURES);
    files.put(
        "onboarding.yaml",
        "{apiVersion: aisolate.dev/v1, kind: Airlock, metadata: {name: onboarding},"
            + " spec: {space: hr-acme, displayName: Onboarding,"
            + " access: {users: [eric@example.com], callers: [\"*\"]}}}");

    // Act
    Configuration configuration = ConfigurationLoader.parse(files);

    // Assert
    AirlockDocument onboarding = configuration.airlocks().get("onboarding");
    assertThat(onboarding.hasCode()).isFalse();
    assertThat(onboarding.tools()).isEmpty();
    assertThat(configuration.mainLlmOf("onboarding")).isNull();
    assertThat(configuration.llmsOf("onboarding")).containsExactly(GRANITE, NOMIC);
  }

  @Test
  void givenSpaceWithoutUsers_whenLoad_thenAccepted() throws IOException {
    // Arrange: the AIsolate UI creates a space by name and description, users come afterwards
    Map<String, String> files = read(FIXTURES);
    files.put(
        "empty.yaml",
        "{apiVersion: aisolate.dev/v1, kind: Space, metadata: {name: empty-space},"
            + " spec: {displayName: Empty space, access: {users: []}}}");

    // Act
    Configuration configuration = ConfigurationLoader.parse(files);

    // Assert
    assertThat(configuration.spaces().get("empty-space").spec().access().users()).isEmpty();
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        "[]|''",
        "[ivy@example.com]|ivy@example.com",
        // the space lists eric@: admission ignores the case, the document keeps it as written
        "[Eric@Example.Com]|Eric@Example.Com"
      })
  void givenAirlockUsersNoneNotOnTheSpaceOrInAnotherCase_whenLoad_thenAccepted(
      String users, String kept) throws IOException {
    // Arrange: a space's users, then an airlock's own
    Map<String, String> files = read(FIXTURES);
    files.put(
        "airlocks.yaml",
        files
            .get("airlocks.yaml")
            .replaceFirst("users: \\[eric@example.com, nora@example.com\\]", "users: " + users));

    // Act
    Configuration configuration = ConfigurationLoader.parse(files);

    // Assert
    assertThat(configuration.airlocks().get("hr-assistant").spec().access().users())
        .isEqualTo(kept.isEmpty() ? List.of() : List.of(kept));
  }

  @Test
  void givenAirlockWithClients_whenLoad_thenItsAccessCarriesThemAndOnlyItsDocumentsNameThem()
      throws IOException {
    // Arrange: a machine client on hr-assistant only
    Map<String, String> files = read(FIXTURES);
    files.put(
        "airlocks.yaml",
        files
            .get("airlocks.yaml")
            .replaceFirst(
                Pattern.quote("callers: [\"*\"]}"), "callers: [\"*\"], clients: [knime]}"));

    // Act
    Configuration configuration = ConfigurationLoader.parse(files);
    List<DiscoveredAirlock> discovered = DiscoveredAirlock.of(configuration);

    // Assert
    assertThat(configuration.accessOf("hr-assistant").clients()).containsExactly("knime");
    assertThat(configuration.accessOf("founders-finance").clients()).isEmpty();
    assertThat(ConfigurationLoader.toYaml(List.of(configuration.airlocks().get("hr-assistant"))))
        .contains("clients:");
    assertThat(
            ConfigurationLoader.toYaml(List.of(configuration.airlocks().get("founders-finance"))))
        .doesNotContain("clients");
    assertThat(discovered)
        .extracting(DiscoveredAirlock::clients)
        .containsExactly(List.of("knime"), List.of());
    assertThat(DiscoveredAirlock.toJson(discovered.subList(1, 2))).doesNotContain("clients");
    assertThat(DiscoveredAirlock.fromJson(DiscoveredAirlock.toJson(discovered)))
        .isEqualTo(discovered);
  }

  @Test
  void givenRelease_whenDiscovered_thenEachAirlockWithItsDescriptionAndToolsNoPromptOrModel() {
    // Arrange
    Configuration configuration = ConfigurationLoader.load(FIXTURES);

    // Act
    List<DiscoveredAirlock> discovered = DiscoveredAirlock.of(configuration);

    // Assert: founders-finance has no description and no code yet
    assertThat(discovered)
        .extracting(
            DiscoveredAirlock::id,
            DiscoveredAirlock::name,
            DiscoveredAirlock::description,
            DiscoveredAirlock::tools)
        .containsExactly(
            tuple(
                "hr-assistant",
                "Acme HR assistant",
                "Answers people, pay and HR document questions.",
                List.of("read_records", "aggregate_records")),
            tuple("founders-finance", "Founders finance", "", List.of()));
    assertThat(discovered.getFirst().lists("Eric@Example.com")).isTrue();
    assertThat(DiscoveredAirlock.toJson(discovered))
        .doesNotContain("granite")
        .doesNotContain("prompt")
        .doesNotContain("eric@example.com");
  }

  @Test
  void givenAirlockOfNoSpace_whenLoad_thenOnlyTheMissingSpaceIsReported() throws IOException {
    // Arrange
    Map<String, String> files = read(FIXTURES);
    files.put(
        "airlocks.yaml",
        files.get("airlocks.yaml").replaceFirst("space: hr-acme", "space: nowhere"));

    // Act / Assert
    assertThatThrownBy(() -> ConfigurationLoader.parse(files))
        .isInstanceOfSatisfying(
            ConfigurationException.class,
            e ->
                assertThat(e.problems())
                    .containsExactly(
                        "airlocks.yaml: Airlock hr-assistant: spec.space nowhere is no Space"));
  }

  @Test
  void givenDocumentsWithAJarConfigMap_whenLoad_thenTheConfigMapIsSkipped() throws IOException {
    // Arrange: a release directory may hold other API groups' objects beside the documents
    Configuration original = ConfigurationLoader.load(FIXTURES);
    Map<String, String> files = read(FIXTURES);
    files.put(
        "jars.yaml",
        "apiVersion: v1\nkind: ConfigMap\nmetadata: {name: hr-acme-hr-assistant-jar}\n"
            + "binaryData: {hr-assistant.jar: UEsDBA==}\n");

    // Act
    Configuration loaded = ConfigurationLoader.parse(files);

    // Assert
    assertThat(loaded.revision()).isEqualTo(original.revision());
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        "{apiVersion: aisolate.dev/v1, kind: Airlock, metadata: {name: onboarding},"
            + " spec: {space: hr-acme, access: {users: [eric@example.com],"
            + " callers: [\"*\"]}, dataSources: [{id: acme_hr, url:"
            + " \"jdbc:postgresql://db:5432/a\"}, {id: acme_hr, url:"
            + " \"jdbc:postgresql://db:5432/b\"}]}}"
            + "|onboarding: spec.dataSources[1].id acme_hr is listed twice",
        "{apiVersion: aisolate.dev/v1, kind: Airlock, metadata: {name: onboarding},"
            + " spec: {space: hr-acme}}"
            + "|onboarding: spec.access.callers must name at least one calling client",
        "{apiVersion: aisolate.dev/v1, kind: Airlock, spec: {space: hr-acme}}"
            + "|extra.yaml: Airlock: metadata.name '': use 2 to 41",
        "{apiVersion: aisolate.dev/v1, kind: Airlock, metadata: {name: onboarding},"
            + " spec: {space: hr-acme, access: {callers: [\"*\"]},"
            + " preLLM: {contextPreparator: {handlers: [HrDocumentsContextPreparator]}}}}"
            + "|onboarding: lists tools or handlers but has no jar",
        // An llm without a prompt is checked all the same
        "{apiVersion: aisolate.dev/v1, kind: Airlock, metadata: {name: onboarding},"
            + " spec: {space: hr-acme, access: {callers: [\"*\"]}, llm: gemma}}"
            + "|onboarding: llm gemma is no model of it, of Space hr-acme or of AIsolate",
        "{apiVersion: aisolate.dev/v1, kind: Global, metadata: {name: other}}"
            + "|extra.yaml: Global other: metadata.name must be aisolate",
        "{apiVersion: aisolate.dev/v1, kind: Global, metadata: {name: aisolate}}"
            + "|extra.yaml: Global aisolate: already defined in global.yaml"
      })
  void givenExtraDocument_whenLoad_thenProblemNamesDocumentAndField(
      String document, String expected) throws IOException {
    // Arrange: the fixtures plus one document the loader must refuse
    Map<String, String> files = read(FIXTURES);
    files.put("extra.yaml", document);

    // Act / Assert
    assertThatThrownBy(() -> ConfigurationLoader.parse(files))
        .isInstanceOf(ConfigurationException.class)
        .hasMessageContaining(expected);
  }

  @Test
  void givenDocuments_whenToYaml_thenLoadsBackEqual() throws IOException {
    // Arrange
    Configuration configuration = ConfigurationLoader.load(FIXTURES);

    // Act
    String yaml =
        ConfigurationLoader.toYaml(
            List.of(
                configuration.global(),
                configuration.spaces().get("hr-acme"),
                configuration.airlocks().get("hr-assistant"),
                configuration.airlocks().get("founders-finance")));
    Configuration reloaded = ConfigurationLoader.parse(Map.of("documents.yaml", yaml));

    // Assert
    assertThat(yaml)
        .contains("kind: Global")
        .contains("kind: Space")
        .contains("kind: Airlock")
        .contains("preLLM:")
        .contains("postLLM:")
        .contains("dataSources:")
        .contains("jar:");
    assertThat(reloaded.airlocks()).isEqualTo(configuration.airlocks());
    assertThat(reloaded.spaces().get("hr-acme")).isEqualTo(configuration.spaces().get("hr-acme"));
    assertThat(reloaded.revision()).isEqualTo(configuration.revision());
  }

  @Test
  void givenNoGlobal_whenLoad_thenAnEmptyOneAndOnlyTheGlobalLlmIsMissing() throws IOException {
    // Arrange: a space's documents load alone; its airlocks then see no global LLM
    Map<String, String> files = read(FIXTURES);
    files.remove("global.yaml");

    // Act
    Throwable thrown = catchThrowable(() -> ConfigurationLoader.parse(files));

    // Assert
    assertThat(thrown)
        .isInstanceOfSatisfying(
            ConfigurationException.class,
            e ->
                assertThat(e.problems())
                    .containsExactly(
                        "airlocks.yaml: Airlock hr-assistant: llm granite is no model of it, of"
                            + " Space hr-acme or of AIsolate"));
  }

  @Test
  void givenEmptyDirectory_whenLoad_thenRejected(@TempDir Path empty) {
    // Arrange / Act
    Throwable thrown = catchThrowable(() -> ConfigurationLoader.load(empty));

    // Assert
    assertThat(thrown)
        .isInstanceOf(ConfigurationException.class)
        .hasMessageContaining("no documents");
  }

  private static Map<String, String> read(Path dir) throws IOException {
    Map<String, String> files = new LinkedHashMap<>();
    try (Stream<Path> list = Files.list(dir)) {
      for (Path file : list.sorted().toList()) {
        files.put(file.getFileName().toString(), Files.readString(file));
      }
    }
    return files;
  }

  static Stream<String> acceptedDeclarations() {
    // Unset or blank: the pod declares none; else the one the mounted files hash to
    return Stream.of(null, "  ", ConfigurationLoader.load(FIXTURES).revision());
  }

  @ParameterizedTest
  @MethodSource("acceptedDeclarations")
  void givenNoOrTheSameDeclaredRevision_whenDeclared_thenTheComputedOneStands(String declared) {
    // Arrange
    Configuration loaded = ConfigurationLoader.load(FIXTURES);

    // Act
    Configuration same = loaded.declaredAs(declared);

    // Assert
    assertThat(same.revision()).isEqualTo(loaded.revision());
  }

  @Test
  void givenDeclaredRevisionDiffers_whenDeclared_thenRefusedNamingBoth() {
    // Arrange: the pod's env says one revision, the mounted files hash to another
    Configuration loaded = ConfigurationLoader.load(FIXTURES);

    // Act / Assert
    assertThatThrownBy(() -> loaded.declaredAs("rel-42"))
        .isInstanceOfSatisfying(
            ConfigurationException.class,
            e ->
                assertThat(e.problems())
                    .singleElement()
                    .satisfies(p -> assertThat(p).contains("rel-42").contains(loaded.revision())));
  }
}
