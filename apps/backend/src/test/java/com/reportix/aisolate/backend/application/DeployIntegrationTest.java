package com.reportix.aisolate.backend.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.reportix.aisolate.backend.BackendApplication;
import com.reportix.aisolate.backend.domain.airlock.AirlockFormDTO;
import com.reportix.aisolate.backend.domain.deploy.PythonArtifacts;
import com.reportix.aisolate.backend.support.BackendTestConfig;
import com.reportix.aisolate.identity.testing.Http;
import com.reportix.aisolate.identity.testing.Keycloak;
import com.reportix.aisolate.identity.testing.Tokens;
import com.reportix.aisolate.plugins.testing.HandlerJars;
import com.reportix.aisolate.plugins.testing.PluginJars;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ContextConfiguration;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A project's jar and its {@code aisolate.yaml}, one Airlock, deployed in one call without a
 * cluster: the airlock is created from the file, or saved in place with its jar when it exists,
 * each field the file writes replacing the stored one and the rest kept; the file and the jar must
 * agree; a refusal leaves nothing written. The space comes from the AIsolate UI first. The tests
 * share their spaces and build on each other, so their order is fixed.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = BackendApplication.class)
@ContextConfiguration(initializers = BackendTestConfig.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DeployIntegrationTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private static final String SPACE = "/v1/spaces/deploy-lab";

  private static final String DEPLOY = SPACE + "/deploy";

  private static final String AIRLOCK = SPACE + "/airlocks/hr-assistant";

  /** The space the tests of a file writing the airlock's own fields deploy into. */
  private static final String BLOCK = "/v1/spaces/deploy-block";

  /** The HR tools, the handbook preparator and a rule set. */
  private static final byte[] PROJECT_JAR = projectJar();

  /**
   * The one airlock, answering with an LLM of its own whose URL reads a variable with a fallback;
   * the data source the tools read is the space's. No displayName and no access: the stored ones
   * stay.
   */
  private static final String PROJECT =
      """
      apiVersion: aisolate.dev/v1
      kind: Airlock
      metadata: {name: hr-assistant}
      spec:
        description: The HR assistant.
        preLLM:
          contextPreparator:
            handlers: [HandbookPreparator]
        llm: qwen
        prompt: You are the HR assistant.
        tools: [read_records, aggregate_records]
        llms:
          - {id: qwen, provider: ollama, url: "${LLM_URL:-http://ollama:11434}", model: "qwen3.6:27b-q4_K_M"}
        postLLM:
          answerVerifier:
            handlers: []
      """;

  private final String jasmine =
      Tokens.forUser(Keycloak.issuerUri(), "aisolate-ui", "jasmine", "password");
  private final String rita =
      Tokens.forUser(Keycloak.issuerUri(), "aisolate-ui", "andrew", "password");

  @LocalServerPort int port;

  /**
   * The AIsolate UI's part, the admin's: the spaces with their roles, jasmine the developer of
   * those she deploys into, and the airlocks made before any deploy.
   */
  @BeforeAll
  void createSpacesAndAirlocks() {
    create(
        "/v1/spaces",
        """
        {"id":"deploy-lab","name":"Deploy lab","llms":[{"id":"granite","provider":"ollama","url":"http://ollama:11434","model":"granite4.1:8b"}],
         "developers":["jasmine@example.com"],"roles":["employee"],
         "data_sources":[{"id":"acme_hr","url":"jdbc:postgresql://acme-db:5432/hr",
                          "secret_ref":"deploy-lab-ds-acme-hr-abc123"}]}
        """);
    create(
        "/v1/spaces",
        """
        {"id":"deploy-other","name":"Deploy other","roles":["employee"]}
        """);
    create(SPACE + "/airlocks", "{\"id\":\"hr-assistant\",\"name\":\"HR assistant\"}");
    create(
        "/v1/spaces/deploy-other/airlocks",
        "{\"id\":\"foreign-assistant\",\"name\":\"Foreign assistant\"}");
    create(
        "/v1/spaces",
        """
        {"id":"deploy-block","name":"Deploy block","llms":[{"id":"granite","provider":"ollama","url":"http://ollama:11434","model":"granite4.1:8b"}],
         "developers":["jasmine@example.com"],
         "data_sources":[{"id":"acme_hr","url":"jdbc:postgresql://acme-db:5432/hr",
                          "secret_ref":"deploy-block-ds-acme-hr-abc123"}]}
        """);
    create(
        BLOCK + "/airlocks",
        """
        {"id":"kept-assistant","name":"Kept assistant","description":"From the UI.",
         "roles":["ceo"]}
        """);
  }

  @Test
  @Order(2)
  void givenNoToken_whenDeploy_then401() {
    // Arrange
    Map<String, Object> parts = new LinkedHashMap<>();
    parts.put("jar", new Http.FilePart("project.jar", PROJECT_JAR));
    HttpRequest request =
        Http.multipart(HttpRequest.newBuilder(uri(DEPLOY)), "POST", parts).build();

    // Act
    HttpResponse<String> response = Http.send(request);

    // Assert
    assertThat(response.statusCode()).isEqualTo(401);
  }

  @Test
  @Order(4)
  void givenAirlockOfAnotherSpace_whenDeploy_then409TakenThere() {
    // Arrange: ids are global; the file cannot take it either
    String documents = projectOf("foreign-assistant");

    // Act
    HttpResponse<String> response = deploy(jasmine, DEPLOY, PROJECT_JAR, documents);

    // Assert
    assertThat(response.statusCode()).isEqualTo(409);
    assertThat(errors(response))
        .containsExactly(
            "id: An airlock with the id foreign-assistant already exists in space deploy-other.");
  }

  @Test
  @Order(20)
  void givenAirlockNowhere_whenDeploy_thenTheAirlockIsCreatedFromTheFile() {
    // Arrange
    String documents =
        projectOf("new-assistant")
            .replace(
                "  description: The HR assistant.\n",
                "  displayName: New assistant\n  description: Made by the deploy.\n"
                    + "  access: {roles: [founder]}\n");

    // Act
    HttpResponse<String> response = deploy(jasmine, BLOCK + "/deploy", PROJECT_JAR, documents);

    // Assert
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    JsonNode outcome = JSON.readTree(response.body()).path("airlock");
    assertThat(outcome.path("created").asBoolean()).isTrue();
    assertThat(outcome.path("revision").asInt()).isEqualTo(1);
    JsonNode airlock = JSON.readTree(get(jasmine, BLOCK + "/airlocks/new-assistant").body());
    assertThat(airlock.path("name").asString()).isEqualTo("New assistant");
    assertThat(airlock.path("description").asString()).isEqualTo("Made by the deploy.");
    assertThat(strings(airlock.path("roles"))).containsExactly("founder");
    assertThat(strings(airlock.path("callers"))).containsExactly("*");
    assertThat(airlock.path("created_by").asString()).isNotBlank();
    assertThat(airlock.path("form").path("llm").asString()).isEqualTo("qwen");
    assertThat(airlock.path("jar").path("digest").asString()).startsWith("sha256:");
  }

  @Test
  @Order(21)
  void givenAirlockFromTheUiAndAFileWritingItsDescription_whenDeploy_thenSavedAndItsRolesKept() {
    // Arrange: the file leaves the roles and the name out
    String documents = projectOf("kept-assistant").replace("The HR assistant.", "From the file.");

    // Act
    HttpResponse<String> response = deploy(jasmine, BLOCK + "/deploy", PROJECT_JAR, documents);

    // Assert
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    JsonNode outcome = JSON.readTree(response.body()).path("airlock");
    assertThat(outcome.path("created").asBoolean()).isFalse();
    assertThat(outcome.path("revision").asInt()).isEqualTo(2);
    JsonNode airlock = JSON.readTree(get(jasmine, BLOCK + "/airlocks/kept-assistant").body());
    assertThat(airlock.path("name").asString()).isEqualTo("Kept assistant");
    assertThat(airlock.path("description").asString()).isEqualTo("From the file.");
    assertThat(strings(airlock.path("roles"))).containsExactly("ceo");
  }

  @Test
  @Order(22)
  void givenFileLeavingThePromptOut_whenDeployAgain_thenTheStoredPromptStaysAndTheSaveCounts() {
    // Arrange: a deploy is a save, so it counts; what the file does not write, it keeps
    String documents =
        projectOf("kept-assistant").replace("  prompt: You are the HR assistant.\n", "");

    // Act
    HttpResponse<String> response = deploy(jasmine, BLOCK + "/deploy", PROJECT_JAR, documents);

    // Assert
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    assertThat(JSON.readTree(response.body()).path("airlock").path("revision").asInt())
        .isEqualTo(3);
    assertThat(
            JSON.readTree(get(jasmine, BLOCK + "/airlocks/kept-assistant").body())
                .path("form")
                .path("prompt")
                .asString())
        .isEqualTo("You are the HR assistant.");
  }

  @Test
  @Order(23)
  void givenRefusedFile_whenDeploy_then400AndNoAirlockCreatedOrSaved() {
    // Arrange: a handler the code lacks
    String refused = "handlers: [HandbookPreparator, Faq]";
    String created =
        projectOf("refused-assistant").replace("handlers: [HandbookPreparator]", refused);
    String updated =
        projectOf("kept-assistant")
            .replace("The HR assistant.", "Never saved.")
            .replace("handlers: [HandbookPreparator]", refused);

    // Act
    HttpResponse<String> create = deploy(jasmine, BLOCK + "/deploy", PROJECT_JAR, created);
    HttpResponse<String> update = deploy(jasmine, BLOCK + "/deploy", PROJECT_JAR, updated);

    // Assert
    assertThat(create.statusCode()).isEqualTo(400);
    assertThat(update.statusCode()).isEqualTo(400);
    assertThat(get(jasmine, BLOCK + "/airlocks/refused-assistant").statusCode()).isEqualTo(404);
    assertThat(
            JSON.readTree(get(jasmine, BLOCK + "/airlocks/kept-assistant").body())
                .path("description")
                .asString())
        .isEqualTo("The HR assistant.");
  }

  @Test
  @Order(6)
  void givenJarAndProjectFile_whenDeploy_then200WithAirlockJarAndNoWarning() {
    // Arrange / Act
    HttpResponse<String> response = deploy(jasmine, DEPLOY, PROJECT_JAR, PROJECT);

    // Assert: the UI's create was revision 1; the deploy is the next save
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    JsonNode body = JSON.readTree(response.body());
    JsonNode airlock = body.path("airlock");
    assertThat(airlock.path("id").asString()).isEqualTo("hr-assistant");
    assertThat(airlock.path("name").asString()).isEqualTo("HR assistant");
    assertThat(airlock.path("revision").asInt()).isEqualTo(2);
    assertThat(airlock.path("created").asBoolean()).isFalse();
    assertThat(airlock.path("endpoint").asString())
        .endsWith("/v1/spaces/deploy-lab/airlocks/hr-assistant/chat");
    assertThat(body.path("jar").path("digest").asString()).matches("sha256:[0-9a-f]{64}");
    assertThat(strings(body.path("jar").path("tools")))
        .containsExactly("read_records", "aggregate_records");
    assertThat(strings(body.path("jar").path("handlers"))).containsExactly("HandbookPreparator");
    assertThat(body.path("warnings").isEmpty()).isTrue();
    assertThat(body.path("cluster").isNull()).isTrue();
    JsonNode stored = JSON.readTree(get(jasmine, AIRLOCK).body());
    assertThat(stored.path("description").asString()).isEqualTo("The HR assistant.");
    // The variable's fallback, read into the parsed value
    assertThat(stored.path("form").path("llms").path(0).path("url").asString())
        .isEqualTo("http://ollama:11434");
    assertThat(stored.path("main_llm").path("from").asString()).isEqualTo("airlock");
    assertThat(strings(stored.path("form").path("context_preparators")))
        .containsExactly("HandbookPreparator");
    JsonNode jar = stored.path("jar");
    assertThat(jar.path("digest").asString()).isEqualTo(body.path("jar").path("digest").asString());
    assertThat(jar.path("size").asLong()).isEqualTo(PROJECT_JAR.length);
    assertThat(jar.path("language").asString()).isEqualTo("java");
    assertThat(jar.path("tools").path(0).path("plugin").asString()).isEqualTo("hr-tools");
    assertThat(jar.path("tools").path(0).path("data_source").asString()).isEqualTo("acme_hr");
    assertThat(jar.path("handlers").path(0).path("stage").asString()).isEqualTo("context_prep");
    assertThat(jar.path("rules").path(0).path("entity").asString()).isEqualTo("hr.compensation");
    assertThat(jar.path("rules").path(0).path("rules_count").asInt()).isEqualTo(1);
  }

  @Test
  @Order(7)
  void givenSecondDeployOfTheSameAirlock_whenDeploy_thenSavedAtRevision3() {
    // Arrange / Act
    HttpResponse<String> response = deploy(jasmine, DEPLOY, PROJECT_JAR, PROJECT);

    // Assert
    assertThat(response.statusCode()).isEqualTo(200);
    JsonNode airlock = JSON.readTree(response.body()).path("airlock");
    assertThat(airlock.path("created").asBoolean()).isFalse();
    assertThat(airlock.path("revision").asInt()).isEqualTo(3);
    assertThat(list(SPACE + "/airlocks")).hasSize(1);
  }

  @Test
  @Order(8)
  void givenHandlerNotInTheCode_whenDeploy_then400NamingIt() {
    // Arrange: the airlock's own check, run once, with the code the deploy brings
    String documents =
        PROJECT.replace("handlers: [HandbookPreparator]", "handlers: [HandbookPreparator, Faq]");

    // Act
    HttpResponse<String> response = deploy(jasmine, DEPLOY, PROJECT_JAR, documents);

    // Assert
    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(errors(response))
        .containsExactly(
            "Airlock hr-assistant: context_preparators: Faq is not a context preparator of this"
                + " airlock's code");
    assertThat(revision()).isEqualTo(3);
  }

  @Test
  @Order(9)
  void givenCodeToolNotListed_whenDeploy_thenWarning() {
    // Arrange
    String documents =
        PROJECT.replace("tools: [read_records, aggregate_records]", "tools: [read_records]");

    // Act
    HttpResponse<String> response = deploy(jasmine, DEPLOY, PROJECT_JAR, documents);

    // Assert
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(strings(JSON.readTree(response.body()).path("warnings")))
        .containsExactly(
            "tool aggregate_records of this project is not in tools, so the model cannot call"
                + " it");
    assertThat(revision()).isEqualTo(4);
  }

  @Test
  @Order(10)
  void givenFormReadBeforeADeploy_whenSavedWithWhatItRead_then409AndTheDeployStands() {
    // Arrange: the AIsolate UI opens the airlock; a deploy then saves it
    JsonNode read = JSON.readTree(get(jasmine, AIRLOCK).body());
    assertThat(deploy(jasmine, DEPLOY, PROJECT_JAR, PROJECT).statusCode()).isEqualTo(200);
    HttpRequest.Builder save =
        HttpRequest.newBuilder(uri(AIRLOCK))
            .header("Content-Type", "application/json")
            .header("If-Match", "\"" + read.path("updated_at").asString() + "\"")
            .PUT(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(read.path("form"))));

    // Act
    HttpResponse<String> response = send(save, jasmine);

    // Assert: the tab's save would have undone the deploy's description
    assertThat(response.statusCode()).isEqualTo(409);
    assertThat(errors(response))
        .containsExactly(
            "updated_at: Saved by someone else since you opened it; reload it and save again.");
    assertThat(revision()).isEqualTo(read.path("revision").asInt() + 1);
  }

  @Test
  @Order(11)
  void givenDocumentsSentAsFilePart_whenDeploy_thenTheAirlockDeploys() {
    // Arrange: another airlock of the space
    byte[] yaml = projectOf("pilot-assistant").getBytes(StandardCharsets.UTF_8);
    Map<String, Object> parts = new LinkedHashMap<>();
    parts.put("jar", new Http.FilePart("project.jar", PROJECT_JAR));
    parts.put("documents", new Http.FilePart("aisolate.yaml", yaml, "application/yaml"));

    // Act
    HttpResponse<String> response =
        send(Http.multipart(HttpRequest.newBuilder(uri(DEPLOY)), "POST", parts), jasmine);

    // Assert
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    assertThat(list(SPACE + "/airlocks"))
        .extracting(a -> a.path("id").asString())
        .containsExactly("hr-assistant", "pilot-assistant");
  }

  @Test
  @Order(12)
  void givenCredentialsAndNoCluster_whenDeploy_then400AndNoPasswordInTheAnswer() {
    // Arrange: the variables fill the data source's login; no cluster keeps Secrets here
    String documents =
        PROJECT
            + """
              dataSources:
                - id: payroll
                  url: jdbc:postgresql://acme-db:5432/payroll
                  username: ${HR_USERNAME}
                  password: ${HR_PASSWORD}
            """;
    String variables = "{\"HR_USERNAME\":\"reader\",\"HR_PASSWORD\":\"hunter2\"}";

    // Act
    HttpResponse<String> response = deploy(jasmine, DEPLOY, PROJECT_JAR, documents, variables);

    // Assert
    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(errors(response))
        .containsExactly(
            "Airlock hr-assistant: data_sources[0].password: no cluster is configured to keep it"
                + " in a Secret");
    assertThat(response.body()).doesNotContain("hunter2").doesNotContain("reader");
    assertThat(revision()).isEqualTo(5);
  }

  @Test
  @Order(13)
  void givenCodeReadingTheSpacesDataSource_whenSpaceSavedWithoutIt_then400NamingTheAirlocks() {
    // Arrange: both airlocks' HR tools read acme_hr, which only the space defines
    String withoutDataSource =
        """
        {"id":"deploy-lab","name":"Deploy lab","llms":[{"id":"granite","provider":"ollama","url":"http://ollama:11434","model":"granite4.1:8b"}],
         "developers":["jasmine@example.com"],"roles":["employee"]}
        """;

    // Act
    HttpResponse<String> response =
        send(
            HttpRequest.newBuilder(uri(SPACE))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(withoutDataSource)),
            rita);

    // Assert: their tool servers would refuse to start; the space keeps it
    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(errors(response))
        .singleElement()
        .asString()
        .startsWith(
            "data_sources: deploy-lab/hr-assistant and deploy-lab/pilot-assistant would each have"
                + " no data source acme_hr, which its plugin ")
        .endsWith(" reads; keep it, or define it on the airlock first");
    assertThat(JSON.readTree(get(jasmine, SPACE).body()).path("data_sources")).hasSize(1);
  }

  @Test
  @Order(14)
  void
      givenAPythonArtifactAndNoPrompt_whenDeployedAsPython_thenTheManifestIsTheCodeAndThePromptTheDefault() {
    // Arrange: the same project, its code a Python zip, as a new airlock without a prompt
    String project = projectOf("py-assistant").replace("  prompt: You are the HR assistant.\n", "");
    byte[] zip = PythonArtifacts.zip();

    // Act
    HttpResponse<String> response = deployAs("python", zip, project);

    // Assert: read from the manifest, never run; the verifier it lists nowhere is a warning
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    JsonNode body = JSON.readTree(response.body());
    assertThat(strings(body.path("jar").path("tools")))
        .containsExactly("aggregate_records", "read_records");
    assertThat(strings(body.path("warnings")))
        .containsExactly(
            "verifier SalaryVerifier of this project is listed nowhere and runs nowhere");
    JsonNode stored = JSON.readTree(get(jasmine, SPACE + "/airlocks/py-assistant").body());
    assertThat(stored.path("jar").path("language").asString()).isEqualTo("python");
    assertThat(stored.path("jar").path("size").asLong()).isEqualTo(zip.length);
    assertThat(stored.path("form").path("prompt").asString())
        .isEqualTo(AirlockFormDTO.DEFAULT_PROMPT);
  }

  @ParameterizedTest
  @CsvSource({
    "python, artifact: no aisolate-manifest.json",
    "ruby, 'language: java or python, not ruby'"
  })
  @Order(15)
  void givenAJarSentAsPythonOrAnUnknownLanguage_whenDeploy_then400NamingIt(
      String language, String expected) {
    // Arrange / Act
    HttpResponse<String> response = deployAs(language, PROJECT_JAR, PROJECT);

    // Assert
    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(errors(response)).anySatisfy(error -> assertThat(error).startsWith(expected));
  }

  /** The project file for another airlock: the same code and settings. */
  private static String projectOf(String airlock) {
    return PROJECT.replace("{name: hr-assistant}", "{name: " + airlock + "}");
  }

  private static byte[] projectJar() {
    Map<String, byte[]> entries =
        new LinkedHashMap<>(PluginJars.compile(PluginJars.HR_TOOLS, HandlerJars.HANDBOOK));
    entries.put("aisolate/rules/hr.compensation.json", HandlerJars.rulesFile("hr.compensation"));
    return PluginJars.buildJar(entries);
  }

  private void create(String path, String body) {
    HttpResponse<String> created =
        send(
            HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)),
            rita);
    assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
  }

  private HttpResponse<String> deploy(String bearer, String path, byte[] jar, String documents) {
    return deploy(bearer, path, jar, documents, null);
  }

  /** jasmine's deploy into deploy-lab with the {@code language} part. */
  private HttpResponse<String> deployAs(String language, byte[] code, String documents) {
    Map<String, Object> parts = new LinkedHashMap<>();
    parts.put("jar", new Http.FilePart("project.zip", code));
    parts.put("language", language);
    parts.put("documents", documents);
    return send(Http.multipart(HttpRequest.newBuilder(uri(DEPLOY)), "POST", parts), jasmine);
  }

  private HttpResponse<String> deploy(
      String bearer, String path, byte[] jar, String documents, String variables) {
    Map<String, Object> parts = new LinkedHashMap<>();
    parts.put("jar", new Http.FilePart("project.jar", jar));
    parts.put("documents", documents);
    if (variables != null) {
      parts.put("variables", variables);
    }
    return send(Http.multipart(HttpRequest.newBuilder(uri(path)), "POST", parts), bearer);
  }

  private int revision() {
    return JSON.readTree(get(jasmine, AIRLOCK).body()).path("revision").asInt();
  }

  private List<JsonNode> list(String path) {
    HttpResponse<String> response = get(jasmine, path);
    assertThat(response.statusCode()).isEqualTo(200);
    return JSON.readTree(response.body()).valueStream().toList();
  }

  private static List<String> strings(JsonNode array) {
    return array.valueStream().map(JsonNode::asString).toList();
  }

  private static Stream<String> errors(HttpResponse<String> response) {
    return JSON.readTree(response.body()).path("errors").valueStream().map(JsonNode::asString);
  }

  private HttpResponse<String> get(String bearer, String path) {
    return send(HttpRequest.newBuilder(uri(path)).GET(), bearer);
  }

  private URI uri(String path) {
    return URI.create("http://localhost:" + port + path);
  }

  private static HttpResponse<String> send(HttpRequest.Builder request, String bearer) {
    return Http.send(Http.bearer(request, bearer).build());
  }
}
