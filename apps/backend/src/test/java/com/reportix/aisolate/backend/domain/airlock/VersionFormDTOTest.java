package com.reportix.aisolate.backend.domain.airlock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Named.named;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import com.reportix.aisolate.backend.domain.deploy.JarInspector;
import com.reportix.aisolate.backend.domain.space.Space;
import com.reportix.aisolate.config.GlobalDocument;
import com.reportix.aisolate.config.Llm;
import com.reportix.aisolate.config.Metadata;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class VersionFormDTOTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  /** Every section filled in, one model of its own. */
  private static final String VALID =
      """
      {"version":"v1","description":"First version.",
       "context_preparators":["HrDocumentsContextPreparator"],
       "memory_handlers":["SessionMemory"],
       "llm":"granite","prompt":"You are the HR assistant.","tools":["read_records"],
       "verifiers":["SalaryVerifier"],
       "llms":[{"id":"granite","provider":"ollama","url":"http://ollama:11434",
                "model":"granite4.1:8b"}]}
      """;

  private static VersionFormDTO form(String json) {
    return JSON.readValue(json, VersionFormDTO.class);
  }

  /** {@link #VALID} with one field set to a value, or removed when the value is null. */
  private static VersionFormDTO with(String field, String value) {
    ObjectNode node = (ObjectNode) JSON.readTree(VALID);
    if (value == null) {
      node.remove(field);
    } else if (value.startsWith("[")) {
      node.set(field, JSON.readTree(value));
    } else {
      node.put(field, value);
    }
    return JSON.treeToValue(node, VersionFormDTO.class);
  }

  private static AirlockVersionRepository.Row row(String definition) {
    return new AirlockVersionRepository.Row(
        "hr-assistant",
        "hr-acme",
        "v1",
        "First version.",
        1,
        "jasmine",
        null,
        null,
        null,
        JSON.readTree(definition),
        null);
  }

  @Test
  void givenValidForm_whenStored_thenNoErrorsAndTheDefinitionHoldsTheFormWithoutItsColumns() {
    // Arrange
    VersionFormDTO form = form(VALID);

    // Act
    List<String> errors = form.validate();
    String definition = form.toDefinitionJson(JSON);

    // Assert: snake_case throughout; name and description have their own columns
    assertThat(errors).isEmpty();
    JsonNode stored = JSON.readTree(definition);
    assertThat(stored.has("version") || stored.has("description")).isFalse();
    assertThat(stored.path("llm").asString()).isEqualTo("granite");
    assertThat(stored.path("llms").path(0).path("url").asString()).isEqualTo("http://ollama:11434");
    assertThat(stored.path("llms").path(0).path("secret_ref").asString()).isEmpty();
    assertThat(stored.path("context_preparators").valueStream().map(JsonNode::asString))
        .containsExactly("HrDocumentsContextPreparator");
    assertThat(stored.path("memory_handlers").valueStream().map(JsonNode::asString))
        .containsExactly("SessionMemory");
    assertThat(stored.path("verifiers").valueStream().map(JsonNode::asString))
        .containsExactly("SalaryVerifier");
    assertThat(stored.has("data_sources")).isFalse();
    assertThat(VersionFormDTO.from(row(definition), JSON)).isEqualTo(form);
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = " ")
  void givenBlankPrompt_whenWithDefaultPrompt_thenTheDefaultAndNothingElseChanges(String prompt) {
    // Arrange
    VersionFormDTO form = with("prompt", prompt);

    // Act
    VersionFormDTO saved = form.withDefaultPrompt();

    // Assert
    assertThat(saved).isEqualTo(with("prompt", VersionFormDTO.DEFAULT_PROMPT));
  }

  @Test
  void givenPrompt_whenWithDefaultPrompt_thenKept() {
    // Arrange
    VersionFormDTO form = form(VALID);

    // Act
    VersionFormDTO saved = form.withDefaultPrompt();

    // Assert
    assertThat(saved).isSameAs(form);
    assertThat(saved.prompt()).isEqualTo("You are the HR assistant.");
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      nullValues = "NULL",
      value = {
        "version| |version: required",
        "version|V1!|version: 1 to 31 lowercase letters, digits or dashes, not starting or ending"
            + " with a dash",
        "version|this-name-is-longer-than-31-chars|version: 1 to 31 lowercase letters, digits or"
            + " dashes, not starting or ending with a dash",
        "version|new|version: new is reserved",
        "memory_handlers|[\"\"]|memory_handlers: must not contain a blank name",
        "verifiers|[\" \"]|verifiers: must not contain a blank name",
        "llm| |llm: name the model that answers, for example granite",
        "llms|[{\"id\":\"granite\",\"provider\":\"bedrock\",\"url\":\"http://ollama:11434\","
            + "\"model\":\"m\"}]|llms[0].provider: bedrock is not one of [ollama, openai]",
        "llms|[{\"id\":\"granite\",\"provider\":\"ollama\",\"url\":\"http://ollama:11434\","
            + "\"model\":\" \"}]|llms[0].model: required",
        "llms|[{\"id\":\"gpt\",\"provider\":\"openai\",\"url\":\"https://api.openai.example/v1\","
            + "\"model\":\"m\",\"api_key\":\"sk\"}]|llms[0].api_key: a version's model takes no API"
            + " key; define the model on its airlock",
        "llms|[{\"id\":\"granite\",\"provider\":\"ollama\",\"url\":\"http://o:11434\","
            + "\"model\":\"m\"},{\"id\":\"granite\",\"provider\":\"ollama\","
            + "\"url\":\"http://o:11434\",\"model\":\"m\"}]|llms[1].id: granite is listed twice",
        "context_preparators|[\"HandbookPreparator\",\"HandbookPreparator\"]|context_preparators:"
            + " lists HandbookPreparator twice",
        "tools|[\"read_records\",\"read_records\"]|tools: lists read_records twice"
      })
  void givenOneBrokenField_whenValidate_thenExactlyItsError(
      String field, String value, String expected) {
    // Arrange
    VersionFormDTO form = with(field, value);

    // Act
    List<String> errors = form.validate();

    // Assert
    assertThat(errors).containsExactly(expected);
  }

  private static final Llm GRANITE = new Llm("granite", "ollama", "http://ollama:11434", "g");
  private static final LlmFormDTO GRANITE_ROW = LlmFormDTO.of(GRANITE);

  private static Space space(List<LlmFormDTO> llms) {
    return new Space(
        "hr-acme",
        "HR Acme",
        "",
        List.of("jasmine@example.com"),
        List.of("ceo"),
        llms,
        List.of(),
        "jasmine",
        null,
        null,
        null,
        null);
  }

  private static Airlock airlock(List<LlmFormDTO> llms) {
    return new Airlock(
        "hr-assistant",
        "hr-acme",
        1,
        0,
        "jasmine",
        null,
        null,
        null,
        new AirlockFormDTO(
            "hr-assistant",
            "HR assistant",
            "",
            List.of("employee"),
            List.of("*"),
            null,
            llms,
            List.of()));
  }

  private static GlobalDocument global(List<Llm> llms) {
    return new GlobalDocument(new Metadata("aisolate"), new GlobalDocument.Spec(llms, List.of()));
  }

  static Stream<Arguments> givenVersion_whenValidateVersion_thenExactlyItsError() {
    // One plugin reading payroll: no level defines it, and the version lists no tool.
    JarInspector.Contents payroll =
        new JarInspector.Contents(
            "sha256:ab",
            10,
            List.of(new JarInspector.Tool("export_payslips", "", "payroll-tools", "payroll")),
            List.of(),
            List.of(),
            "java");
    String granite = ",\"llm\":\"granite\"";
    String gemma = ",\"llm\":\"gemma\"";
    String own =
        granite
            + ",\"llms\":[{\"id\":\"granite\",\"provider\":\"ollama\","
            + "\"url\":\"http://ollama:11434\",\"model\":\"g\"}]";
    String nowhere = "llm: no model named granite in this airlock's space or globally";
    // Its main LLM on each level, or on none; ids that code names are never checked
    return Stream.of(
        arguments(
            named("granite globally", global(List.of(GRANITE))),
            List.of(),
            List.of(),
            granite,
            null,
            null),
        arguments(
            named("granite on the space", global(List.of())),
            List.of(GRANITE_ROW),
            List.of(),
            granite,
            null,
            null),
        arguments(
            named("granite on the airlock", global(List.of())),
            List.of(),
            List.of(GRANITE_ROW),
            granite,
            null,
            null),
        arguments(
            named("granite on the version", global(List.of())),
            List.of(),
            List.of(),
            own,
            null,
            null),
        arguments(
            named("granite nowhere", global(List.of())),
            List.of(),
            List.of(),
            granite,
            null,
            nowhere),
        arguments(
            named("another id on every level", global(List.of(GRANITE))),
            List.of(GRANITE_ROW),
            List.of(GRANITE_ROW),
            gemma,
            null,
            "llm: no model named gemma in this airlock's space or globally"),
        arguments(
            named("a data source the code reads and nothing defines", global(List.of(GRANITE))),
            List.of(),
            List.of(),
            granite,
            payroll,
            "tools: plugin payroll-tools reads data source payroll, which this airlock does not"
                + " define"),
        // A version saved in the AIsolate UI has no code: it lists nothing of it
        arguments(
            named("a tool without code", global(List.of(GRANITE))),
            List.of(),
            List.of(),
            granite + ",\"tools\":[\"read_records\"]",
            null,
            "tools: read_records is not a tool of this version's code"),
        arguments(
            named("a context preparator without code", global(List.of(GRANITE))),
            List.of(),
            List.of(),
            granite + ",\"context_preparators\":[\"HandbookPreparator\"]",
            null,
            "context_preparators: HandbookPreparator is not a context preparator of this"
                + " version's code"),
        arguments(
            named("a conversation memory without code", global(List.of(GRANITE))),
            List.of(),
            List.of(),
            granite + ",\"memory_handlers\":[\"SessionMemory\"]",
            null,
            "memory_handlers: SessionMemory is not a conversation memory of this version's code"),
        arguments(
            named("a verifier without code", global(List.of(GRANITE))),
            List.of(),
            List.of(),
            granite + ",\"verifiers\":[\"SalaryVerifier\"]",
            null,
            "verifiers: SalaryVerifier is not a verifier of this version's code"));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource
  void givenVersion_whenValidateVersion_thenExactlyItsError(
      GlobalDocument global,
      List<LlmFormDTO> spaceLlms,
      List<LlmFormDTO> airlockLlms,
      String fields,
      JarInspector.Contents code,
      String expected) {
    // Arrange: no level defines a data source
    VersionFormDTO form = form("{\"version\":\"v1\",\"prompt\":\"prompt\"" + fields + "}");

    // Act
    List<String> errors =
        AirlockService.validateVersion(global, space(spaceLlms), airlock(airlockLlms), form, code);

    // Assert
    assertThat(errors).isEqualTo(expected == null ? List.of() : List.of(expected));
  }

  @Test
  void givenVersionNamedPast63Characters_whenValidateVersion_thenRefusedOnItsField() {
    // Arrange: 22 characters of space, 26 of airlock: the longest the create allows, so a short
    // version fits and a longer one does not
    Airlock airlock =
        new Airlock(
            "acme-hr-assistant-for-empl",
            "acme-financial-holding",
            1,
            0,
            "jasmine",
            null,
            null,
            null,
            new AirlockFormDTO(
                "acme-hr-assistant-for-empl", "X", "", List.of(), List.of("*"), null, null, null));
    Space longSpace =
        new Space(
            "acme-financial-holding",
            "Acme",
            "",
            List.of(),
            List.of(),
            List.of(GRANITE_ROW),
            List.of(),
            "andrew",
            null,
            null,
            null,
            null);

    // Act
    List<String> fits =
        AirlockService.validateVersion(
            global(List.of()), longSpace, airlock, versionNamed("v1"), null);
    List<String> tooLong =
        AirlockService.validateVersion(
            global(List.of()), longSpace, airlock, versionNamed("v10"), null);

    // Assert
    assertThat(fits).isEmpty();
    assertThat(tooLong)
        .containsExactly(
            "version: it would run as"
                + " acme-financial-holding-acme-hr-assistant-for-empl-v10-mcp-server, 64"
                + " characters; Kubernetes allows 63 (shorten the space, the airlock or the version"
                + " name)");
  }

  /** A version of that name answering with granite, as the AIsolate UI's create page sends it. */
  private static VersionFormDTO versionNamed(String version) {
    return form("{\"version\":\"" + version + "\",\"llm\":\"granite\",\"prompt\":\"p\"}");
  }

  @Test
  void givenLlmUrlWithACredential_whenValidate_thenTheFieldIsNamedAndTheUrlNeverEchoed() {
    // Arrange
    VersionFormDTO form =
        with(
            "llms",
            "[{\"id\":\"granite\",\"provider\":\"ollama\","
                + "\"url\":\"http://user:hunter2@ollama:11434\",\"model\":\"g\"}]");

    // Act
    List<String> errors = form.validate();

    // Assert
    assertThat(errors).singleElement().asString().startsWith("llms[0].url: carries");
    assertThat(errors.getFirst()).doesNotContain("hunter2");
  }
}
