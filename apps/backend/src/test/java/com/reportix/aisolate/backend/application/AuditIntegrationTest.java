package com.reportix.aisolate.backend.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Named.named;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import com.reportix.aisolate.backend.BackendApplication;
import com.reportix.aisolate.backend.domain.audit.AuditLine;
import com.reportix.aisolate.backend.support.BackendTestConfig;
import com.reportix.aisolate.backend.support.Containers;
import com.reportix.aisolate.backend.support.OwnDatabase;
import com.reportix.aisolate.config.AuditIngestToken;
import com.reportix.aisolate.identity.testing.Http;
import com.reportix.aisolate.identity.testing.Keycloak;
import com.reportix.aisolate.identity.testing.Tokens;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import org.apache.catalina.connector.Connector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ContextConfiguration;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The audit trail end to end: the ingest takes a line only with the ingest token of the airlock it
 * names, the table keeps what it took, the chain shows what changed, and the admin and the auditor
 * read all of it, a developer only their spaces' events.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = BackendApplication.class,
    properties = "aisolate.test.database=audit")
@ContextConfiguration(initializers = {BackendTestConfig.class, OwnDatabase.class})
class AuditIntegrationTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  @LocalServerPort int port;
  @Autowired Connector auditIngestConnector;

  private final String space = "s" + UUID.randomUUID().toString().substring(0, 8);
  private final String airlock = "hr";

  // Ingest

  @Test
  void givenNoToken_whenIngesting_then401() {
    // Arrange
    String line = line(space, airlock, "q");

    // Act
    HttpResponse<String> response = ingest(null, line);

    // Assert
    assertThat(response.statusCode()).isEqualTo(401);
  }

  /** Each gives the token sent for a line of the space under test. */
  static Stream<Arguments> tokensNotTheAirlocks() {
    String developer = Tokens.forUser(Keycloak.issuerUri(), "aisolate-ui", "jasmine", "password");
    return Stream.of(
        arguments(named("another airlock's of the space", tokenFor("sales"))),
        arguments(
            named(
                "the same airlock in another space",
                (UnaryOperator<String>)
                    s -> AuditIngestToken.of(BackendTestConfig.INGEST_KEY, "other", "hr"))),
        arguments(
            named(
                "another install's",
                (UnaryOperator<String>) s -> AuditIngestToken.of("other-key", s, "hr"))),
        arguments(named("a developer's own token", (UnaryOperator<String>) s -> developer)));
  }

  private static UnaryOperator<String> tokenFor(String airlock) {
    return s -> AuditIngestToken.of(BackendTestConfig.INGEST_KEY, s, airlock);
  }

  @ParameterizedTest
  @MethodSource("tokensNotTheAirlocks")
  void givenATokenNotTheIngestTokenOfTheNamedAirlock_whenIngesting_then403AndNothingStored(
      UnaryOperator<String> token) {
    // Arrange
    String line = line(space, airlock, "q");

    // Act
    HttpResponse<String> response = ingest(token.apply(space), line);

    // Assert
    assertThat(response.statusCode()).isEqualTo(403);
    assertThat(verify(space, airlock, auditor()).path("events").asLong()).isZero();
  }

  @Test
  void givenTheAirlocksToken_whenTwoLinesIngested_thenEachIsStoredAndTheSecondChainsToTheFirst() {
    // Arrange
    String token = AuditIngestToken.of(BackendTestConfig.INGEST_KEY, space, airlock);

    // Act
    HttpResponse<String> first = ingest(token, line(space, airlock, "first"));
    HttpResponse<String> second = ingest(token, line(space, airlock, "second"));

    // Assert
    assertThat(first.statusCode()).isEqualTo(201);
    assertThat(second.statusCode()).isEqualTo(201);
    JsonNode one = event(JSON.readTree(first.body()).path("id").asLong());
    JsonNode two = event(JSON.readTree(second.body()).path("id").asLong());
    assertThat(one.path("prev_hash").asString()).isEqualTo(AuditLine.GENESIS);
    assertThat(two.path("prev_hash").asString()).isEqualTo(one.path("hash").asString());
    assertThat(two.path("event").path("question").asString()).isEqualTo("second");
    assertThat(two.path("event").path("tools").path(0).path("args").path("entity").asString())
        .isEqualTo("hr.compensation");
  }

  @Test
  void givenALineStoredAlready_whenTheSameLineIsSentAgain_then200WithTheStoredId() {
    // Arrange
    String token = AuditIngestToken.of(BackendTestConfig.INGEST_KEY, space, airlock);
    String line = line(space, airlock, "retried");
    long stored = JSON.readTree(ingest(token, line).body()).path("id").asLong();

    // Act
    HttpResponse<String> again = ingest(token, line);

    // Assert
    assertThat(again.statusCode()).isEqualTo(200);
    assertThat(JSON.readTree(again.body()).path("id").asLong()).isEqualTo(stored);
    assertThat(verify(space, airlock, auditor()).path("events").asLong()).isEqualTo(1);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "not json",
        "[]",
        "{\"event\":\"exchange\",\"space\":\"SPACE\",\"airlock\":\"hr\",\"outcome\":\"answered\"}",
        "{\"ts\":\"2026-10-18T09:00:00Z\",\"event\":\"chat\",\"space\":\"SPACE\",\"airlock\":\"hr\",\"outcome\":\"answered\"}",
        "{\"ts\":\"2026-10-18T09:00:00Z\",\"event\":\"exchange\",\"space\":\"SPACE\",\"airlock\":\"hr\",\"outcome\":\"fine\"}"
      })
  void givenABodyThatIsNoAuditLine_whenIngested_then400(String body) {
    // Arrange
    String token = AuditIngestToken.of(BackendTestConfig.INGEST_KEY, space, airlock);

    // Act
    HttpResponse<String> response = ingest(token, body.replace("SPACE", space));

    // Assert
    assertThat(response.statusCode()).isEqualTo(400);
  }

  @Test
  void givenTheIngestPath_whenPostedToTheApiPort_then404() {
    // Arrange
    String token = AuditIngestToken.of(BackendTestConfig.INGEST_KEY, space, airlock);
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v1/audit/events"))
            .header("Authorization", "Bearer " + token)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(line(space, airlock, "q")))
            .build();

    // Act
    HttpResponse<String> response = Http.send(request);

    // Assert
    assertThat(response.statusCode()).isEqualTo(404);
  }

  @ParameterizedTest
  @ValueSource(strings = {"/v1/spaces", "/v1/audit/events"})
  void givenAUsersToken_whenTheApiIsAskedOnTheIngestPort_then404(String path) {
    // Arrange: a pod holds its users' tokens; the ingest port must not lead it to the API
    HttpRequest request =
        Http.bearer(HttpRequest.newBuilder(ingestUri(path)), admin()).GET().build();

    // Act
    HttpResponse<String> response = Http.send(request);

    // Assert
    assertThat(response.statusCode()).isEqualTo(404);
  }

  // Append-only, at the database

  static Stream<Arguments> changes() {
    return Stream.of(
        arguments("aisolate", "UPDATE audit_events SET question = 'changed'"),
        arguments("aisolate", "DELETE FROM audit_events"),
        arguments("aisolate", "TRUNCATE audit_events"),
        arguments("aisolate_audit_writer", "UPDATE audit_events SET question = 'changed'"),
        arguments("aisolate_audit_writer", "DELETE FROM audit_events"));
  }

  @ParameterizedTest
  @MethodSource("changes")
  void givenStoredLines_whenChangedOrRemovedInTheDatabase_thenRefused(String role, String sql) {
    // Arrange
    String token = AuditIngestToken.of(BackendTestConfig.INGEST_KEY, space, airlock);
    ingest(token, line(space, airlock, "kept"));

    // Act / Assert
    assertThatThrownBy(() -> execute("SET ROLE " + role, sql))
        .isInstanceOf(SQLException.class)
        .hasMessageMatching("(?s).*(append-only|permission denied).*");
    assertThat(verify(space, airlock, auditor()).path("events").asLong()).isEqualTo(1);
  }

  // The chain

  @Test
  void givenAnUntouchedChain_whenVerified_thenOk() {
    // Arrange
    String token = AuditIngestToken.of(BackendTestConfig.INGEST_KEY, space, airlock);
    for (String q : List.of("a", "b", "c")) ingest(token, line(space, airlock, q));

    // Act
    JsonNode verification = verify(space, airlock, auditor());

    // Assert
    assertThat(verification.path("status").asString()).isEqualTo("ok");
    assertThat(verification.path("events").asLong()).isEqualTo(3);
    assertThat(verification.path("last_id").asLong() - verification.path("first_id").asLong())
        .isEqualTo(2);
    assertThat(verification.path("broken").isNull()).isTrue();
  }

  static Stream<Arguments> tampers() {
    return Stream.of(
        arguments(
            named(
                "the line itself rewritten, its hash left",
                "UPDATE audit_events SET body = replace(body, '\"question\":\"b\"',"
                    + " '\"question\":\"x\"') WHERE id = ?"),
            1,
            "altered"),
        arguments(
            named(
                "a column rewritten behind the line's back",
                "UPDATE audit_events SET question = 'x' WHERE id = ?"),
            1,
            "altered"),
        arguments(
            named(
                "the line taken out: the next one no longer links",
                "DELETE FROM audit_events WHERE id = ?"),
            2,
            "removed"));
  }

  @ParameterizedTest
  @MethodSource("tampers")
  void givenALineTamperedBehindTheTrigger_whenVerified_thenBrokenAtTheFirstLineThatFails(
      String tamper, int brokenAt, String reason) throws SQLException {
    // Arrange
    String token = AuditIngestToken.of(BackendTestConfig.INGEST_KEY, space, airlock);
    List<Long> ids =
        Stream.of("a", "b", "c")
            .map(q -> JSON.readTree(ingest(token, line(space, airlock, q)).body()))
            .map(body -> body.path("id").asLong())
            .toList();
    // A superuser can switch the trigger off; the chain is what still tells.
    execute("SET session_replication_role = replica", tamper.replace("?", ids.get(1).toString()));

    // Act
    JsonNode verification = verify(space, airlock, auditor());

    // Assert
    assertThat(verification.path("status").asString()).isEqualTo("broken");
    assertThat(verification.path("broken").path("id").asLong()).isEqualTo(ids.get(brokenAt));
    assertThat(verification.path("broken").path("reason").asString()).isEqualTo(reason);
  }

  // Reading

  @Test
  void givenThreeLines_whenListedTwoAtATime_thenNewestFirstOverTwoPages() {
    // Arrange
    String token = AuditIngestToken.of(BackendTestConfig.INGEST_KEY, space, airlock);
    for (String q : List.of("a", "b", "c")) ingest(token, line(space, airlock, q));

    // Act
    JsonNode first = get("/v1/audit/events?space=" + space + "&limit=2", auditor());
    JsonNode second =
        get(
            "/v1/audit/events?space="
                + space
                + "&limit=2&cursor="
                + first.path("next_cursor").asString(),
            auditor());

    // Assert
    assertThat(questions(first)).containsExactly("c", "b");
    assertThat(questions(second)).containsExactly("a");
    assertThat(second.path("next_cursor").isNull()).isTrue();
    JsonNode newest = first.path("events").path(0);
    assertThat(newest.path("figures").path("sourced").asInt()).isEqualTo(1);
    assertThat(newest.path("input_tokens").asInt()).isEqualTo(1840);
    assertThat(newest.path("user").asString()).isEqualTo("elena@example.com");
  }

  @Test
  void givenATraceId_whenListed_thenOnlyThatExchange() {
    // Arrange
    String token = AuditIngestToken.of(BackendTestConfig.INGEST_KEY, space, airlock);
    for (String q : List.of("a", "b")) ingest(token, line(space, airlock, q));

    // Act
    JsonNode page = get("/v1/audit/events?trace_id=trace-" + space + "-b", auditor());

    // Assert
    assertThat(questions(page)).containsExactly("b");
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        "SALARY|salary",
        "-leave|leave",
        "ELENA@EXAMPLE|leave,salary",
        "acme-chat|leave,salary",
        "50%|"
      })
  void givenASearch_whenListed_thenLinesWhoseQuestionTraceUserOrClientHoldsIt(
      String q, String expected) {
    // Arrange
    String token = AuditIngestToken.of(BackendTestConfig.INGEST_KEY, space, airlock);
    for (String question : List.of("salary", "leave")) {
      ingest(token, line(space, airlock, question));
    }

    // Act
    JsonNode page =
        get(
            "/v1/audit/events?space="
                + space
                + "&q="
                + java.net.URLEncoder.encode(q, java.nio.charset.StandardCharsets.UTF_8),
            auditor());

    // Assert
    assertThat(questions(page))
        .containsExactlyElementsOf(expected == null ? List.of() : List.of(expected.split(",")));
  }

  @ParameterizedTest
  @CsvSource({
    "andrew, 200, 200, 200, 1",
    "auditor, 200, 200, 200, 1",
    // A developer of hr-acme only: the list leaves this space out; its event and chain are 403
    "jasmine, 200, 403, 403, 0",
    "eric, 403, 403, 403, 0"
  })
  void givenAUser_whenReadingTheAudit_thenAllowedByRoleAndSpace(
      String username, int list, int event, int verify, int seen) {
    // Arrange
    String token = AuditIngestToken.of(BackendTestConfig.INGEST_KEY, space, airlock);
    long id = JSON.readTree(ingest(token, line(space, airlock, "q")).body()).path("id").asLong();
    String user = Tokens.forUser(Keycloak.issuerUri(), "aisolate-ui", username, "password");

    // Act
    List<Integer> statuses =
        Stream.of(
                "/v1/audit/events",
                "/v1/audit/events/" + id,
                "/v1/audit/verify?space=" + space + "&airlock=" + airlock)
            .map(path -> send(path, user).statusCode())
            .toList();
    HttpResponse<String> listed = send("/v1/audit/events?trace_id=trace-" + space + "-q", user);

    // Assert
    assertThat(statuses).containsExactly(list, event, verify);
    assertThat(questions(JSON.readTree(listed.body()))).hasSize(seen);
  }

  // Export

  @ParameterizedTest
  @CsvSource({
    "login_refused, login_refused",
    "unreachable, unreachable",
    // No setting of the data source's owner can fix it: the airlock has nothing to show
    "failed, ''",
    "'', ''"
  })
  void givenTheNewestToolCallsError_whenAskingForTheDataError_thenOnlyALoginOrReachFailure(
      String error, String expected) {
    // Arrange: an older exchange that could not log in, the newest tool call, then a greeting
    String token = AuditIngestToken.of(BackendTestConfig.INGEST_KEY, space, airlock);
    ingest(token, failedWith("login_refused", line(space, airlock, "older")));
    String newest = line(space, airlock, "newest");
    ingest(token, error.isEmpty() ? newest : failedWith(error, newest));
    ingest(token, line(space, airlock, "hello").replaceFirst("\"tools\":\\[.*?]", "\"tools\":[]"));

    // Act
    JsonNode found = get("/v1/audit/data-error?space=" + space + "&airlock=" + airlock, auditor());

    // Assert
    JsonNode dataError = found.path("data_error");
    if (expected.isEmpty()) {
      assertThat(dataError.isNull()).isTrue();
    } else {
      assertThat(dataError.path("data_source").asString()).isEqualTo("hr");
      assertThat(dataError.path("error").asString()).isEqualTo(expected);
      assertThat(dataError.path("ts").asString()).isEqualTo("2026-10-18T09:11:57.006Z");
    }
  }

  @Test
  void givenNoToken_whenReadingTheAudit_then401() {
    // Arrange / Act
    HttpResponse<String> response = send("/v1/audit/events", null);

    // Assert
    assertThat(response.statusCode()).isEqualTo(401);
  }

  @Test
  void givenAnAuditor_whenListingSpaces_thenNone() {
    // Arrange / Act: the auditor reads the audit trail and nothing else
    HttpResponse<String> response = send("/v1/spaces", auditor());

    // Assert
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(JSON.readTree(response.body()).isEmpty()).isTrue();
  }

  // Helpers

  /** An exchange line as the airlock writes it, its trace named after the space and question. */
  private static String line(String space, String airlock, String question) {
    return """
    {"ts":"2026-10-18T09:11:57.006Z","event":"exchange","trace_id":"trace-%s-%s",\
    "space":"%s","airlock":"%s","revision":"7b2280eab4b5",\
    "user":"elena@example.com","client":"acme-chat","question":"%s",\
    "answer":"Your gross salary is 78,000 EUR.","outcome":"answered","reason":null,\
    "tools":[{"name":"read_my_records","args":{"entity":"hr.compensation","limit":20.5},\
    "data_source":"hr","rows":1,"rule_version":"1.7b2280eab4b5","sql_hash":"3f9a1c0d2b7e",\
    "rows_hash":"5c1e"}],"evidence":["ev-1"],"verifiers":[],\
    "figures":{"sourced":1,"computed":0,"unsourced":0,"mismatch":0},"grounding":null,\
    "model":{"provider":"ollama","name":"granite4.1:8b","calls":2,"input_tokens":1840,\
    "output_tokens":212},"duration_ms":6114}\
    """
        .formatted(space, question, space, airlock, question);
  }

  /** The line's tool call as a failed one: no rows, its error category. */
  private static String failedWith(String error, String line) {
    return line.replace("\"rows\":1,", "\"rows\":null,\"error\":\"" + error + "\",");
  }

  private static List<String> questions(JsonNode page) {
    List<String> questions = new java.util.ArrayList<>();
    for (JsonNode event : page.path("events")) questions.add(event.path("question").asString());
    return questions;
  }

  private HttpResponse<String> ingest(String token, String line) {
    return Http.send(
        Http.bearer(HttpRequest.newBuilder(ingestUri("/v1/audit/events")), token)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(line))
            .build());
  }

  private URI ingestUri(String path) {
    return URI.create("http://localhost:" + auditIngestConnector.getLocalPort() + path);
  }

  private JsonNode event(long id) {
    return get("/v1/audit/events/" + id, auditor());
  }

  private JsonNode verify(String space, String airlock, String token) {
    return get("/v1/audit/verify?space=" + space + "&airlock=" + airlock, token);
  }

  private JsonNode get(String path, String token) {
    HttpResponse<String> response = send(path, token);
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    return JSON.readTree(response.body());
  }

  private HttpResponse<String> send(String path, String token) {
    return Http.send(
        Http.bearer(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)), token)
            .GET()
            .build());
  }

  private static String auditor() {
    return Tokens.forUser(Keycloak.issuerUri(), "aisolate-ui", "auditor", "password");
  }

  private static String admin() {
    return Tokens.forUser(Keycloak.issuerUri(), "aisolate-ui", "andrew", "password");
  }

  /** On the audit database, as the container's superuser, after {@code setup} on the connection. */
  private static void execute(String setup, String sql) throws SQLException {
    var postgres = Containers.aisolate();
    String url =
        "jdbc:postgresql://" + postgres.getHost() + ":" + postgres.getMappedPort(5432) + "/audit";
    try (Connection connection = DriverManager.getConnection(url, "aisolate", "aisolate");
        Statement statement = connection.createStatement()) {
      statement.execute(setup);
      statement.execute(sql);
    }
  }
}
