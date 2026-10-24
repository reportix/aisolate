package com.reportix.aisolate.backend.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Named.named;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import com.reportix.aisolate.backend.BackendApplication;
import com.reportix.aisolate.backend.domain.deploy.PythonArtifacts;
import com.reportix.aisolate.backend.support.BackendTestConfig;
import com.reportix.aisolate.backend.support.OwnDatabase;
import com.reportix.aisolate.config.AuditIngestToken;
import com.reportix.aisolate.identity.testing.Http;
import com.reportix.aisolate.identity.testing.Keycloak;
import com.reportix.aisolate.identity.testing.Tokens;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;
import org.apache.catalina.connector.Connector;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ContextConfiguration;
import tools.jackson.databind.json.JsonMapper;

/**
 * An airlock's pods fetch its code from the ingest port by digest, with the airlock's code token:
 * any other token is refused, other code is not found, and neither port answers the other's paths.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = BackendApplication.class,
    properties = "aisolate.test.database=code")
@ContextConfiguration(initializers = {BackendTestConfig.class, OwnDatabase.class})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CodeIntegrationTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  static final String KEY = BackendTestConfig.INGEST_KEY;

  /** The Python airlock hr-assistant, its code the HR manifest's zip. */
  static final String PROJECT =
      """
      apiVersion: aisolate.dev/v1
      kind: Airlock
      metadata: {name: hr-assistant}
      spec:
        preLLM:
          contextPreparator:
            handlers: [HandbookPreparator]
        llm: granite
        prompt: You are the HR assistant.
        tools: [read_records, aggregate_records]
      """;

  static final String SPACE_FORM =
      """
      {"id":"%s","name":"Code lab","llms":[{"id":"granite","provider":"ollama","url":"http://ollama:11434","model":"granite4.1:8b"}],
       "developers":["jasmine@example.com"],
       "data_sources":[{"id":"acme_hr","url":"jdbc:postgresql://acme-db:5432/hr",
                        "secret_ref":"%s-ds-acme-hr-abc123"}]}
      """;

  private final byte[] zip = PythonArtifacts.zip();
  private final String jasmine =
      Tokens.forUser(Keycloak.issuerUri(), "aisolate-ui", "jasmine", "password");
  private final String rita =
      Tokens.forUser(Keycloak.issuerUri(), "aisolate-ui", "andrew", "password");

  @LocalServerPort int port;
  @Autowired Connector auditIngestConnector;
  private String digest;

  @BeforeAll
  void deploy() {
    send(port, "POST", "/v1/spaces", rita, SPACE_FORM.formatted("code-lab", "code-lab"));
    send(
        port,
        "POST",
        "/v1/spaces/code-lab/airlocks",
        rita,
        "{\"id\":\"hr-assistant\",\"name\":\"HR assistant\"}");
    Map<String, Object> parts = new LinkedHashMap<>();
    parts.put("jar", new Http.FilePart("project.zip", zip));
    parts.put("language", "python");
    parts.put("documents", PROJECT);
    HttpResponse<String> deployed =
        Http.send(
            Http.bearer(
                    Http.multipart(
                        HttpRequest.newBuilder(uri(port, "/v1/spaces/code-lab/deploy")),
                        "POST",
                        parts),
                    jasmine)
                .build());
    assertThat(deployed.statusCode()).as(deployed.body()).isEqualTo(200);
    digest = JSON.readTree(deployed.body()).path("jar").path("digest").asString();
  }

  @Test
  void givenTheAirlocksCodeToken_whenFetched_thenTheDeployedBytesWithTheirLength() {
    // Arrange
    String token = AuditIngestToken.ofCode(KEY, "code-lab", "hr-assistant");

    // Act
    HttpResponse<byte[]> response = fetch(code("hr-assistant", digest), token);

    // Assert
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).isEqualTo(zip);
    assertThat(response.headers().firstValueAsLong("content-length")).hasValue(zip.length);
    assertThat(response.headers().firstValue("content-type")).hasValue("application/octet-stream");
  }

  @Test
  void givenNoToken_whenFetched_then401() {
    // Arrange
    String path = code("hr-assistant", digest);

    // Act
    HttpResponse<byte[]> response = fetch(path, null);

    // Assert
    assertThat(response.statusCode()).isEqualTo(401);
  }

  static Stream<Arguments> tokensNotTheAirlocksCodeToken() {
    String developer = Tokens.forUser(Keycloak.issuerUri(), "aisolate-ui", "jasmine", "password");
    return Stream.of(
        arguments(
            named(
                "another airlock's code token",
                AuditIngestToken.ofCode(KEY, "code-lab", "other-assistant"))),
        arguments(
            named(
                "the airlock's audit ingest token",
                AuditIngestToken.of(KEY, "code-lab", "hr-assistant"))),
        arguments(
            named(
                "another install's",
                AuditIngestToken.ofCode("other-key", "code-lab", "hr-assistant"))),
        arguments(named("a developer's own token", developer)));
  }

  @ParameterizedTest
  @MethodSource("tokensNotTheAirlocksCodeToken")
  void givenATokenNotTheAirlocksCodeToken_whenFetched_then403(String token) {
    // Arrange
    String path = code("hr-assistant", digest);

    // Act
    HttpResponse<byte[]> response = fetch(path, token);

    // Assert
    assertThat(response.statusCode()).isEqualTo(403);
    assertThat(new String(response.body())).doesNotContain("PK");
  }

  static Stream<Arguments> codeNotHeld() {
    return Stream.of(
        arguments(named("a stale digest", "hr-assistant"), "sha256:" + "0".repeat(64)),
        arguments(named("an airlock without a row", "nobody"), null));
  }

  @ParameterizedTest
  @MethodSource("codeNotHeld")
  void givenItsTokenButCodeTheAirlockDoesNotHold_whenFetched_then404(
      String airlock, String otherDigest) {
    // Arrange
    String token = AuditIngestToken.ofCode(KEY, "code-lab", airlock);

    // Act
    HttpResponse<byte[]> response =
        fetch(code(airlock, otherDigest == null ? digest : otherDigest), token);

    // Assert
    assertThat(response.statusCode()).isEqualTo(404);
  }

  @Test
  void givenAnotherSpacesNameAndItsToken_whenFetched_then404() {
    // Arrange: the airlock exists, in code-lab only
    String token = AuditIngestToken.ofCode(KEY, "other-lab", "hr-assistant");

    // Act
    HttpResponse<byte[]> response = fetch("/v1/code/other-lab/hr-assistant/" + digest, token);

    // Assert
    assertThat(response.statusCode()).isEqualTo(404);
  }

  @Test
  void givenTheAirlocksCodeToken_whenFetchedOnTheApiPort_then404() {
    // Arrange
    String token = AuditIngestToken.ofCode(KEY, "code-lab", "hr-assistant");

    // Act
    HttpResponse<String> response = send(port, "GET", code("hr-assistant", digest), token, null);

    // Assert
    assertThat(response.statusCode()).isEqualTo(404);
  }

  static Stream<Arguments> notOnTheIngestPort() {
    return Stream.of(
        arguments("GET", "/v1/spaces"),
        arguments("POST", "/v1/code/code-lab/hr-assistant/sha256:00"),
        arguments("GET", "/v1/codex"));
  }

  @ParameterizedTest
  @MethodSource("notOnTheIngestPort")
  void givenAnyOtherRequest_whenSentToTheIngestPort_then404(String method, String path) {
    // Arrange
    int ingest = auditIngestConnector.getLocalPort();

    // Act
    HttpResponse<String> response = send(ingest, method, path, jasmine, "{}");

    // Assert
    assertThat(response.statusCode()).isEqualTo(404);
  }

  private static String code(String airlock, String digest) {
    return "/v1/code/code-lab/" + airlock + "/" + digest;
  }

  private HttpResponse<byte[]> fetch(String path, String token) {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(uri(auditIngestConnector.getLocalPort(), path)).GET();
    if (token != null) {
      Http.bearer(request, token);
    }
    return Http.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
  }

  private static HttpResponse<String> send(
      int port, String method, String path, String bearer, String json) {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(uri(port, path))
            .header("Content-Type", "application/json")
            .method(
                method,
                json == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(json));
    return Http.send(Http.bearer(request, bearer).build());
  }

  private static URI uri(int port, String path) {
    return URI.create("http://localhost:" + port + path);
  }
}
