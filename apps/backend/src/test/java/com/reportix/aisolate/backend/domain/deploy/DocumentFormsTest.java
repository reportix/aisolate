package com.reportix.aisolate.backend.domain.deploy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.junit.jupiter.api.Named.named;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import com.reportix.aisolate.backend.domain.airlock.Airlock;
import com.reportix.aisolate.backend.domain.airlock.AirlockFormDTO;
import com.reportix.aisolate.backend.domain.airlock.DataSourceFormDTO;
import com.reportix.aisolate.backend.domain.airlock.InvalidFormException;
import com.reportix.aisolate.backend.domain.airlock.LlmFormDTO;
import com.reportix.aisolate.backend.domain.space.Space;
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

  /** The stored airlock, from the AIsolate UI: who may ask it, and nothing that runs yet. */
  private static final AirlockFormDTO STORED_FORM =
      new AirlockFormDTO(
          "hr-assistant",
          "HR assistant",
          "From the UI.",
          List.of("employee"),
          List.of("*"),
          List.of("acme-batch"),
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null);

  private static final Airlock AIRLOCK =
      new Airlock(
          "hr-assistant",
          "hr-acme",
          1,
          "http://localhost:8080/v1/spaces/hr-acme/airlocks/hr-assistant/chat",
          "seed",
          null,
          null,
          null,
          STORED_FORM,
          null,
          null);

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

  private static final String PROJECT =
      """
      apiVersion: aisolate.dev/v1
      kind: Airlock
      metadata: {name: hr-assistant}
      spec:
        description: The HR assistant.
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

  private static final String SPACE_DOCUMENT =
      """
      apiVersion: aisolate.dev/v1
      kind: Space
      metadata: {name: hr-acme}
      spec:
        access: {roles: [ceo]}
      ---
      """;

  /** The deploy's two steps without the database: scan, then the strict parse into the form. */
  private static AirlockFormDTO parse(String yaml) {
    return DocumentForms.toForm(DocumentForms.scan(yaml, VARIABLES), CODE, GLOBAL, SPACE, AIRLOCK);
  }

  private static List<String> refusal(String yaml) {
    return catchThrowableOfType(InvalidFormException.class, () -> parse(yaml)).getErrors();
  }

  @Test
  void givenProjectFile_whenToForm_thenWhatItWritesLandsWithTheCredentialsAndTheRestStays() {
    // Arrange
    DocumentForms.Project project = DocumentForms.scan(PROJECT, VARIABLES);

    // Act
    AirlockFormDTO form = DocumentForms.toForm(project, CODE, GLOBAL, SPACE, AIRLOCK);

    // Assert: who may ask is the stored airlock's, the file says nothing of it; the Secret names
    // are the server's
    assertThat(project.airlock()).isEqualTo("hr-assistant");
    assertThat(form.id()).isEqualTo("hr-assistant");
    assertThat(form.name()).isEqualTo("HR assistant");
    assertThat(form.description()).isEqualTo("The HR assistant.");
    assertThat(form.roles()).containsExactly("employee");
    assertThat(form.callers()).containsExactly("*");
    assertThat(form.clients()).containsExactly("acme-batch");
    assertThat(form.contextPreparators()).containsExactly("HrDocumentsContextPreparator");
    assertThat(form.memoryHandlers()).containsExactly("SessionMemory");
    assertThat(form.llm()).isEqualTo("qwen");
    assertThat(form.llms())
        .containsExactly(
            LlmFormDTO.of(new Llm("qwen", "ollama", "http://ollama:11434", "qwen3.6:27b-q4_K_M")));
    assertThat(form.prompt()).isEqualTo("You are the HR assistant.");
    assertThat(form.tools()).containsExactly("read_records");
    assertThat(form.verifiers()).containsExactly("SalaryVerifier");
    assertThat(form.dataSources())
        .containsExactly(
            new DataSourceFormDTO(
                "acme_hr", "jdbc:postgresql://acme-db:5432/acme_hr", "", "reader", "s3cret"),
            new DataSourceFormDTO(
                "hr-documents",
                "jdbc:postgresql://acme-db:5432/acme_hr",
                "",
                "preparator",
                "d0cs"));
    assertThat(project.document().toString()).doesNotContain("s3cret", "d0cs");
  }

  static Stream<Arguments> givenNotExactlyOneAirlock_whenScan_thenRefusedAsOneAirlock() {
    return Stream.of(
        // A misspelt kind takes the same branch
        arguments(named("a Space", SPACE_DOCUMENT + PROJECT)),
        arguments(named("none", null)),
        arguments(named("two airlocks", PROJECT + "---\n" + PROJECT)));
  }

  @ParameterizedTest
  @MethodSource
  void givenNotExactlyOneAirlock_whenScan_thenRefusedAsOneAirlock(String yaml) {
    // Arrange

    // Act
    List<String> errors = refusal(yaml);

    // Assert
    assertThat(errors)
        .containsExactly(
            "aisolate.yaml: a project is one Airlock; the Space is defined in the AIsolate UI");
  }

  static Stream<Arguments> givenFieldNotTheProjectsToWrite_whenScan_thenRefused() {
    return Stream.of(
        arguments(
            named("a space", PROJECT.replace("spec:\n", "spec:\n  space: hr-acme\n")),
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
                "a model Secret name",
                PROJECT.replace(
                    "  llms:\n    - {id: qwen,", "  llms:\n    - {id: qwen, secretRef: other,")),
            "aisolate.yaml: Airlock hr-assistant: spec.llms[0].secretRef is AIsolate's to set;"
                + " give the model's API key in the AIsolate UI"),
        arguments(
            named("a jar", PROJECT.replace("spec:\n", "spec:\n  jar: {digest: \"sha256:cd\"}\n")),
            "aisolate.yaml: Airlock hr-assistant: spec.jar is the deploy's to set"),
        // The loader would skip it as another group's, and the deploy find no airlock
        arguments(
            named(
                "another API group",
                PROJECT.replace("apiVersion: aisolate.dev/v1", "apiVersion: aisolate.io/v1")),
            "aisolate.yaml: Airlock hr-assistant: apiVersion aisolate.io/v1 is not"
                + " aisolate.dev/v1"),
        arguments(
            named("no name", PROJECT.replace("metadata: {name: hr-assistant}\n", "")),
            "aisolate.yaml: Airlock: metadata.name is required"),
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
  void givenFileWritingTheNameAndModels_whenToForm_thenTheyReplaceTheStoredOnesAndTheRestStays() {
    // Arrange: the stored airlock lists eric and acme-batch; the file leaves its access out
    String yaml =
        PROJECT
            .replace("spec:\n", "spec:\n  displayName: HR\n")
            .replaceFirst(
                "(?s)  llms:\n.*?(  postLLM:)",
                "  llms: [{id: nomic, provider: ollama, url: \"http://ollama:11434\","
                    + " model: nomic-embed-text}]\n$1");

    // Act
    AirlockFormDTO form = parse(yaml.replace("  llm: qwen\n", "  llm: granite\n"));

    // Assert
    assertThat(form.name()).isEqualTo("HR");
    assertThat(form.description()).isEqualTo("The HR assistant.");
    assertThat(form.roles()).containsExactly("employee");
    assertThat(form.callers()).containsExactly("*");
    assertThat(form.clients()).containsExactly("acme-batch");
    assertThat(form.llms())
        .containsExactly(
            LlmFormDTO.of(new Llm("nomic", "ollama", "http://ollama:11434", "nomic-embed-text")));
  }

  @ParameterizedTest
  @CsvSource({"'{clients: [acme-etl]}', acme-etl", "'{clients: []}', ''"})
  void givenFileWritingClients_whenToForm_thenTheyReplaceTheStoredOnes(
      String access, String expected) {
    // Arrange: an empty list is written too, so it replaces the stored acme-batch
    String yaml = PROJECT.replace("spec:\n", "spec:\n  access: " + access + "\n");

    // Act
    AirlockFormDTO form = parse(yaml);

    // Assert: the roles and callers the file leaves out stay
    assertThat(form.clients()).isEqualTo(expected.isEmpty() ? List.of() : List.of(expected));
    assertThat(form.roles()).containsExactly("employee");
    assertThat(form.callers()).containsExactly("*");
  }

  @Test
  void givenFileLeavingWhatRunsOut_whenToForm_thenTheStoredRunStays() {
    // Arrange: an airlock deployed before; a file naming only the model
    AirlockFormDTO deployedForm =
        new AirlockFormDTO(
            "hr-assistant",
            "HR assistant",
            "",
            List.of("employee"),
            List.of("*"),
            null,
            List.of("HrDocumentsContextPreparator"),
            null,
            "granite",
            "Stored prompt.",
            List.of("read_records"),
            List.of("SalaryVerifier"),
            null,
            null);
    Airlock deployed =
        new Airlock(
            "hr-assistant",
            "hr-acme",
            2,
            AIRLOCK.endpoint(),
            "seed",
            null,
            null,
            null,
            deployedForm,
            null,
            CODE);
    String yaml =
        """
        apiVersion: aisolate.dev/v1
        kind: Airlock
        metadata: {name: hr-assistant}
        spec:
          llm: granite
        """;

    // Act
    AirlockFormDTO form =
        DocumentForms.toForm(DocumentForms.scan(yaml, Map.of()), CODE, GLOBAL, SPACE, deployed);

    // Assert
    assertThat(form.llm()).isEqualTo("granite");
    assertThat(form.prompt()).isEqualTo("Stored prompt.");
    assertThat(form.contextPreparators()).containsExactly("HrDocumentsContextPreparator");
    assertThat(form.tools()).containsExactly("read_records");
    assertThat(form.verifiers()).containsExactly("SalaryVerifier");
  }

  @Test
  void givenFileWithRolesAndCredentials_whenToFormOfANewAirlock_thenItsIdNamesItAndTheLoginLands() {
    // Arrange: the roles list is written, so it replaces; the login comes from the variables
    String yaml = PROJECT.replace("spec:\n", "spec:\n  access: {roles: [founder]}\n");
    DocumentForms.Project project = DocumentForms.scan(yaml, VARIABLES);

    // Act
    AirlockFormDTO form = DocumentForms.toForm(project, CODE, GLOBAL, SPACE, null);

    // Assert: the callers stay empty for the create to default to *
    assertThat(form.name()).isEqualTo("hr-assistant");
    assertThat(form.description()).isEqualTo("The HR assistant.");
    assertThat(form.roles()).containsExactly("founder");
    assertThat(form.callers()).isEmpty();
    assertThat(form.dataSources().getFirst())
        .isEqualTo(
            new DataSourceFormDTO(
                "acme_hr", "jdbc:postgresql://acme-db:5432/acme_hr", "", "reader", "s3cret"));
    assertThat(project.document().toString()).doesNotContain("s3cret");
  }

  @Test
  void givenFileWithAnUnknownField_whenToForm_thenTheLoaderRefusesIt() {
    // Arrange
    String yaml = PROJECT.replace("spec:\n", "spec:\n  colour: blue\n");

    // Act
    List<String> errors = refusal(yaml);

    // Assert
    assertThat(errors)
        .singleElement()
        .asString()
        .startsWith("aisolate.yaml: Airlock hr-assistant: ")
        .contains("colour");
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        // granite is the install's; the file's own llms are the airlock's
        "llm: qwen|llm: granite|''",
        "llm: qwen|llm: gemma|aisolate.yaml: Airlock hr-assistant: llm: no model named gemma in"
            + " this airlock's space or globally",
        // No model: the airlock runs nothing until a deploy names one
        "'  llm: qwen\n'|''|''",
        "model: \"qwen3.6:27b-q4_K_M\"|model: ' '|aisolate.yaml: Airlock hr-assistant:"
            + " spec.llms[0].model is required"
      })
  void givenMainLlm_whenToForm_thenItResolvesOrIsRefusedOnItsField(
      String from, String to, String expected) {
    // Arrange
    String yaml = PROJECT.replace(from, to);

    // Act
    Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(() -> parse(yaml));

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
