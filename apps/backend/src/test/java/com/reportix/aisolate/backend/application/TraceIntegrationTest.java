package com.reportix.aisolate.backend.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.reportix.aisolate.backend.BackendApplication;
import com.reportix.aisolate.backend.support.BackendTestConfig;
import com.reportix.aisolate.backend.support.OwnDatabase;
import com.reportix.aisolate.config.AuditIngestToken;
import com.reportix.aisolate.identity.testing.Http;
import com.reportix.aisolate.identity.testing.Keycloak;
import com.reportix.aisolate.identity.testing.Tokens;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.catalina.connector.Connector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Monitoring's traces end to end, against a stand-in trace store that answers as Jaeger does: the
 * list from the audit trail, a trace's audit line and its steps, who reads which.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = BackendApplication.class,
    properties = "aisolate.test.database=traces")
@ContextConfiguration(initializers = {BackendTestConfig.class, OwnDatabase.class})
class TraceIntegrationTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  /** The status the stand-in answers a trace id with; 200 and the recorded trace otherwise. */
  private static final Map<String, Integer> STORE_STATUS = new ConcurrentHashMap<>();

  private static final HttpServer JAEGER = jaeger();

  @DynamicPropertySource
  static void traceStore(DynamicPropertyRegistry registry) {
    registry.add(
        "aisolate.tracing.query-url",
        () -> "http://localhost:" + JAEGER.getAddress().getPort() + "/");
  }

  @LocalServerPort int port;
  @Autowired Connector auditIngestConnector;

  /** Each test's own airlock, so a list filtered by it holds that test's lines alone. */
  private final String airlock = "a" + UUID.randomUUID().toString().substring(0, 8);

  @ParameterizedTest
  @CsvSource({
    "andrew, hr-acme, 200, 200, 1",
    "jasmine, hr-acme, 200, 200, 1",
    // A developer of hr-acme only: another space's trace is as unknown as none
    "jasmine, other, 404, 200, 0",
    "auditor, hr-acme, 403, 403, 0",
    "eric, hr-acme, 403, 403, 0"
  })
  void givenAUser_whenReadingTraces_thenAllowedByRoleAndSpace(
      String username, String space, int trace, int list, int listed) {
    // Arrange
    String traceId = ingest(space, "How much do I earn?");
    String user = Tokens.forUser(Keycloak.issuerUri(), "aisolate-ui", username, "password");

    // Act
    HttpResponse<String> one = send("/v1/traces/" + traceId, user);
    HttpResponse<String> page = send("/v1/traces?airlock=" + airlock, user);

    // Assert
    assertThat(one.statusCode()).as(one.body()).isEqualTo(trace);
    assertThat(page.statusCode()).isEqualTo(list);
    if (list == 200) assertThat(JSON.readTree(page.body()).path("traces")).hasSize(listed);
  }

  @ParameterizedTest
  @CsvSource({"andrew, 200", "jasmine, 200", "auditor, 403", "eric, 403"})
  void givenAUser_whenAskingForMonitoringsViews_thenTracingForTheAdminAndDevelopersOnly(
      String username, int status) {
    // Arrange
    String user = Tokens.forUser(Keycloak.issuerUri(), "aisolate-ui", username, "password");

    // Act
    HttpResponse<String> response = send("/v1/monitoring", user);

    // Assert
    assertThat(response.statusCode()).isEqualTo(status);
    if (status == 200) {
      assertThat(JSON.readTree(response.body()).path("tracing").asBoolean()).isTrue();
    }
  }

  @Test
  void givenAHeldTrace_whenRead_thenTheAuditLineAndTheStepTreeWithoutAnythingNoSpanMayCarry() {
    // Arrange
    String traceId = ingest("hr-acme", "How much do I earn?");

    // Act
    HttpResponse<String> response = send("/v1/traces/" + traceId, admin());

    // Assert
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).doesNotContain("a description no span may carry");
    JsonNode trace = JSON.readTree(response.body());
    assertThat(trace.path("event").path("question").asString()).isEqualTo("How much do I earn?");
    assertThat(trace.path("spans_status").asString()).isEqualTo("held");
    JsonNode exchange = trace.path("steps").path(0).path("children").path(0);
    assertThat(exchange.path("kind").asString()).isEqualTo("exchange");
    assertThat(kinds(exchange.path("children")))
        .containsExactly("admission", "handler", "model", "tool", "verifier");
    JsonNode tool = exchange.path("children").path(3);
    assertThat(tool.path("attributes").path("aisolate.tool").asString()).isEqualTo("read_records");
    JsonNode mcp = tool.path("children").path(0);
    assertThat(mcp.path("service").asString()).isEqualTo("tool-server");
    assertThat(kinds(mcp.path("children"))).containsExactly("query");
    JsonNode verifier = exchange.path("children").path(4);
    assertThat(verifier.path("error").asBoolean()).isTrue();
    assertThat(verifier.path("error_type").asString()).isEqualTo("java.lang.IllegalStateException");
  }

  @ParameterizedTest
  @CsvSource({
    "hr-acme, 404, gone",
    "hr-acme, 503, unavailable",
    // The recorded spans name hr-acme: a line of another space naming that trace gets none
    "other, 200, gone"
  })
  void givenATraceTheStoreCannotShow_whenRead_thenTheAuditLineAndWhy(
      String space, int storeStatus, String spansStatus) {
    // Arrange
    String traceId = ingest(space, "q");
    STORE_STATUS.put(traceId, storeStatus);

    // Act
    HttpResponse<String> response = send("/v1/traces/" + traceId, admin());

    // Assert
    assertThat(response.statusCode()).isEqualTo(200);
    JsonNode trace = JSON.readTree(response.body());
    assertThat(trace.path("spans_status").asString()).isEqualTo(spansStatus);
    assertThat(trace.path("steps").isNull()).isTrue();
    assertThat(trace.path("event").path("question").asString()).isEqualTo("q");
  }

  @Test
  void givenThreeExchanges_whenListedTwoAtATime_thenNewestFirstWithTheirCalls() {
    // Arrange
    for (String q : List.of("a", "b", "c")) ingest("hr-acme", q);

    // Act
    JsonNode first = get("/v1/traces?airlock=" + airlock + "&limit=2");
    JsonNode second =
        get(
            "/v1/traces?airlock="
                + airlock
                + "&limit=2&cursor="
                + first.path("next_cursor").asString());

    // Assert
    List<String> questions = new ArrayList<>();
    first.path("traces").forEach(t -> questions.add(t.path("question").asString()));
    second.path("traces").forEach(t -> questions.add(t.path("question").asString()));
    assertThat(questions).containsExactly("c", "b", "a");
    JsonNode newest = first.path("traces").path(0);
    assertThat(newest.path("user").asString()).isEqualTo("elena@example.com");
    assertThat(newest.path("model").asString()).isEqualTo("granite4.1:8b");
    assertThat(newest.path("model_calls").asInt()).isEqualTo(2);
    assertThat(newest.path("tool_calls").asInt()).isEqualTo(1);
    assertThat(newest.path("input_tokens").asInt()).isEqualTo(1840);
  }

  @ParameterizedTest
  @CsvSource({
    "andrew, ELENA, 2",
    // A typed % is a character, not a wildcard
    "andrew, 50%, 1",
    "andrew, vacation, 0",
    "andrew, other-secret, 1",
    // Another space's question is not found for a developer of hr-acme only
    "jasmine, other-secret, 0"
  })
  void givenQuestions_whenSearched_thenThoseHoldingTheTextCaseIgnoredInTheCallersSpaces(
      String username, String q, int found) {
    // Arrange
    ingest("hr-acme", "What is Elena's salary?");
    ingest("hr-acme", "Is elena's 50% bonus paid?");
    ingest("hr-acme", "How much do I earn?");
    ingest("other", "An other-secret question");
    String user = Tokens.forUser(Keycloak.issuerUri(), "aisolate-ui", username, "password");

    // Act
    HttpResponse<String> response =
        send(
            "/v1/traces?airlock=" + airlock + "&q=" + URLEncoder.encode(q, StandardCharsets.UTF_8),
            user);

    // Assert
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(JSON.readTree(response.body()).path("traces")).hasSize(found);
  }

  // Helpers

  /** Stores an exchange of this test's airlock in {@code space}; returns its trace id. */
  private String ingest(String space, String question) {
    String traceId = UUID.randomUUID().toString().replace("-", "");
    String line =
        """
        {"ts":"2026-10-19T09:11:57.006Z","event":"exchange","trace_id":"%s",\
        "space":"%s","airlock":"%s","revision":"7b2280eab4b5",\
        "user":"elena@example.com","client":"acme-chat","question":"%s",\
        "answer":"Your gross salary is 78,000 EUR.","outcome":"answered","reason":null,\
        "tools":[{"name":"read_records","args":{"entity":"hr.compensation"},\
        "data_source":"hr","rows":1,"rule_version":"1.7b2280eab4b5","sql_hash":"3f9a",\
        "rows_hash":"5c1e"}],"evidence":["ev-1"],"verifiers":[],\
        "figures":{"sourced":1,"computed":0,"unsourced":0,"mismatch":0},"grounding":null,\
        "model":{"provider":"ollama","name":"granite4.1:8b","calls":2,"input_tokens":1840,\
        "output_tokens":212},"duration_ms":6114}\
        """
            .formatted(traceId, space, airlock, question);
    HttpResponse<String> response =
        Http.send(
            Http.bearer(
                    HttpRequest.newBuilder(
                        URI.create(
                            "http://localhost:"
                                + auditIngestConnector.getLocalPort()
                                + "/v1/audit/events")),
                    AuditIngestToken.of(BackendTestConfig.INGEST_KEY, space, airlock))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(line))
                .build());
    assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
    return traceId;
  }

  private static List<String> kinds(JsonNode steps) {
    List<String> kinds = new ArrayList<>();
    steps.forEach(s -> kinds.add(s.path("kind").asString()));
    return kinds;
  }

  private JsonNode get(String path) {
    HttpResponse<String> response = send(path, admin());
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    return JSON.readTree(response.body());
  }

  private HttpResponse<String> send(String path, String token) {
    return Http.send(
        Http.bearer(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)), token)
            .GET()
            .build());
  }

  private static String admin() {
    return Tokens.forUser(Keycloak.issuerUri(), "aisolate-ui", "andrew", "password");
  }

  private static HttpServer jaeger() {
    try (InputStream in =
        TraceIntegrationTest.class.getResourceAsStream("/traces/jaeger-trace.json")) {
      byte[] trace = in.readAllBytes();
      HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
      server.createContext(
          "/api/traces/",
          exchange -> {
            String id = exchange.getRequestURI().getPath().substring("/api/traces/".length());
            int status = STORE_STATUS.getOrDefault(id, 200);
            byte[] body =
                status == 200
                    ? trace
                    : "{\"data\":null,\"errors\":[]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
          });
      server.start();
      return server;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
