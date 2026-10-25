package com.reportix.aisolate.airlock.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Named.named;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import com.reportix.aisolate.airlock.AirlockApplication;
import com.reportix.aisolate.airlock.domain.chat.McpToolClient;
import com.reportix.aisolate.airlock.domain.chat.ToolCallFailedException;
import com.reportix.aisolate.airlock.support.AirlockTestConfig;
import com.reportix.aisolate.airlock.support.AuditCapture;
import com.reportix.aisolate.airlock.support.FakeToolClient;
import com.reportix.aisolate.airlock.support.ScriptedChatModel;
import com.reportix.aisolate.airlock.support.UnknownKeyJwt;
import com.reportix.aisolate.identity.testing.Http;
import com.reportix.aisolate.identity.testing.Keycloak;
import com.reportix.aisolate.identity.testing.Tokens;
import io.micrometer.observation.ObservationRegistry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.web.client.ResourceAccessException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The endpoint of an airlock with real Keycloak tokens. The model and the tool server are stubs:
 * these tests are about what happens before and around the exchange.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = AirlockApplication.class)
@ContextConfiguration(
    initializers = {AirlockTestConfig.class, ChatEndpointSecurityTest.Documents.class})
@Import(ChatEndpointSecurityTest.Stubs.class)
class ChatEndpointSecurityTest {

  /**
   * The airlocks these cases talk to, as the pod reads them: documents, no pin, every airlock
   * served.
   */
  static final class Documents
      implements ApplicationContextInitializer<ConfigurableApplicationContext> {
    @Override
    public void initialize(ConfigurableApplicationContext context) {
      TestPropertyValues.of(
              "aisolate.config.dir="
                  + Path.of("src/test/resources/documents-security").toAbsolutePath(),
              "aisolate.tool-server.url=http://hr-acme-acme-hr-mcp-server:8081/mcp")
          .applyTo(context);
    }
  }

  @TestConfiguration
  static class Stubs {
    @Bean
    @Primary
    ScriptedChatModel scriptedChatModel() {
      return new ScriptedChatModel();
    }

    @Bean
    @Primary
    McpToolClient fakeToolClient() {
      return new FakeToolClient()
          .tool(
              "read_records",
              args -> {
                if (args.containsKey("boom")) {
                  throw new IllegalStateException("tool server unreachable");
                }
                if (args.containsKey("salary")) {
                  // The tool server's argument check quotes the value
                  throw new ToolCallFailedException(
                      "Invalid arguments: value for numeric column salary is not a number: "
                          + args.get("salary"));
                }
                return FakeToolClient.evidence(1, "own-record");
              });
    }

    /** The spans the pod would send to the OTLP endpoint. */
    @Bean
    InMemorySpanExporter spans() {
      return InMemorySpanExporter.create();
    }
  }

  private static final JsonMapper JSON = JsonMapper.builder().build();

  @RegisterExtension final AuditCapture audit = new AuditCapture();

  @LocalServerPort int port;
  @LocalManagementPort int managementPort;
  @Autowired ScriptedChatModel model;
  @Autowired InMemorySpanExporter spans;
  @Autowired SdkTracerProvider tracerProvider;
  @Autowired ObservationRegistry observations;

  @BeforeEach
  void resetModel() {
    model.reset();
    spans.reset();
  }

  static Stream<Arguments> tokensNotForAIsolate() {
    return Stream.of(
        arguments(named("no token", null)),
        // Every claim is right, only the signature is not the identity provider's.
        arguments(
            named("signed by an unknown key", UnknownKeyJwt.forged(Keycloak.usersIssuerUri()))),
        // A real token of a real user, issued to another Acme Financial Group client.
        arguments(
            named(
                "issued for another audience",
                Tokens.forUser(Keycloak.usersIssuerUri(), "other-app", "nora", "password"))));
  }

  @ParameterizedTest
  @MethodSource("tokensNotForAIsolate")
  void givenTokenNotForAIsolate_whenChat_then401AndNoModelCall(String token) {
    // Arrange
    String message = "{\"message\":\"hi\"}";

    // Act
    HttpResponse<String> response = chat(token, "acme-hr", message);

    // Assert
    assertThat(response.statusCode()).isEqualTo(401);
    assertThat(model.prompts()).isEmpty();
  }

  @Test
  void givenRoleOfTheSpaceOnly_whenChatAnAirlockWithRolesOfItsOwn_thenAdmitted() {
    // Arrange: eric holds a role of hr-acme's list, not short-app's own: a union, not an override
    model.answer("Hello.");

    // Act
    HttpResponse<String> response =
        chat(Tokens.forUser("eric", "password"), "short-app", "{\"message\":\"hi\"}");

    // Assert: past admission, the model was asked
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(model.prompts()).isNotEmpty();
  }

  @Test
  void givenRoleOfOneAirlockOnly_whenChatItAndTheOthers_thenAdmittedThereAlone() {
    // Arrange: ivy holds short-app's own role (intern), not hr-acme's
    String intern = Tokens.forUser("ivy", "password");
    model.answer("Hello.");

    // Act
    HttpResponse<String> own = chat(intern, "short-app", "{\"message\":\"hi\"}");
    HttpResponse<String> sibling = chat(intern, "acme-hr", "{\"message\":\"hi\"}");
    HttpResponse<String> otherSpace = chat(intern, "strict-app", "{\"message\":\"hi\"}");

    // Assert: the model was asked for the admitted one alone
    assertThat(own.statusCode()).isEqualTo(200);
    assertThat(sibling.statusCode()).isEqualTo(403);
    assertThat(otherSpace.statusCode()).isEqualTo(403);
    assertThat(audit.events())
        .extracting(line -> line.path("airlock").asString() + " " + line.path("outcome").asString())
        .containsExactly("short-app answered", "acme-hr refused", "strict-app refused");
  }

  @Test
  void givenAirlockAndSpaceListingNoRole_whenChat_then403() {
    // Arrange: closed-app and its space list no role: an empty union admits no one, not anyone
    String jasmine = Tokens.forUser("jasmine", "password");

    // Act
    HttpResponse<String> response = chat(jasmine, "closed-app", "{\"message\":\"hi\"}");

    // Assert
    assertThat(response.statusCode()).isEqualTo(403);
    assertThat(response.body()).contains("forbidden").doesNotContain("jasmine");
    assertThat(model.prompts()).isEmpty();
  }

  @Test
  void givenTokenWithoutListedRole_whenChatAsJson_then403() {
    // Arrange: ivy holds short-app's own role (intern); acme-hr and its space do not list it
    String intern = Tokens.forUser("ivy", "password");

    // Act
    HttpResponse<String> response =
        chat(intern, "acme-hr", "application/json", "{\"message\":\"hi\"}");

    // Assert: the same problem document as for the stream, and no model call
    assertThat(response.statusCode()).isEqualTo(403);
    assertThat(response.body()).contains("forbidden").doesNotContain("ivy");
    assertThat(model.prompts()).isEmpty();
  }

  @Test
  void
      givenTokenWithoutListedRole_whenChat_then403AndRefusalReasonRoleAuditLineAndRefusedAdmissionSpan() {
    // Arrange: ivy holds short-app's own role (intern); acme-hr and its space do not list it
    String intern = Tokens.forUser("ivy", "password");

    // Act
    HttpResponse<String> response =
        chat(intern, "acme-hr", "{\"message\":\"What does Eric earn?\"}");

    // Assert: who asked what, and why not; the 403 body itself says nothing of it
    assertThat(response.statusCode()).isEqualTo(403);
    assertThat(response.body()).contains("forbidden").doesNotContain("ivy");
    assertThat(audit.lines()).hasSize(1);
    JsonNode line = audit.events().getFirst();
    assertThat(line.path("event").asString()).isEqualTo("refusal");
    assertThat(line.path("outcome").asString()).isEqualTo("refused");
    assertThat(line.path("reason").asString()).isEqualTo("role");
    assertThat(line.path("user").asString()).isEqualTo("ivy@example.com");
    assertThat(line.path("client").asString()).isEqualTo("acme-chat");
    assertThat(line.path("question").asString()).isEqualTo("What does Eric earn?");
    assertThat(line.path("space").asString()).isEqualTo("hr-acme");
    assertThat(line.path("airlock").asString()).isEqualTo("acme-hr");
    assertThat(line.path("revision").asString()).isNotEmpty();
    assertThat(Instant.parse(line.path("ts").asString())).isBeforeOrEqualTo(Instant.now());
    assertThat(line.path("answer").isNull()).isTrue();
    assertThat(model.prompts()).isEmpty();
    // The refusal's trace is the one its audit line names
    String traceId = line.path("trace_id").asString();
    assertThat(traceId).matches("[0-9a-f]{32}");
    tracerProvider.forceFlush().join(10, TimeUnit.SECONDS);
    List<SpanData> trace =
        spans.getFinishedSpanItems().stream()
            .filter(span -> span.getTraceId().equals(traceId))
            .toList();
    SpanData root = spanNamed(trace, "exchange hr-acme/acme-hr");
    SpanData admission = spanNamed(trace, "admission");
    assertThat(admission.getParentSpanId()).isEqualTo(root.getSpanId());
    assertThat(attribute(admission, "aisolate.admitted")).isEqualTo("false");
    assertThat(attribute(admission, "aisolate.reason")).isEqualTo("role");
    assertThat(attribute(root, "aisolate.outcome")).isEqualTo("refused");
    // Failed by type alone: the refusal's message names her
    assertThat(trace).noneMatch(span -> exposed(span).contains("ivy"));
    assertThat(admission.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
    assertThat(attribute(admission, "error.type")).endsWith("ForbiddenException");
  }

  @Test
  void givenToolErrorQuotingTheModelsValue_whenChat_thenNoSpanCarriesTheValueTheUserOrQuestion() {
    // Arrange: the model sends a name where a number goes, and the tool server quotes it back
    model.toolCall("read_records", "{\"salary\":\"Lindholm\"}").answer("I don't know.");

    // Act: as nora, whose ceo and founder roles become authorities Spring Security would name
    chat(
        Tokens.forUser("nora", "password"),
        "acme-hr",
        "{\"message\":\"What does Lindholm earn?\"}");

    // Assert: the tool span failed, by type alone
    String traceId = audit.events().getFirst().path("trace_id").asString();
    tracerProvider.forceFlush().join(10, TimeUnit.SECONDS);
    List<SpanData> trace =
        spans.getFinishedSpanItems().stream()
            .filter(span -> span.getTraceId().equals(traceId))
            .toList();
    assertThat(trace)
        .noneMatch(span -> exposed(span).contains("Lindholm") || exposed(span).contains("nora@"));
    // Nor any span of the request: Spring Security's would name her roles
    assertThat(spans.getFinishedSpanItems())
        .noneMatch(span -> exposed(span).contains("ROLE_"))
        .extracting(SpanData::getName)
        .noneMatch(
            name ->
                name.startsWith("security filterchain")
                    || name.startsWith("authorize ")
                    || name.startsWith("authenticate "));
    SpanData tool = spanNamed(trace, "tool read_records");
    assertThat(tool.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
    assertThat(attribute(tool, "error.type")).endsWith("ToolCallFailedException");
  }

  private static final String CALLERS_TRACE = "4bf92f3577b34da6a3ce929d0e0e4736";

  /** A caller's context in each format the pod could read, each also asking not to sample. */
  static Stream<Arguments> callersTraceContexts() {
    return Stream.of(
        arguments(
            named(
                "W3C traceparent",
                Map.of("traceparent", "00-" + CALLERS_TRACE + "-00f067aa0ba902b7-00"))),
        arguments(named("B3 single", Map.of("b3", CALLERS_TRACE + "-00f067aa0ba902b7-0"))),
        arguments(
            named(
                "B3 multi",
                Map.of(
                    "X-B3-TraceId", CALLERS_TRACE,
                    "X-B3-SpanId", "00f067aa0ba902b7",
                    "X-B3-Sampled", "0"))));
  }

  @ParameterizedTest
  @MethodSource("callersTraceContexts")
  void givenCallersTraceContext_whenChat_thenTheExchangeStartsATraceOfItsOwn(
      Map<String, String> context) {
    // Arrange
    model.toolCall("read_records", "{}").answerCiting("{f1}", "ev-1", "r1", "gross_salary");
    String callers = CALLERS_TRACE;
    HttpRequest.Builder request =
        HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + "/v1/spaces/hr-acme/airlocks/acme-hr/chat"))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{\"message\":\"hi\"}"));
    context.forEach(request::header);

    // Act
    HttpResponse<String> response =
        Http.send(Http.bearer(request, Tokens.forUser("eric", "password")).build());

    // Assert: its own trace keys the audit line and the answer, and reaches the trace store
    assertThat(response.statusCode()).isEqualTo(200);
    String traceId = audit.events().getFirst().path("trace_id").asString();
    assertThat(traceId).matches("[0-9a-f]{32}").isNotEqualTo(callers);
    assertThat(JSON.readTree(response.body()).path("trace_id").asString()).isEqualTo(traceId);
    tracerProvider.forceFlush().join(10, TimeUnit.SECONDS);
    assertThat(spans.getFinishedSpanItems())
        .noneMatch(span -> span.getTraceId().equals(callers))
        .anyMatch(
            span ->
                span.getTraceId().equals(traceId)
                    && span.getName().equals("exchange hr-acme/acme-hr"));
  }

  @Test
  void givenStartedPod_whenSpringAiWouldWarnWithAToolName_thenNothingIsLogged() {
    // Arrange: the name is the model's, so a newline in it could start a line that reads as audit
    Logger toolCalling =
        LoggerFactory.getLogger("org.springframework.ai.model.tool.DefaultToolCallingManager");

    // Act
    boolean warns = toolCalling.isWarnEnabled();

    // Assert
    assertThat(warns).isFalse();
  }

  /** What a span carries out of the pod: its attributes, its events with theirs, its status. */
  private static String exposed(SpanData span) {
    String events =
        span.getEvents().stream()
            .map(event -> event.getName() + " " + event.getAttributes())
            .collect(Collectors.joining(" "));
    // Timings are left out: an epoch timestamp can contain any digits.
    return span.getAttributes() + " " + events + " " + span.getStatus().getDescription();
  }

  @Test
  void givenAnsweredAndRefusedRequests_whenScraped_thenTheDashboardsSeriesByAirlock() {
    // Arrange: the model observed through the pod's registry, as a provider's client is
    model
        .observedBy(observations)
        .reportingTokens(100, 20)
        .toolCall("read_records", "{}")
        .answerCiting("{f1}", "ev-1", "r1", "gross_salary");
    chat(Tokens.forUser("eric", "password"), "acme-hr", "{\"message\":\"my salary?\"}");
    chat(Tokens.forUser("ivy", "password"), "acme-hr", "{\"message\":\"hi\"}");
    // A tool name the model invents, as a user may ask it to
    model.toolCall("x_invented_by_the_user", "{}").answer("I don't know.");
    chat(Tokens.forUser("eric", "password"), "acme-hr", "{\"message\":\"call x\"}");
    HttpRequest scrape =
        HttpRequest.newBuilder(
                URI.create("http://localhost:" + managementPort + "/actuator/prometheus"))
            .build();

    // Act: without a token, on the management port, as a scraper reads it
    HttpResponse<String> metrics = Http.send(scrape);

    // Assert: the series the example dashboard queries; the trace's observations make none
    assertThat(metrics.statusCode()).isEqualTo(200);
    String airlock = "airlock=\"acme-hr\",";
    assertThat(metrics.body())
        .contains("aisolate_exchanges_total{" + airlock + "outcome=\"answered\"}")
        .contains("aisolate_exchanges_total{" + airlock + "outcome=\"refused\"}")
        .contains("aisolate_refusals_total{" + airlock + "reason=\"role\"}")
        .contains("aisolate_tool_calls_total{" + airlock + "tool=\"read_records\"}")
        .contains("aisolate_exchange_duration_seconds_bucket{" + airlock + "le=\"")
        .contains("gen_ai_client_token_usage_total{" + airlock)
        .contains("spring_ai_tool_definition_name=\"unknown\"")
        .doesNotContain("x_invented_by_the_user")
        .doesNotContain("aisolate_trace");
  }

  private static SpanData spanNamed(List<SpanData> trace, String name) {
    return trace.stream()
        .filter(span -> name.equals(span.getName()))
        .findFirst()
        .orElseThrow(
            () ->
                new AssertionError(
                    name + " is not among " + trace.stream().map(SpanData::getName).toList()));
  }

  private static String attribute(SpanData span, String key) {
    return span.getAttributes().get(AttributeKey.stringKey(key));
  }

  @Test
  void givenCallingClientNotAllowed_whenChat_then403AndRefusalAuditLineNamesTheClient() {
    // Arrange: nora is on strict-app's list, the client she came through is not
    String partner = Tokens.forUser(Keycloak.usersIssuerUri(), "partner-app", "nora", "password");

    // Act
    HttpResponse<String> response = chat(partner, "strict-app", "{\"message\":\"hi\"}");

    // Assert
    assertThat(response.statusCode()).isEqualTo(403);
    assertThat(response.body()).doesNotContain("calling application").contains("forbidden");
    assertThat(model.prompts()).isEmpty();
    JsonNode line = audit.events().getFirst();
    assertThat(audit.lines()).hasSize(1);
    assertThat(line.path("reason").asString()).isEqualTo("client");
    assertThat(line.path("user").asString()).isEqualTo("nora@example.com");
    assertThat(line.path("client").asString()).isEqualTo("partner-app");
  }

  /** Stream or document, the airlock writes the same record, once. */
  @ParameterizedTest
  @ValueSource(strings = {"application/json", "text/event-stream"})
  void
      givenAllowedEmployeeThroughAnyClient_whenChat_thenOneExchangeAuditLineWithTheTokensUserAndClient(
          String accept) {
    // Arrange: a client acme-hr lists only through "*", to see the token's azp, not a constant
    String partner = Tokens.forUser(Keycloak.usersIssuerUri(), "partner-app", "nora", "password");
    model.toolCall("read_records", "{}").answerCiting("{f1}", "ev-1", "r1", "gross_salary");

    // Act
    HttpResponse<String> response =
        chat(partner, "acme-hr", accept, "{\"message\":\"my salary?\"}");

    // Assert
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(audit.lines()).hasSize(1);
    JsonNode line = audit.events().getFirst();
    assertThat(line.path("event").asString()).isEqualTo("exchange");
    assertThat(line.path("outcome").asString()).isEqualTo("answered");
    assertThat(line.path("user").asString()).isEqualTo("nora@example.com");
    assertThat(line.path("client").asString()).isEqualTo("partner-app");
    assertThat(line.path("tools").get(0).path("name").asString()).isEqualTo("read_records");
  }

  @ParameterizedTest
  @CsvSource({"hr-acme, no-such-app", "hr-acme, Acme-HR", "strict-space, acme-hr"})
  void givenUnknownAirlockInTheSpace_whenChat_then404AndNoModelCall(String space, String airlock) {
    // Arrange
    String eric = Tokens.forUser("eric", "password");

    // Act
    HttpResponse<String> response =
        chat(space, eric, airlock, "text/event-stream", "{\"message\":\"hi\"}");

    // Assert: an airlock is named exactly, never in another case, and in its own space
    assertThat(response.statusCode()).isEqualTo(404);
    assertThat(model.prompts()).isEmpty();
  }

  static Stream<Arguments> messagesTheEndpointRefuses() {
    return Stream.of(
        arguments(named("blank", "  ")),
        arguments(named("longer than the bound", "x".repeat(4001))));
  }

  @ParameterizedTest
  @MethodSource("messagesTheEndpointRefuses")
  void givenInvalidMessage_whenChat_then400AndNoModelCall(String message) {
    // Arrange
    String body = JSON.writeValueAsString(Map.of("message", message));

    // Act
    HttpResponse<String> response = chat(Tokens.forUser("eric", "password"), "acme-hr", body);

    // Assert
    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(model.prompts()).isEmpty();
  }

  @ParameterizedTest
  @NullSource
  // The third is the default of axios and n8n: it names JSON but also accepts anything
  @ValueSource(strings = "application/json, text/plain, */*")
  void givenNoPreferenceInAccept_whenChat_thenSseAnswer(String accept) {
    // Arrange
    model
        .toolCall("read_records", "{}")
        .answerCiting("Your salary is {f1}.", "ev-1", "r1", "gross_salary");

    // Act
    HttpResponse<String> response =
        chat(
            Tokens.forUser("eric", "password"),
            "acme-hr",
            accept,
            "{\"message\":\"What is my salary?\"}");

    // Assert: the stream stays the default, JSON is only for a caller that asks
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.headers().firstValue("Content-Type"))
        .hasValueSatisfying(ct -> assertThat(ct).startsWith("text/event-stream"));
    assertThat(response.body()).contains("\"type\":\"done\"");
  }

  static Stream<Arguments>
      givenFailureDuringExchange_whenChatAsJson_thenProblemDocumentWithoutTheCause() {
    return Stream.of(
        arguments(
            named(
                "a tool call that blows up with something that is no tool error",
                (Consumer<ScriptedChatModel>) m -> m.toolCall("read_records", "{\"boom\":true}")),
            500,
            "The assistant could not complete the request",
            "unreachable"),
        arguments(
            named(
                "the model server down or busy, as the provider client fails then",
                (Consumer<ScriptedChatModel>)
                    m ->
                        m.onCall(
                            () -> {
                              throw new ResourceAccessException(
                                  "Connection refused: localhost/127.0.0.1:11434");
                            })),
            503,
            "The model is not available right now",
            "11434"));
  }

  @ParameterizedTest
  @MethodSource
  void givenFailureDuringExchange_whenChatAsJson_thenProblemDocumentWithoutTheCause(
      Consumer<ScriptedChatModel> failure, int status, String sentence, String cause) {
    // Arrange
    failure.accept(model);

    // Act
    HttpResponse<String> response =
        chat(
            Tokens.forUser("eric", "password"),
            "acme-hr",
            "application/json",
            "{\"message\":\"salary?\"}");

    // Assert: no stream to put an error event on, so a status; the cause stays in the log
    assertThat(response.statusCode()).isEqualTo(status);
    assertThat(response.headers().firstValue("Content-Type"))
        .hasValueSatisfying(ct -> assertThat(ct).startsWith("application/").contains("json"));
    assertThat(response.body()).contains(sentence).doesNotContain(cause);
  }

  @Test
  void givenUnexpectedFailureDuringExchange_whenChat_thenGenericErrorEvent() {
    // Arrange: the tool call blows up with something that is not a tool error
    model.toolCall("read_records", "{\"boom\":true}");

    // Act
    HttpResponse<String> response =
        chat(Tokens.forUser("eric", "password"), "acme-hr", "{\"message\":\"salary?\"}");

    // Assert
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).contains("\"type\":\"error\"").doesNotContain("unreachable");
  }

  @Test
  void givenNoToken_whenAnyEndpoint_then401PointsAtTheDiscoveryDocument() {
    // Arrange
    String noToken = null;

    // Act
    HttpResponse<String> response = chat(noToken, "acme-hr", "{\"message\":\"hi\"}");

    // Assert: a generic client learns where to authenticate from the challenge itself
    assertThat(response.headers().firstValue("WWW-Authenticate"))
        .hasValueSatisfying(
            h ->
                assertThat(h)
                    .startsWith("Bearer")
                    .contains(
                        "resource_metadata=\"http://localhost:8080/.well-known/oauth-protected-resource\""));
  }

  @Test
  void givenAnonymousClient_whenProtectedResourceMetadata_thenNamesTheCustomerIdp()
      throws Exception {
    // Arrange
    HttpRequest request =
        HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + "/.well-known/oauth-protected-resource"))
            .GET()
            .build();

    // Act
    HttpResponse<String> response = Http.send(request);

    // Assert
    assertThat(response.statusCode()).isEqualTo(200);
    JsonNode doc = JSON.readTree(response.body());
    assertThat(doc.path("resource").asString()).isEqualTo("http://localhost:8080");
    assertThat(doc.path("authorization_servers").get(0).asString())
        .isEqualTo(Keycloak.usersIssuerUri());
    assertThat(doc.path("scopes_supported").valueStream().map(n -> n.asString()))
        .contains("aisolate");
  }

  @Test
  void givenPreflightFromTheCustomerChat_whenOptions_thenAllowedWithoutToken() {
    // Arrange
    HttpRequest request =
        HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + "/v1/spaces/hr-acme/airlocks/acme-hr/chat"))
            .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
            .header("Origin", "http://localhost:3000")
            .header("Access-Control-Request-Method", "POST")
            .header("Access-Control-Request-Headers", "authorization,content-type")
            .build();

    // Act
    HttpResponse<String> response = Http.send(request);

    // Assert
    assertThat(response.statusCode()).isIn(200, 204);
    assertThat(response.headers().firstValue("Access-Control-Allow-Origin"))
        .hasValue("http://localhost:3000");
  }

  private HttpResponse<String> chat(String bearer, String airlockId, String body) {
    return chat(bearer, airlockId, "text/event-stream", body);
  }

  /** The fixtures' spaces: strict-space holds these two airlocks, hr-acme the others. */
  private static final Map<String, String> SPACES =
      Map.of("strict-app", "strict-space", "closed-app", "strict-space");

  private HttpResponse<String> chat(String bearer, String airlockId, String accept, String body) {
    return chat(SPACES.getOrDefault(airlockId, "hr-acme"), bearer, airlockId, accept, body);
  }

  /** A null {@code accept} sends none, which is what a plain client does. */
  private HttpResponse<String> chat(
      String space, String bearer, String airlockId, String accept, String body) {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(
                URI.create(
                    "http://localhost:"
                        + port
                        + "/v1/spaces/"
                        + space
                        + "/airlocks/"
                        + airlockId
                        + "/chat"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body));
    if (accept != null) {
      request.header("Accept", accept);
    }
    return Http.send(Http.bearer(request, bearer).build());
  }
}
