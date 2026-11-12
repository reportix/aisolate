package com.reportix.aisolate.backend.domain.deploy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.junit.jupiter.api.Named.named;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import com.reportix.aisolate.backend.domain.airlock.Airlock;
import com.reportix.aisolate.backend.domain.airlock.AirlockFormDTO;
import com.reportix.aisolate.backend.domain.airlock.DataSourceFormDTO;
import com.reportix.aisolate.backend.domain.airlock.InvalidFormException;
import com.reportix.aisolate.backend.domain.airlock.LlmFormDTO;
import com.reportix.aisolate.backend.domain.airlock.VersionFormDTO;
import com.reportix.aisolate.backend.domain.space.Space;
import com.reportix.aisolate.config.AirlockVersionDocument;
import com.reportix.aisolate.config.GlobalDocument;
import com.reportix.aisolate.config.Llm;
import com.reportix.aisolate.config.Metadata;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

class DocumentFormsTest {

  /** The install defines granite; the space and the airlock no model of their own. */
  private static final GlobalDocument GLOBAL =
      new GlobalDocument(
          new Metadata("aisolate"),
          new GlobalDocument.Spec(
              List.of(new Llm("granite", "ollama", "http://ollama:11434", "granite4.1:8b")),
              List.of()));

  /** The stored space: its developer and roles. */
  private static final Space SPACE =
      new Space(
          "hr-acme",
          "HR Acme",
          "",
          List.of("jasmine@example.com"),
          List.of("ceo"),
          List.of(),
          List.of(),
          "seed",
          null,
          null,
          null,
          null);

  /** The stored airlock, from the AIsolate UI: who may ask it. */
  private static final AirlockFormDTO STORED_FORM =
      new AirlockFormDTO(
          "hr-assistant",
          "HR assistant",
          "From the UI.",
          List.of("employee"),
          List.of("*"),
          List.of("acme-batch"),
          null,
          null);

  private static final Airlock AIRLOCK =
      new Airlock("hr-assistant", "hr-acme", 1, 0, "seed", null, null, null, STORED_FORM);

  /** What the project's jar carries: the tools and handlers the file lists. */
  private static final JarInspector.Contents CODE =
      new JarInspector.Contents(
          "sha256:ab",
          10,
          List.of(new JarInspector.Tool("read_records", "", "hr-acme-tools", "acme_hr")),
          List.of(
              new JarInspector.Handler(
                  "HrDocumentsContextPreparator", "context_prep", 1, null, List.of()),
              new JarInspector.Handler("SessionMemory", "memory", 1, null, List.of()),
              new JarInspector.Handler("SalaryVerifier", "verifier", 1, null, List.of())),
          List.of(),
          "java");

  private static final Map<String, String> VARIABLES =
      Map.of("HR_USERNAME", "reader", "HR_PASSWORD", "s3cret", "DOCS_PASSWORD", "d0cs");

  /** The airlock's document: its data sources with their logins from the variables. */
  private static final String AIRLOCK_DOCUMENT =
      """
      apiVersion: aisolate.dev/v1
      kind: Airlock
      metadata: {name: hr-assistant}
      spec:
        dataSources:
          - id: acme_hr
            url: jdbc:postgresql://acme-db:5432/acme_hr
            username: ${HR_USERNAME}
            password: ${HR_PASSWORD}
          - id: hr-documents
            url: jdbc:postgresql://acme-db:5432/acme_hr
            username: preparator
            password: ${DOCS_PASSWORD}
      ---
      """;

  /** The version's document: what runs, with a model of its own whose URL reads a variable. */
  private static final String VERSION_DOCUMENT =
      """
      apiVersion: aisolate.dev/v1
      kind: AirlockVersion
      metadata: {name: hr-assistant-v1}
      spec:
        airlock: hr-assistant
        version: v1
        description: First version.
        preLLM:
          contextPreparator:
            handlers: [HrDocumentsContextPreparator]
          conversationMemory:
            handlers: [SessionMemory]
        llm: qwen
        prompt: You are the HR assistant.
        tools: [read_records]
        llms:
          - {id: qwen, provider: ollama, url: "${LLM_URL:-http://ollama:11434}", model: "qwen3.6:27b-q4_K_M"}
        postLLM:
          answerVerifier:
            handlers: [SalaryVerifier]
      """;

  private static final String PROJECT = AIRLOCK_DOCUMENT + VERSION_DOCUMENT;

  private static final String SPACE_DOCUMENT =
      """
      apiVersion: aisolate.dev/v1
      kind: Space
      metadata: {name: hr-acme}
      spec:
        access: {roles: [ceo]}
      ---
      """;

  /** The deploy's steps without the database: scan, then the strict parse into the form. */
  private static VersionFormDTO parse(String yaml) {
    return DocumentForms.toForm(
        DocumentForms.parse(DocumentForms.scan(yaml, VARIABLES), CODE, GLOBAL, SPACE, AIRLOCK));
  }

  private static List<String> refusal(String yaml) {
    return catchThrowableOfType(InvalidFormException.class, () -> parse(yaml)).getErrors();
  }

  @Test
  void givenProjectFile_whenScannedAndParsed_thenTheVersionLandsAndTheAirlockGetsTheLogins() {
    // Arrange
    DocumentForms.Project project = DocumentForms.scan(PROJECT, VARIABLES);

    // Act
    AirlockVersionDocument document = DocumentForms.parse(project, CODE, GLOBAL, SPACE, AIRLOCK);
    VersionFormDTO form = DocumentForms.toForm(document);
    AirlockFormDTO airlock = DocumentForms.toAirlockForm(project, SPACE, AIRLOCK);

    // Assert: the version is named by its airlock and label; the Secret names are the server's
    assertThat(project.name()).isEqualTo("hr-assistant-v1");
    assertThat(project.airlock()).isEqualTo("hr-assistant");
    assertThat(project.version()).isEqualTo("v1");
    assertThat(form.version()).isEqualTo("v1");
    assertThat(form.description()).isEqualTo("First version.");
    assertThat(form.contextPreparators()).containsExactly("HrDocumentsContextPreparator");
    assertThat(form.memoryHandlers()).containsExactly("SessionMemory");
    assertThat(form.llm()).isEqualTo("qwen");
    assertThat(form.llms())
        .containsExactly(
            LlmFormDTO.of(new Llm("qwen", "ollama", "http://ollama:11434", "qwen3.6:27b-q4_K_M")));
    assertThat(form.prompt()).isEqualTo("You are the HR assistant.");
    assertThat(form.tools()).containsExactly("read_records");
    assertThat(form.verifiers()).containsExactly("SalaryVerifier");
    assertThat(document.spec().jar().digest()).isEqualTo("sha256:ab");
    // Who may ask is the stored airlock's, the file says nothing of it
    assertThat(airlock.roles()).containsExactly("employee");
    assertThat(airlock.callers()).containsExactly("*");
    assertThat(airlock.clients()).containsExactly("acme-batch");
    assertThat(airlock.dataSources())
        .containsExactly(
            new DataSourceFormDTO(
                "acme_hr", "jdbc:postgresql://acme-db:5432/acme_hr", "", "reader", "s3cret"),
            new DataSourceFormDTO(
                "hr-documents",
                "jdbc:postgresql://acme-db:5432/acme_hr",
                "",
                "preparator",
                "d0cs"));
    assertThat(project.airlockDocument().toString()).doesNotContain("s3cret", "d0cs");
  }

  static Stream<Arguments> givenNotOneVersionAndAtMostItsAirlock_whenScan_thenRefused() {
    return Stream.of(
        // A misspelt kind takes the same branch
        arguments(named("a Space", SPACE_DOCUMENT + PROJECT)),
        arguments(named("none", null)),
        arguments(named("an Airlock alone", AIRLOCK_DOCUMENT)),
        arguments(named("two versions", VERSION_DOCUMENT + "---\n" + VERSION_DOCUMENT)),
        arguments(named("two airlocks", AIRLOCK_DOCUMENT + PROJECT)));
  }

  @ParameterizedTest
  @MethodSource
  void givenNotOneVersionAndAtMostItsAirlock_whenScan_thenRefused(String yaml) {
    // Arrange

    // Act
    List<String> errors = refusal(yaml);

    // Assert
    assertThat(errors)
        .containsExactly(
            "aisolate.yaml: a project is one AirlockVersion and at most its Airlock; the Space is"
                + " defined in the AIsolate UI");
  }

  static Stream<Arguments> givenFieldNotTheProjectsToWrite_whenScan_thenRefused() {
    return Stream.of(
        arguments(
            named(
                "a space on the airlock",
                PROJECT.replace(
                    "spec:\n  dataSources:", "spec:\n  space: hr-acme\n  dataSources:")),
            "aisolate.yaml: Airlock hr-assistant: spec.space is AISOLATE_SPACE's to set; leave it"
                + " out"),
        arguments(
            named(
                "a data source Secret name",
                PROJECT.replace(
                    "    - id: hr-documents\n",
                    "    - id: hr-documents\n      secretRef: other\n")),
            "aisolate.yaml: Airlock hr-assistant: spec.dataSources[1].secretRef is AIsolate's to"
                + " set; give username and password"),
        arguments(
            named(
                "a model Secret name on the version",
                PROJECT.replace(
                    "  llms:\n    - {id: qwen,", "  llms:\n    - {id: qwen, secretRef: other,")),
            "aisolate.yaml: AirlockVersion hr-assistant-v1: spec.llms[0].secretRef is AIsolate's"
                + " to set; give the model's API key in the AIsolate UI"),
        arguments(
            named(
                "a jar",
                PROJECT.replace(
                    "  version: v1\n", "  version: v1\n  jar: {digest: \"sha256:cd\"}\n")),
            "aisolate.yaml: AirlockVersion hr-assistant-v1: spec.jar is the deploy's to set"),
        arguments(
            named(
                "an airlock of another name",
                PROJECT.replace("metadata: {name: hr-assistant}", "metadata: {name: other}")),
            "aisolate.yaml: Airlock other: metadata.name must be the AirlockVersion's spec.airlock,"
                + " hr-assistant"),
        // The loader would skip it as another group's, and the deploy find no version
        arguments(
            named(
                "another API group",
                VERSION_DOCUMENT.replace(
                    "apiVersion: aisolate.dev/v1", "apiVersion: aisolate.io/v1")),
            "aisolate.yaml: AirlockVersion hr-assistant-v1: apiVersion aisolate.io/v1 is not"
                + " aisolate.dev/v1"),
        arguments(
            named("no airlock", VERSION_DOCUMENT.replace("  airlock: hr-assistant\n", "")),
            "aisolate.yaml: AirlockVersion hr-assistant-v1: spec.airlock is required"),
        arguments(
            named(
                "a key written twice",
                PROJECT.replace("  llm: qwen\n", "  llm: qwen\n  llm: granite\n")),
            "aisolate.yaml: Duplicate Object property \"llm\""));
  }

  @ParameterizedTest
  @MethodSource
  void givenFieldNotTheProjectsToWrite_whenScan_thenRefused(String yaml, String expected) {
    // Arrange

    // Act
    List<String> errors = refusal(yaml);

    // Assert
    assertThat(errors).containsExactly(expected);
  }

  @Test
  void givenAirlockDocumentWritingSomeFields_whenToAirlockForm_thenTheyReplaceAndTheRestStays() {
    // Arrange: the stored airlock lists employee and acme-batch; the file writes a name and roles
    String yaml =
        PROJECT.replace(
            "spec:\n  dataSources:",
            "spec:\n  displayName: HR\n  access: {roles: [founder]}\n  dataSources:");

    // Act
    AirlockFormDTO form =
        DocumentForms.toAirlockForm(DocumentForms.scan(yaml, VARIABLES), SPACE, AIRLOCK);

    // Assert: the callers and clients the file leaves out stay
    assertThat(form.name()).isEqualTo("HR");
    assertThat(form.description()).isEqualTo("From the UI.");
    assertThat(form.roles()).containsExactly("founder");
    assertThat(form.callers()).containsExactly("*");
    assertThat(form.clients()).containsExactly("acme-batch");
  }

  @ParameterizedTest
  @CsvSource({"'{clients: [acme-etl]}', acme-etl", "'{clients: []}', ''"})
  void givenAirlockDocumentWritingClients_whenToAirlockForm_thenTheyReplaceTheStoredOnes(
      String access, String expected) {
    // Arrange: an empty list is written too, so it replaces the stored acme-batch
    String yaml =
        PROJECT.replace("spec:\n  dataSources:", "spec:\n  access: " + access + "\n  dataSources:");

    // Act
    AirlockFormDTO form =
        DocumentForms.toAirlockForm(DocumentForms.scan(yaml, VARIABLES), SPACE, AIRLOCK);

    // Assert
    assertThat(form.clients()).isEqualTo(expected.isEmpty() ? List.of() : List.of(expected));
    assertThat(form.roles()).containsExactly("employee");
  }

  @Test
  void givenNoStoredAirlock_whenToAirlockForm_thenItsIdNamesItAndTheLoginLands() {
    // Arrange: the callers stay empty for the create to default to *
    DocumentForms.Project project = DocumentForms.scan(PROJECT, VARIABLES);

    // Act
    AirlockFormDTO form = DocumentForms.toAirlockForm(project, SPACE, null);

    // Assert
    assertThat(form.id()).isEqualTo("hr-assistant");
    assertThat(form.name()).isEqualTo("hr-assistant");
    assertThat(form.roles()).isEmpty();
    assertThat(form.callers()).isEmpty();
    assertThat(form.dataSources().getFirst().password()).isEqualTo("s3cret");
  }

  static Stream<Arguments> givenDocumentWithAnUnknownField_whenParsed_thenTheLoaderRefusesIt() {
    return Stream.of(
        arguments(
            "Airlock",
            PROJECT.replace("spec:\n  dataSources:", "spec:\n  colour: blue\n  dataSources:"),
            "colour"),
        arguments(
            "AirlockVersion",
            PROJECT.replace("  version: v1\n", "  version: v1\n  colour: blue\n"),
            "colour"),
        // A version holds no credential, so no data source: the loader knows no such field
        arguments(
            "AirlockVersion",
            PROJECT.replace("  version: v1\n", "  version: v1\n  dataSources: []\n"),
            "dataSources"));
  }

  @ParameterizedTest
  @MethodSource
  void givenDocumentWithAnUnknownField_whenParsed_thenTheLoaderRefusesIt(
      String kind, String yaml, String field) {
    // Arrange
    DocumentForms.Project project = DocumentForms.scan(yaml, VARIABLES);

    // Act
    Throwable thrown =
        catchThrowable(() -> {
          if (kind.equals("Airlock")) {
            DocumentForms.toAirlockForm(project, SPACE, AIRLOCK);
          } else {
            DocumentForms.parse(project, CODE, GLOBAL, SPACE, AIRLOCK);
          }
        });

    // Assert
    assertThat(((InvalidFormException) thrown).getErrors())
        .singleElement()
        .asString()
        .startsWith("aisolate.yaml: " + kind + " hr-assistant")
        .contains(field);
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        // granite is the install's; the file's own llms are the version's
        "llm: qwen|llm: granite|''",
        "llm: qwen|llm: gemma|aisolate.yaml: AirlockVersion hr-assistant-v1: llm: no model named"
            + " gemma in this airlock's space or globally",
        "'  llm: qwen\n'|''|aisolate.yaml: AirlockVersion hr-assistant-v1: llm: name the model"
            + " that answers, for example granite",
        "model: \"qwen3.6:27b-q4_K_M\"|model: ' '|aisolate.yaml: AirlockVersion hr-assistant-v1:"
            + " spec.llms[0].model is required"
      })
  void givenMainLlm_whenParsed_thenItResolvesOrIsRefusedOnItsField(
      String from, String to, String expected) {
    // Arrange
    String yaml = PROJECT.replace(from, to);

    // Act
    Throwable thrown = catchThrowable(() -> parse(yaml));

    // Assert
    if (expected.isEmpty()) {
      assertThat(thrown).isNull();
    } else {
      assertThat(((InvalidFormException) thrown).getErrors()).containsExactly(expected);
    }
  }

  @Test
  void givenUnsetVariable_whenScan_thenRefusedByNameAndNoValueEchoed() {
    // Arrange
    Map<String, String> variables = Map.of("HR_USERNAME", "reader", "DOCS_PASSWORD", "d0cs");

    // Act
    InvalidFormException thrown =
        catchThrowableOfType(
            InvalidFormException.class, () -> DocumentForms.scan(PROJECT, variables));

    // Assert
    assertThat(thrown.getErrors()).containsExactly("variable HR_PASSWORD is not set");
  }

  @Test
  void givenVariablesThatAreNoObjectOfStrings_whenRead_thenRefusedWithoutEcho() {
    // Arrange: one catch for every shape; this one would echo a value
    String variables = "not json hunter2";

    // Act
    InvalidFormException thrown =
        catchThrowableOfType(
            InvalidFormException.class, () -> DocumentForms.readVariables(variables));

    // Assert
    assertThat(thrown.getErrors()).containsExactly("variables: a JSON object of names to values");
  }
}
