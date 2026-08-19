package com.reportix.aisolate.airlock.infrastructure.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import com.reportix.aisolate.airlock.domain.airlock.AirlockDefinition;
import com.reportix.aisolate.airlock.domain.chat.ToolCallFailedException;
import com.reportix.aisolate.airlock.infrastructure.config.AirlockProperties;
import com.reportix.aisolate.airlock.support.TestAirlocks;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The user's token must travel with the call: read from the calling thread's security context,
 * never from the SDK's own threads, and never from another request. These tests drive the real MCP
 * transport over a real socket, because the whole risk lives in how the SDK propagates context: a
 * mocked client would prove nothing.
 */
class McpSyncToolClientTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private static final AirlockDefinition AIRLOCK =
      TestAirlocks.acmeHr("7", "default", List.of("read_records"), TestAirlocks.NO_HANDLERS);

  private HttpServer toolServer;
  private McpSyncToolClient client;

  private volatile int failureStatus;

  /** The result every tools/call gets instead of the echoed bearer, when set. */
  private volatile String callResult;

  private volatile String failureBody = "";

  /** How many requests the stub tool server received, and every Authorization header on them. */
  private final AtomicInteger requests = new AtomicInteger();

  private final List<String> authorizationHeaders = new CopyOnWriteArrayList<>();

  /** The {@code _meta} of every tools/call and tools/list the stub received, as JSON text. */
  private final List<String> metas = new CopyOnWriteArrayList<>();

  /** The {@code traceparent} of every tools/call that carried one. */
  private final List<String> traceparents = new CopyOnWriteArrayList<>();

  @BeforeEach
  void startStubToolServer() throws IOException {
    toolServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    toolServer.createContext("/mcp", this::handleJsonRpc);
    toolServer.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
    toolServer.start();
    // A bare origin, as the pod's bean gets it: the stub listens on the conventional /mcp.
    String origin = "http://127.0.0.1:" + toolServer.getAddress().getPort();
    client =
        (McpSyncToolClient)
            new McpClientConfig()
                .mcpToolClient(
                    new AirlockProperties(
                        null,
                        null,
                        null,
                        new AirlockProperties.ToolServer(origin),
                        List.of(),
                        null),
                    JSON);
  }

  @AfterEach
  void stopStubToolServer() {
    SecurityContextHolder.clearContext();
    client.close();
    toolServer.stop(0);
  }

  @Test
  void givenJwtOnCallingThread_whenToolCalled_thenTheTokenTravelsAsAnAuthorizationHeader() {
    // Arrange
    jwtOnThisThread("t1-token");

    // Act
    String evidence = client.callTool("read_records", Map.of(), AIRLOCK);

    // Assert: every request the SDK sent carried it, the lazy handshake included; the call named
    // the airlock and its revision, both from the admitted airlock
    assertThat(evidence).isEqualTo("Bearer t1-token");
    assertThat(authorizationHeaders).containsOnly("Bearer t1-token").hasSize(requests.get());
    JsonNode meta = JSON.readTree(metas.getFirst());
    assertThat(metas).hasSize(1);
    assertThat(meta.path("airlock").asString()).isEqualTo("acme-hr");
    assertThat(meta.path("revision").asString()).isEqualTo("7");
    assertThat(meta.has("version")).isFalse();
  }

  @Test
  void givenSpanOnCallingThread_whenToolCalled_thenItsTraceparentTravels() {
    // Arrange: the tool span current, as the OpenTelemetry bridge makes it
    jwtOnThisThread("t1-token");
    SpanContext toolSpan =
        SpanContext.create(
            "4bf92f3577b34da6a3ce929d0e0e4736",
            "00f067aa0ba902b7",
            TraceFlags.getSampled(),
            TraceState.getDefault());

    // Act
    try (Scope current = Context.current().with(Span.wrap(toolSpan)).makeCurrent()) {
      client.callTool("read_records", Map.of(), AIRLOCK);
    }

    // Assert: the tool server's span continues the airlock's trace
    assertThat(traceparents)
        .containsExactly("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");
  }

  @Test
  void givenAdmittedAirlock_whenToolsListed_thenTheBearerAndThePinTravel() {
    // Arrange
    jwtOnThisThread("t1-token");

    // Act
    client.listTools(AIRLOCK);

    // Assert: the same bearer and pin as a tools/call
    assertThat(authorizationHeaders).containsOnly("Bearer t1-token").hasSize(requests.get());
    assertThat(metas).hasSize(1);
    JsonNode meta = JSON.readTree(metas.getFirst());
    assertThat(meta.path("airlock").asString()).isEqualTo("acme-hr");
    assertThat(meta.path("revision").asString()).isEqualTo("7");
  }

  @Test
  void givenFirstListingFailed_whenToolsListedTwiceMore_thenTheServerIsListedOnceMore() {
    // Arrange: a failed listing must not be kept
    jwtOnThisThread("t1-token");
    rejectWith(500, "busy");
    catchThrowableOfType(ToolCallFailedException.class, () -> client.listTools(AIRLOCK));
    rejectWith(0, "");

    // Act
    client.listTools(AIRLOCK);
    client.listTools(AIRLOCK);

    // Assert: the failed list and the first good one; the list is fixed for the pod's life
    assertThat(metas).hasSize(2);
  }

  @Test
  void givenConcurrentExchanges_whenEachThreadHoldsItsOwnJwt_thenNoTokenReachesAnotherUsersRequest()
      throws Exception {
    // Arrange: the test thread holds somebody else's token, which must never reach a request
    jwtOnThisThread("somebody-elses-token");
    int exchanges = 24;
    CountDownLatch start = new CountDownLatch(1);
    List<String> mismatches = new CopyOnWriteArrayList<>();
    List<String> echoes = new CopyOnWriteArrayList<>();
    List<Thread> threads = new ArrayList<>();

    // Act
    for (int exchange = 0; exchange < exchanges; exchange++) {
      String token = "token-of-user-" + exchange;
      threads.add(
          Thread.ofVirtual()
              .start(
                  () -> {
                    jwtOnThisThread(token);
                    awaitLatch(start);
                    try {
                      String echoed = client.callTool("read_records", Map.of(), AIRLOCK);
                      echoes.add(echoed);
                      if (!("Bearer " + token).equals(echoed)) {
                        mismatches.add(token + " saw " + echoed);
                      }
                    } catch (Throwable t) {
                      mismatches.add(token + " threw " + t);
                    }
                  }));
    }
    start.countDown();
    for (Thread thread : threads) {
      thread.join(TimeUnit.SECONDS.toMillis(30));
    }

    // Assert: mismatches first, so a thread that threw shows its exception, not just a missing echo
    assertThat(mismatches).isEmpty();
    assertThat(echoes).hasSize(exchanges);
    assertThat(authorizationHeaders).doesNotContain("Bearer somebody-elses-token");
  }

  @ParameterizedTest
  @NullSource
  @MethodSource("authenticationsWithoutAJwt")
  void givenNoJwtOnCallingThread_whenToolCalled_thenNothingIsSentAndTheCallFailsClosed(
      Authentication authentication) {
    // Arrange
    SecurityContextHolder.getContext().setAuthentication(authentication);

    // Act
    IllegalStateException failure =
        catchThrowableOfType(
            IllegalStateException.class, () -> client.callTool("read_records", Map.of(), AIRLOCK));

    // Assert: a programming error, refused before anything leaves the pod
    assertThat(failure).hasMessage("MCP call without an authenticated user on this thread");
    assertThat(requests.get()).isZero();
  }

  static Stream<Authentication> authenticationsWithoutAJwt() {
    return Stream.of(new TestingAuthenticationToken("somebody", "n/a"));
  }

  static Stream<Arguments> failingToolServers() {
    return Stream.of(
        // 401, as for an expired token mid-exchange: the body quotes the token and the user
        arguments(
            401,
            "{\"error\":\"expired token t1-token for Eric Lindholm\"}",
            "the tool server rejected the request",
            List.of("t1-token", "Eric Lindholm")),
        // a misconfigured tool server answering with something that is not JSON-RPC: rows
        arguments(
            500,
            "full_name,gross_salary\nEric Lindholm,78000",
            "the tool server could not be reached",
            List.of("Eric Lindholm", "78000")));
  }

  @ParameterizedTest
  @MethodSource("failingToolServers")
  void givenToolServerFails_whenToolCalled_thenTheMessageIsFixedAndQuotesNothingItWasGiven(
      int status, String body, String message, List<String> secrets) {
    // Arrange
    jwtOnThisThread("t1-token");
    rejectWith(status, body);

    // Act
    ToolCallFailedException failure =
        catchThrowableOfType(
            ToolCallFailedException.class,
            () -> client.callTool("read_records", Map.of(), AIRLOCK));

    // Assert: this message is logged, so it may quote neither the token nor a row
    assertThat(failure).hasMessage(message);
    secrets.forEach(secret -> assertThat(failure.toString()).doesNotContain(secret));
  }

  static Stream<Arguments> errorResults() {
    return Stream.of(
        arguments("{\"error\":\"login_refused\",\"data_source\":\"hr\"}", "login_refused", "hr"),
        arguments("{\"error\":\"unreachable\",\"data_source\":\"hr\"}", "unreachable", "hr"),
        arguments("{\"error\":\"failed\",\"data_source\":\"hr\"}", "failed", "hr"),
        arguments("{\"error\":\"disk_on_fire\",\"data_source\":7}", "failed", null),
        arguments(null, "failed", null));
  }

  @ParameterizedTest
  @MethodSource("errorResults")
  void givenErrorResultWithMeta_whenToolCalled_thenItsCategoryAndDataSource(
      String meta, String error, String dataSource) {
    // Arrange
    jwtOnThisThread("t1-token");
    callResult =
        """
        {"content":[{"type":"text","text":"The data source refused the login"}],"isError":true%s}\
        """
            .formatted(meta == null ? "" : ",\"_meta\":" + meta);

    // Act
    ToolCallFailedException failure =
        catchThrowableOfType(
            ToolCallFailedException.class,
            () -> client.callTool("read_records", Map.of(), AIRLOCK));

    // Assert
    assertThat(failure).hasMessage("The data source refused the login");
    assertThat(failure.getError()).isEqualTo(error);
    assertThat(failure.getDataSource()).isEqualTo(dataSource);
  }

  /** Puts a JWT authentication carrying this token value into the calling thread's context. */
  private static void jwtOnThisThread(String tokenValue) {
    Jwt jwt =
        new Jwt(
            tokenValue,
            Instant.now(),
            Instant.now().plusSeconds(60),
            Map.of("alg", "RS256"),
            Map.of("sub", "user-of-" + tokenValue, "aud", List.of("aisolate")));
    SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
  }

  /** The smallest MCP server that answers a handshake and echoes the caller's bearer back. */
  private void handleJsonRpc(HttpExchange exchange) throws IOException {
    requests.incrementAndGet();
    String authorization = exchange.getRequestHeaders().getFirst("Authorization");
    // Like the real tool server: no bearer, no answer. The handshake is a request like any other,
    // so a header-less initialize cannot pass unnoticed.
    if (authorization == null) {
      exchange.sendResponseHeaders(401, -1);
      exchange.close();
      return;
    }
    authorizationHeaders.add(authorization);
    JsonNode request;
    try (InputStream body = exchange.getRequestBody()) {
      request = JSON.readTree(new String(body.readAllBytes(), StandardCharsets.UTF_8));
    }
    String method = request.path("method").asString();
    if ("tools/call".equals(method) || "tools/list".equals(method)) {
      metas.add(request.path("params").path("_meta").toString());
    }
    String traceparent = exchange.getRequestHeaders().getFirst("traceparent");
    if ("tools/call".equals(method) && traceparent != null) {
      traceparents.add(traceparent);
    }
    if (failureStatus != 0 && method.startsWith("tools/")) {
      byte[] failure = failureBody.getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(failureStatus, failure.length);
      exchange.getResponseBody().write(failure);
      exchange.close();
      return;
    }
    if (method.startsWith("notifications/")) {
      exchange.sendResponseHeaders(202, -1);
      exchange.close();
      return;
    }
    String result =
        "tools/call".equals(method) && callResult != null
            ? callResult
            : resultFor(method, request, authorization);
    respond(exchange, request.path("id").asString(), result);
  }

  private static String resultFor(String method, JsonNode request, String authorization) {
    if ("initialize".equals(method)) {
      return """
      {"protocolVersion":"%s","capabilities":{"tools":{}},\
      "serverInfo":{"name":"stub-mcp-server","version":"0.1.0"}}\
      """
          .formatted(request.path("params").path("protocolVersion").asString());
    }
    if ("tools/list".equals(method)) {
      return """
      {"tools":[]}\
      """;
    }
    // tools/call: the evidence is the bearer we were called with, so the test can compare it
    return """
    {"content":[{"type":"text","text":"%s"}],"isError":false}\
    """
        .formatted(authorization);
  }

  private static void respond(HttpExchange exchange, String id, String result) throws IOException {
    byte[] body =
        """
        {"jsonrpc":"2.0","id":"%s","result":%s}\
        """
            .formatted(id, result)
            .getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(200, body.length);
    exchange.getResponseBody().write(body);
    exchange.close();
  }

  private static void awaitLatch(CountDownLatch latch) {
    try {
      latch.await(30, TimeUnit.SECONDS);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }

  /** Makes every later request fail with this status and body. */
  private void rejectWith(int status, String body) {
    failureStatus = status;
    failureBody = body;
  }
}
