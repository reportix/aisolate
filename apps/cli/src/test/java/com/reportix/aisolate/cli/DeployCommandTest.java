package com.reportix.aisolate.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import picocli.CommandLine;

/**
 * {@code deploy} against a stub AIsolate and issuer: the project's {@code gradlew} is a script that
 * copies a fixture jar, so no real Gradle build runs.
 */
class DeployCommandTest {

  private static final String DEPLOY_PATH = "/v1/spaces/hr-lab/deploy";
  private static final String JAR_BYTES = "fixture jar bytes";
  private static final String ELSEWHERE = "http://elsewhere:8080";

  /** The project file as written: one airlock, the way the template lays it out. */
  private static final String DOCUMENTS =
      TestProject.AIRLOCK
          + """
            llm: granite
            prompt: You are the Acme HR assistant.
            tools: [read_records, aggregate_records]
          """;

  @TempDir Path tmp;

  private final FakeConsole console = new FakeConsole();
  private final StringWriter err = new StringWriter();
  private IdentityStub stub;
  private Path project;
  private Path home;
  private Path sessionFile;
  private Map<String, String> shell = Map.of();
  private int deployStatus = 200;
  private String deployBody;
  private String authorization;
  private String contentType;
  private String body;

  @BeforeEach
  void start() throws IOException {
    stub = new IdentityStub();
    stub.server.createContext(
        DEPLOY_PATH,
        exchange -> {
          authorization = exchange.getRequestHeaders().getFirst("Authorization");
          contentType = exchange.getRequestHeaders().getFirst("Content-Type");
          body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.ISO_8859_1);
          IdentityStub.respond(exchange, deployStatus, deployBody);
        });
    deployBody = deployResult(stub.url);
    // The endpoint with a trailing slash, as a hand-edited .env may carry it.
    project = TestProject.create(tmp.resolve("hr-lab"), "hr-lab", stub.url + "/");
    Files.writeString(project.resolve("aisolate.yaml"), DOCUMENTS);
    home = tmp.resolve("home");
    sessionFile = home.resolve(Session.FILE);
    Path fixture = Files.writeString(tmp.resolve("fixture.jar"), JAR_BYTES);
    TestProject.gradlew(
        project,
        "mkdir -p build/libs && cp '"
            + fixture
            + "' build/libs/hr-lab.jar && touch build/libs/hr-lab-plain.jar"
            + " build/libs/hr-lab-sources.jar build/libs/hr-lab-javadoc.jar");
  }

  @AfterEach
  void stop() {
    stub.close();
  }

  @Test
  void givenValidSession_whenDeploy_thenBuildsPostsTheFileAsWrittenAndReportsTheAirlock()
      throws Exception {
    // Arrange
    storeSession(stub.url, "stored", Instant.now().getEpochSecond() + 3600);

    // Act
    int exit = deploy();

    // Assert
    assertThat(exit).isZero();
    assertThat(stub.forms).isEmpty();
    assertThat(authorization).isEqualTo("Bearer stored");
    assertThat(contentType).startsWith("multipart/form-data; boundary=aisolate-");
    assertThat(body)
        .contains(
            "Content-Disposition: form-data; name=\"jar\"; filename=\"hr-lab.jar\"\r\n"
                + "Content-Type: application/java-archive\r\n\r\n"
                + JAR_BYTES
                + "\r\n")
        .contains(
            "Content-Disposition: form-data; name=\"language\"\r\n"
                + "Content-Type: text/plain; charset=UTF-8\r\n\r\njava\r\n")
        .contains(
            "Content-Disposition: form-data; name=\"documents\"\r\n"
                + "Content-Type: text/plain; charset=UTF-8\r\n\r\n"
                + DOCUMENTS
                + "\r\n")
        .contains(
            "Content-Disposition: form-data; name=\"variables\"\r\n"
                + "Content-Type: application/json\r\n\r\n{}\r\n");
    assertThat(console.lines)
        .containsExactly(
            "⏺ Signed in as jasmine",
            "⏺ Building hr-lab",
            "✔ Built build/libs/hr-lab.jar",
            "⏺ Deploying to space hr-lab",
            "✔ airlock hr-lab/hr-assistant (created, revision 1)",
            "   POST " + stub.url + "/v1/spaces/hr-lab/airlocks/hr-assistant/chat",
            "   code sha256:0f1e; tools: read_records, aggregate_records; handlers:"
                + " HrDocumentsContextPreparator, SessionMemory, SalaryVerifier");
    assertThat(console.errors).isEmpty();
  }

  @Test
  void givenWarningsInTheResult_whenDeploy_thenEachIsOneLineBeforeTheAirlockLine()
      throws Exception {
    // Arrange: the backend updated the airlock in place, and says what the project lists nowhere
    deployBody =
        deployResult(stub.url)
            .replace("\"revision\":1", "\"revision\":3")
            .replace("\"created\":true", "\"created\":false")
            .replace(
                "\"warnings\":[]",
                "\"warnings\":[\"tool export_payslips of this project is not in tools, so the"
                    + " model cannot call it\",\"verifier PayslipVerifier of this project is listed"
                    + " nowhere and runs nowhere\"]");
    storeSession(stub.url, "stored", Instant.now().getEpochSecond() + 3600);

    // Act
    int exit = deploy();

    // Assert
    assertThat(exit).isZero();
    assertThat(console.lines)
        .containsSubsequence(
            "⏺ Deploying to space hr-lab",
            "! tool export_payslips of this project is not in tools, so the model cannot call"
                + " it",
            "! verifier PayslipVerifier of this project is listed nowhere and runs nowhere",
            "✔ airlock hr-lab/hr-assistant (updated, revision 3)");
    assertThat(console.errors).isEmpty();
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        "{\"applied\":true}|'   cluster: applied, the status of the Airlock shows its pods'",
        "{\"applied\":false,\"problem\":\"forbidden: configmaps is forbidden\"}|'   cluster: not"
            + " applied: forbidden: configmaps is forbidden'",
      })
  void givenClusterOutcome_whenReported_thenOneLineSaysApplied(String cluster, String expected)
      throws Exception {
    // Arrange: the backend in a cluster says whether the pods follow
    deployBody = deployResult(stub.url).replace("\"cluster\":null", "\"cluster\":" + cluster);
    storeSession(stub.url, "stored", Instant.now().getEpochSecond() + 3600);

    // Act
    int exit = deploy();

    // Assert
    assertThat(exit).isZero();
    assertThat(console.lines).contains(expected);
  }

  @Test
  void givenTokenExpiringDuringTheBuild_whenDeploy_thenRefreshedBeforeTheUpload() throws Exception {
    // Arrange: valid at the gate (32 s left, more than 30), not after a 2 s build
    storeSession(stub.url, "short", Instant.now().getEpochSecond() + 32);
    TestProject.gradlew(project, "sleep 2 && mkdir -p build/libs && touch build/libs/hr-lab.jar");

    // Act
    int exit = deploy();

    // Assert
    assertThat(exit).isZero();
    assertThat(stub.forms).containsExactly(IdentityStub.REFRESH_GRANT);
    assertThat(authorization).isEqualTo("Bearer at2");
  }

  @Test
  void givenNoSessionInATerminal_whenDeploy_thenSignsInSavesTheSessionAndDeploys()
      throws Exception {
    // Arrange: the browser sign-in, played by the test
    BrowserLogin.SignIn signIn =
        auth ->
            new Session(
                stub.url,
                stub.issuer,
                "jasmine",
                "signed-in",
                "rt",
                Instant.now().getEpochSecond() + 3600);

    // Act
    int exit = deploy(signIn);

    // Assert
    assertThat(exit).isZero();
    assertThat(console.errors).containsExactly("You are not logged in to " + stub.url);
    assertThat(console.lines).startsWith("✔ Signed in as jasmine", "⏺ Building hr-lab");
    assertThat(authorization).isEqualTo("Bearer signed-in");
    assertThat(Session.load(sessionFile, stub.url))
        .hasValueSatisfying(saved -> assertThat(saved.accessToken()).isEqualTo("signed-in"));
  }

  @Test
  void givenNoUsableSession_whenDeploy_thenNotLoggedInAndTheBuildNeverRuns() throws Exception {
    // Arrange: no session stored; why a stored one is unusable is AuthTest's

    // Act
    int exit = deploy();

    // Assert
    assertThat(exit).isEqualTo(1);
    assertThat(console.errors).containsExactly(Auth.NOT_LOGGED_IN);
    assertThat(console.lines).isEmpty();
    assertThat(project.resolve("build")).doesNotExist();
    assertThat(body).isNull();
    assertThat(err.toString()).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "<html>ok</html>",
        "{\"airlock\":{\"id\":\"hr-assistant\",\"name\":\"HR assistant\"}}"
      })
  void givenServer200WithoutAResult_whenDeploy_thenOneLineAndExit1(String response)
      throws Exception {
    // Arrange
    storeSession(stub.url, "stored", Instant.now().getEpochSecond() + 3600);
    deployBody = response;

    // Act
    int exit = deploy();

    // Assert
    assertThat(exit).isEqualTo(1);
    assertThat(console.errors)
        .containsExactly("AIsolate answered HTTP 200 without a deploy result");
    assertThat(console.lines)
        .containsExactly(
            "⏺ Signed in as jasmine",
            "⏺ Building hr-lab",
            "✔ Built build/libs/hr-lab.jar",
            "⏺ Deploying to space hr-lab");
    assertThat(err.toString()).isEmpty();
  }

  @Test
  void givenServer400_whenDeploy_thenEveryErrorLineAndExit1() throws Exception {
    // Arrange: a 409 with errors takes the same case
    storeSession(stub.url, "stored", Instant.now().getEpochSecond() + 3600);
    deployStatus = 400;
    deployBody =
        "{\"status\":400,\"detail\":\"invalid form\",\"errors\":[\"aisolate.yaml: one\",\"jar:"
            + " two\"]}";

    // Act
    int exit = deploy();

    // Assert
    assertThat(exit).isEqualTo(1);
    assertThat(console.errors).containsExactly("aisolate.yaml: one", "jar: two");
    assertThat(err.toString()).isEmpty();
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      textBlock =
          """
          403 |                                                          | You are not a developer of space hr-lab in AIsolate
          403 | {"detail":"You are not a developer of space hr-lab: ask its admin to add you as Developer."} | You are not a developer of space hr-lab: ask its admin to add you as Developer.
          404 | {"detail":"unknown space"}                               | space hr-lab not found
          404 | {"detail":"Airlock hr-assistant is not in space hr-lab"} | Airlock hr-assistant is not in space hr-lab
          409 | {"detail":"hr-assistant is elsewhere"}                   | hr-assistant is elsewhere
          500 |                                                          | AIsolate answered HTTP 500
          """)
  void givenServerRefusal_whenDeploy_thenOneLineAndExit1(int status, String response, String line)
      throws Exception {
    // Arrange
    storeSession(stub.url, "stored", Instant.now().getEpochSecond() + 3600);
    deployStatus = status;
    deployBody = response == null ? "" : response;

    // Act
    int exit = deploy();

    // Assert
    assertThat(exit).isEqualTo(1);
    assertThat(console.errors).containsExactly(line);
    assertThat(err.toString()).isEmpty();
    assertThat(Session.load(sessionFile, stub.url)).isPresent();
  }

  @Test
  void givenServer401_whenDeploy_thenThisEndpointsSessionForgottenAndNotLoggedIn()
      throws Exception {
    // Arrange
    storeSession(stub.url, "revoked", Instant.now().getEpochSecond() + 3600);
    storeSession(ELSEWHERE, "other", Instant.now().getEpochSecond() + 3600);
    deployStatus = 401;
    deployBody = "";

    // Act
    int exit = deploy();

    // Assert
    assertThat(exit).isEqualTo(1);
    assertThat(console.errors).containsExactly(Auth.NOT_LOGGED_IN);
    assertThat(err.toString()).isEmpty();
    assertThat(Session.load(sessionFile, stub.url)).isEmpty();
    assertThat(Session.load(sessionFile, ELSEWHERE)).isPresent();
  }

  @Test
  void givenDocumentsReadingVariables_whenDeploy_thenSentAsWrittenWithTheValuesTheyRead()
      throws Exception {
    // Arrange: the shell wins over .env unless empty; a fallback, a comment and an unread entry
    // send nothing
    storeSession(stub.url, "stored", Instant.now().getEpochSecond() + 3600);
    String documents =
        TestProject.AIRLOCK
            + """
              llm: "${LLM:-granite}"
              # password: ${RETIRED_PASSWORD}
              dataSources: [{id: hr, username: "${HR_USERNAME}", password: "${HR_PASSWORD}"}]
            """;
    Files.writeString(project.resolve("aisolate.yaml"), documents);
    Files.writeString(
        project.resolve(".env"),
        "AISOLATE_ENDPOINT="
            + stub.url
            + "\nAISOLATE_SPACE=hr-lab\nHR_USERNAME=reader\nHR_PASSWORD=from-env-file"
            + "\nUNREAD=unread\n");
    shell = Map.of("HR_PASSWORD", "from-shell", "HR_USERNAME", "");

    // Act
    int exit = deploy();

    // Assert
    assertThat(exit).isZero();
    assertThat(body)
        .contains("Content-Type: text/plain; charset=UTF-8\r\n\r\n" + documents + "\r\n")
        .contains(
            "Content-Disposition: form-data; name=\"variables\"\r\n"
                + "Content-Type: application/json\r\n\r\n"
                + "{\"HR_PASSWORD\":\"from-shell\",\"HR_USERNAME\":\"reader\"}\r\n")
        .doesNotContain("from-env-file")
        .doesNotContain("unread")
        .doesNotContain("AISOLATE_ENDPOINT")
        .doesNotContain("AISOLATE_SPACE");
    assertThat(console.lines).noneMatch(line -> line.contains("from-shell"));
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      textBlock =
          """
          ''                                         | HR_PASSWORD, HR_USERNAME, which are
          HR_USERNAME=reader                         | HR_PASSWORD, which is
          """)
  void givenVariableUnsetOrEmptyWithoutFallback_whenDeploy_thenRefusedByNameBeforeTheSignIn(
      String envFile, String refused) throws Exception {
    // Arrange
    storeSession(stub.url, "stored", Instant.now().getEpochSecond() + 3600);
    Files.writeString(
        project.resolve("aisolate.yaml"),
        TestProject.AIRLOCK
            + "  llm: \"${LLM:-granite}\"\n"
            + "  dataSources: [{id: hr, username: \"${HR_USERNAME}\", password:"
            + " \"${HR_PASSWORD}\"}]\n");
    Files.writeString(
        project.resolve(".env"), "AISOLATE_SPACE=hr-lab\n" + envFile.translateEscapes());

    // Act
    int exit = deploy();

    // Assert
    assertThat(exit).isEqualTo(1);
    assertThat(console.errors)
        .containsExactly("aisolate.yaml reads " + refused + " not set in .env or the shell");
    assertThat(console.lines).isEmpty();
    assertThat(project.resolve("build")).doesNotExist();
    assertThat(body).isNull();
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      textBlock =
          """
          mkdir -p build/libs && touch build/libs/hr-lab-plain.jar        | no jar in build/libs
          mkdir -p build/libs && touch build/libs/b.jar build/libs/a.jar  | several jars in build/libs: a.jar, b.jar; keep one
          """)
  void givenBuildWithoutOneJar_whenDeploy_thenOneLineNothingSentAndExit1(String script, String line)
      throws Exception {
    // Arrange
    storeSession(stub.url, "stored", Instant.now().getEpochSecond() + 3600);
    TestProject.gradlew(project, script);

    // Act
    int exit = deploy();

    // Assert
    assertThat(exit).isEqualTo(1);
    assertThat(console.errors).containsExactly(line);
    assertThat(body).isNull();
  }

  private int deploy() {
    return deploy(null);
  }

  private int deploy(BrowserLogin.SignIn signIn) {
    DeployCommand command = new DeployCommand(console, HttpClient.newHttpClient(), home, signIn);
    command.shell = shell;
    CommandLine cli = new CommandLine(command);
    cli.setErr(new PrintWriter(err, true));
    return cli.execute("-p=" + project);
  }

  private void storeSession(String endpoint, String accessToken, long expiresAt)
      throws IOException {
    new Session(endpoint, stub.issuer, "jasmine", accessToken, "rt", expiresAt).save(sessionFile);
  }

  private static String deployResult(String url) {
    return """
    {"airlock":{"id":"hr-assistant","name":"HR assistant","created":true,"revision":1,
       "endpoint":"%s/v1/spaces/hr-lab/airlocks/hr-assistant/chat"},
     "jar":{"digest":"sha256:0f1e","tools":["read_records","aggregate_records"],
       "handlers":["HrDocumentsContextPreparator","SessionMemory","SalaryVerifier"]},
     "warnings":[],"cluster":null}
    """
        .formatted(url);
  }

  @ParameterizedTest
  @ValueSource(strings = {"PATH", "Path", "path"})
  void givenPathInAnyCase_whenRead_thenFound(String name) {
    // Arrange
    Map<String, String> windowsShell = Map.of(name, "C:\\uv", "SystemRoot", "C:\\Windows");

    // Act
    String path = DeployCommand.path(windowsShell);

    // Assert
    assertThat(path).isEqualTo("C:\\uv");
  }
}
