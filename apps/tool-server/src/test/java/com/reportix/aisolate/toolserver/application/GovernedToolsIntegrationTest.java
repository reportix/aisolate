package com.reportix.aisolate.toolserver.application;

import static com.reportix.aisolate.toolserver.support.ToolServerTestConfig.REVISION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Named.named;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import com.reportix.aisolate.identity.testing.Keycloak;
import com.reportix.aisolate.identity.testing.Tokens;
import com.reportix.aisolate.toolserver.ToolServerApplication;
import com.reportix.aisolate.toolserver.support.McpTestClient;
import com.reportix.aisolate.toolserver.support.ToolServerTestConfig;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;
import tools.jackson.databind.JsonNode;

/**
 * The MCP server called directly with real Keycloak tokens, exactly as an airlock pod (or an
 * attacker who reached the network) would call it, against the real customer database. The policies
 * come from a mounted documents directory with the revision the chart computed, the rules from the
 * rule files on the test classpath, as a pinned jar carries them; there is no AIsolate database
 * anywhere in this test. Rules must hold regardless of the caller. The exact tool-list assertions
 * hold because every jar is loaded at start: the pod's shape.
 *
 * <p>The documents: space hr-acme lists nora; hr-assistant adds eric, admits any client, and reads
 * and aggregates; founders-finance adds no one, admits the Acme chat alone and declares {@code
 * read_records} and {@code export_payslips}; hr-archive reads the data source nothing answers.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = ToolServerApplication.class)
@ContextConfiguration(initializers = ToolServerTestConfig.class)
@Import(GovernedToolsIntegrationTest.Stubs.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GovernedToolsIntegrationTest {

  @TestConfiguration
  static class Stubs {
    /** The spans the pod would send to the OTLP endpoint. */
    @Bean
    InMemorySpanExporter spans() {
      return InMemorySpanExporter.create();
    }
  }

  /** Of the five seeded employees, Nora is the CEO (id 1) and Eric an engineer (id 2). */
  @LocalServerPort int port;

  @Autowired InMemorySpanExporter spans;
  @Autowired SdkTracerProvider tracerProvider;
  @Autowired MeterRegistry meters;

  private McpTestClient mcp;
  private String eric;
  private String nora;

  @BeforeAll
  void tokens() {
    mcp = new McpTestClient(port);
    eric = Tokens.forUser("eric", "password");
    nora = Tokens.forUser("nora", "password");
  }

  @Test
  void givenValidToken_whenListTools_thenExactlyThePublishedTools() {
    // Arrange
    String validToken = eric;

    // Act
    HttpResponse<String> response = mcp.listTools(validToken);

    // Assert
    assertThat(response.statusCode()).isEqualTo(200);
    JsonNode tools = mcp.parseBody(response.body()).path("result").path("tools");
    assertThat(names(tools))
        .containsExactlyInAnyOrder(
            "read_records", "aggregate_records", "export_payslips", "read_archive");
  }

  Stream<Arguments> toolQueries() {
    Map<String, Object> served = pin("hr-assistant");
    Map<String, Object> unserved = pin("no-such-app");
    // Past the tool's schema, refused by the governed query: the IBAN is masked for him
    Map<String, Object> maskedColumn =
        Map.of("filters", List.of(Map.of("column", "iban", "op", "ilike", "value", "DE00%")));
    return Stream.of(
        arguments(named("his own row", Map.of()), served, "hr-assistant", "rows"),
        arguments(named("an airlock nobody serves", Map.of()), unserved, "none", "denied"),
        arguments(
            named("a filter on a masked column", maskedColumn), served, "hr-assistant", "invalid"));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("toolQueries")
  void givenQuery_whenAnswered_thenToolQueriesCounterOutcomeRows(
      Map<String, Object> args, Map<String, Object> meta, String airlock, String outcome) {
    // Arrange: labels from the served airlock, never from what the caller named
    Counter queries =
        meters.counter(
            "aisolate.tool.queries",
            "airlock",
            airlock,
            "tool",
            "read_records",
            "outcome",
            outcome);
    double before = queries.count();

    // Act
    mcp.callTool(eric, "read_records", args, meta);

    // Assert
    assertThat(queries.count()).isEqualTo(before + 1);
  }

  @Test
  void givenTraceparent_whenQuery_thenTheServerSpanIsItsChild() {
    // Arrange: the airlock's tool span, as its client sends it
    String traceId = "4bf92f3577b34da6a3ce929d0e0e4736";
    String toolSpanId = "00f067aa0ba902b7";
    Map<String, String> traceparent =
        Map.of("traceparent", "00-" + traceId + "-" + toolSpanId + "-01");
    Map<String, Object> meta = pin("hr-assistant");

    // Act
    HttpResponse<String> response = mcp.callTool(eric, "read_records", Map.of(), meta, traceparent);

    // Assert: the server span continues the airlock's trace; the query and its SQL run under it
    assertThat(response.statusCode()).isEqualTo(200);
    tracerProvider.forceFlush().join(10, TimeUnit.SECONDS);
    Map<String, SpanData> trace =
        spans.getFinishedSpanItems().stream()
            .filter(span -> span.getTraceId().equals(traceId))
            .collect(Collectors.toMap(SpanData::getSpanId, Function.identity()));
    SpanData server =
        trace.values().stream()
            .filter(span -> span.getKind() == SpanKind.SERVER)
            .findFirst()
            .orElseThrow();
    assertThat(server.getParentSpanId()).isEqualTo(toolSpanId);
    SpanData query = spanNamed(trace, "governed query read_records");
    assertThat(ancestors(query, trace)).contains(server.getSpanId());
    assertThat(attribute(query, "aisolate.airlock")).isEqualTo("hr-assistant");
    assertThat(attribute(query, "aisolate.revision")).isEqualTo(REVISION);
    assertThat(attribute(query, "aisolate.data_source")).isEqualTo("acme_hr");
    assertThat(attribute(query, "aisolate.entity")).isEqualTo("hr.compensation");
    assertThat(attribute(query, "aisolate.rows")).isEqualTo("1");
    assertThat(attribute(query, "aisolate.outcome")).isEqualTo("rows");
    SpanData sql = spanNamed(trace, "sql acme_hr");
    assertThat(sql.getParentSpanId()).isEqualTo(query.getSpanId());
    assertThat(attribute(sql, "aisolate.airlock")).isEqualTo("hr-assistant");
    assertThat(attribute(sql, "aisolate.revision")).isEqualTo(REVISION);
    // Names and counts only: never a statement, a row or who asked
    assertThat(trace.values())
        .allSatisfy(
            span ->
                assertThat(exposed(span))
                    .doesNotContainIgnoringCase("select")
                    .doesNotContainIgnoringCase("join")
                    .doesNotContain("hr.employees")
                    .doesNotContain("Lindholm")
                    .doesNotContain("78000")
                    .doesNotContain("eric@"));
  }

  /** Everything a span carries to the collector besides its name. */
  private static String exposed(SpanData span) {
    String events =
        span.getEvents().stream()
            .map(event -> event.getName() + " " + event.getAttributes())
            .collect(Collectors.joining(" "));
    // Timings are left out: an epoch timestamp can contain any digits.
    return span.getAttributes() + " " + events + " " + span.getStatus().getDescription();
  }

  private static SpanData spanNamed(Map<String, SpanData> trace, String name) {
    return trace.values().stream()
        .filter(span -> name.equals(span.getName()))
        .findFirst()
        .orElseThrow(
            () ->
                new AssertionError(
                    name
                        + " is not among "
                        + trace.values().stream().map(SpanData::getName).toList()));
  }

  /** The span ids above {@code span} in its trace, nearest first. */
  private static List<String> ancestors(SpanData span, Map<String, SpanData> trace) {
    List<String> ids = new ArrayList<>();
    for (SpanData parent = trace.get(span.getParentSpanId());
        parent != null;
        parent = trace.get(parent.getParentSpanId())) {
      ids.add(parent.getSpanId());
    }
    return ids;
  }

  private static String attribute(SpanData span, String key) {
    return span.getAttributes().get(AttributeKey.stringKey(key));
  }

  static Stream<Arguments> givenMetaThatPinsNoServedAirlock_whenReadRecords_thenZeroRows() {
    return Stream.of(
        arguments(named("no revision", Map.of("airlock", "hr-assistant"))),
        // An airlock given as a number, not its id
        arguments(named("an airlock number", Map.of("airlock", 1, "revision", REVISION))),
        arguments(named("an airlock nobody serves", pin("no-such-app"))),
        // A pod of another release, or a stale one, does not agree on the revision
        arguments(
            named(
                "another revision",
                Map.of("airlock", "hr-assistant", "revision", "0123456789ab"))));
  }

  @ParameterizedTest
  @MethodSource
  void givenMetaThatPinsNoServedAirlock_whenReadRecords_thenZeroRows(Map<String, Object> meta) {
    // Arrange: the policy is pinned to both or not fetched at all

    // Act
    JsonNode evidence = mcp.resultJson(mcp.callTool(nora, "read_records", Map.of(), meta));

    // Assert: a silent deny, exactly like an airlock AIsolate never served
    assertThat(evidence.path("row_count").asInt()).isZero();
    assertThat(evidence.path("rules")).isEmpty();
  }

  Stream<Arguments> foundersFinanceCalls() {
    // The rules would give every caller here rows: only the gates decide
    Map<String, Object> count = Map.of("function", "count", "column", "gross_salary");
    String partner = Tokens.forUser(Keycloak.usersIssuerUri(), "partner-app", "nora", "password");
    return Stream.of(
        // nora is the space's user: every airlock of it; as CEO she reads all five rows
        arguments(
            named("the space's user through the Acme chat", nora), "read_records", Map.of(), 5),
        // eric is hr-assistant's own user: nothing on its sibling, though his own-record rule would
        // give him his row
        arguments(named("a sibling airlock's own user", eric), "read_records", Map.of(), 0),
        // naming the airlock in _meta is not enough, the airlock must admit the client
        arguments(
            named("the space's user through a client it does not admit", partner),
            "read_records",
            Map.of(),
            0),
        // gate 3 holds at the data boundary too, whatever the caller names in _meta
        arguments(named("a tool it does not declare", nora), "aggregate_records", count, 0));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("foundersFinanceCalls")
  void givenCallerAndTool_whenCalledOnFoundersFinance_thenRowsOnlyWhereEveryGateAdmits(
      String token, String tool, Map<String, Object> args, int rows) {
    // Arrange: founders-finance lists no user of its own, admits the Acme chat alone and declares
    // read_records but not aggregate_records
    Map<String, Object> meta = pin("founders-finance");

    // Act
    JsonNode evidence = mcp.resultJson(mcp.callTool(token, tool, args, meta));

    // Assert: a refusal is zero rows and no rule, a silent deny
    assertThat(evidence.path("row_count").asInt()).isEqualTo(rows);
    if (rows == 0) {
      assertThat(evidence.path("rules")).isEmpty();
    }
  }

  @Test
  void givenCallerTheAirlockDoesNotAdmit_whenCalled_thenTurnedAwayBeforeAnyDatabaseWork() {
    // Arrange: eric is no user of hr-archive, whose identity join and query fail at its database
    Map<String, Object> meta = pin("hr-archive");

    // Act
    HttpResponse<String> response = mcp.callTool(eric, "read_archive", Map.of(), meta);

    // Assert: zero rows, not the database's error: admission ran before the identity join
    assertThat(mcp.parseBody(response.body()).path("result").path("isError").asBoolean()).isFalse();
    assertThat(mcp.resultJson(response).path("row_count").asInt()).isZero();
  }

  @Test
  void givenArgumentsThatSmuggleAnAirlockId_whenReadRecords_thenTheyDoNotSelectAnotherAirlock() {
    // Arrange: founders-finance turns Eric away; the arguments name it, _meta names hr-assistant
    Map<String, Object> smuggled =
        Map.of("airlock", "founders-finance", "_meta", Map.of("airlock", "founders-finance"));

    // Act
    JsonNode evidence = mcp.resultJson(mcp.callTool(eric, "read_records", smuggled));

    // Assert: arguments are arguments; only _meta selects the airlock
    assertThat(strings(evidence.path("rules"))).containsExactly("own-record");
    assertThat(evidence.path("row_count").asInt()).isEqualTo(1);
  }

  static Stream<Arguments> givenDatabaseError_whenCalled_thenFixedToolErrorWithoutSql() {
    return Stream.of(
        // Refused at the identity join, before any statement of the tool exists
        arguments(
            named("a connection refused", "read_archive"),
            Map.of(),
            "hr-archive",
            "The data source could not be reached",
            "unreachable",
            "hr_archive"),
        // Refused by the database, which the driver's exception quotes with the statement
        arguments(
            named("a statement the database refuses", "read_records"),
            Map.of(
                "filters",
                List.of(Map.of("column", "full_name", "op", "ilike", "value", "Lind\u0000holm"))),
            "hr-assistant",
            "The tool could not complete the request",
            "failed",
            "acme_hr"));
  }

  @ParameterizedTest
  @MethodSource
  void givenDatabaseError_whenCalled_thenFixedToolErrorWithoutSql(
      String tool,
      Map<String, Object> args,
      String airlock,
      String text,
      String error,
      String dataSource) {
    // Arrange: Nora passes every gate and the rules; a trace of its own to find the spans by
    String traceId = UUID.randomUUID().toString().replace("-", "");
    Map<String, String> traceparent =
        Map.of("traceparent", "00-" + traceId + "-00f067aa0ba902b7-01");

    // Act
    HttpResponse<String> response = mcp.callTool(nora, tool, args, pin(airlock), traceparent);

    // Assert: the model learns nothing of the statement, the driver or the data source
    JsonNode result = mcp.parseBody(response.body()).path("result");
    assertThat(result.path("isError").asBoolean()).isTrue();
    assertThat(result.path("content").get(0).path("text").asString()).isEqualTo(text);
    // The airlock learns which kind of failure, and on which data source, to tell the user
    assertThat(result.path("_meta").path("error").asString()).isEqualTo(error);
    assertThat(result.path("_meta").path("data_source").asString()).isEqualTo(dataSource);
    assertThat(response.body())
        .doesNotContainIgnoringCase("select")
        .doesNotContain("jdbc:")
        .doesNotContain(ToolServerTestConfig.UNREACHABLE_URL)
        .doesNotContain("localhost:1");
    // Nor do the spans: the query's failed by type alone
    tracerProvider.forceFlush().join(10, TimeUnit.SECONDS);
    List<SpanData> trace =
        spans.getFinishedSpanItems().stream()
            .filter(span -> span.getTraceId().equals(traceId))
            .toList();
    SpanData query =
        trace.stream()
            .filter(span -> span.getName().equals("governed query " + tool))
            .findFirst()
            .orElseThrow();
    assertThat(query.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
    assertThat(attribute(query, "error.type")).isNotBlank();
    assertThat(trace)
        .allSatisfy(
            span ->
                assertThat(exposed(span))
                    .doesNotContainIgnoringCase("select")
                    .doesNotContain("localhost:1")
                    .doesNotContain("Lind")
                    .doesNotContain("nora@"));
  }

  static Stream<Arguments>
      givenEntityUngovernedOrOfAnotherPlugin_whenRead_thenZeroRowsAndNoRules() {
    return Stream.of(
        // export_payslips reads hr.payroll, which no rule set governs
        arguments(named("an entity without a rule set", Map.of()), "hr.payroll"),
        // hr.compensation is hr-acme-tools', whose rules would show Nora every row
        arguments(
            named("an entity of another plugin", Map.of("entity", "hr.compensation")),
            "hr.compensation"));
  }

  @ParameterizedTest
  @MethodSource
  void givenEntityUngovernedOrOfAnotherPlugin_whenRead_thenZeroRowsAndNoRules(
      Map<String, Object> args, String entity) {
    // Arrange: founders-finance declares export_payslips, of payroll-tools
    Map<String, Object> meta = pin("founders-finance");

    // Act
    JsonNode evidence = mcp.resultJson(mcp.callTool(nora, "export_payslips", args, meta));

    // Assert: denied inside the tool, past every gate, and not a tool error
    assertThat(evidence.path("entity").asString()).isEqualTo(entity);
    assertThat(evidence.path("row_count").asInt()).isZero();
    assertThat(evidence.path("rules")).isEmpty();
  }

  /** The _meta an airlock pod of this release sends for the airlock. */
  private static Map<String, Object> pin(String airlock) {
    return Map.of("airlock", airlock, "revision", REVISION);
  }

  private static List<String> names(JsonNode tools) {
    return StreamSupport.stream(tools.spliterator(), false)
        .map(t -> t.path("name").asString())
        .toList();
  }

  private static List<String> strings(JsonNode array) {
    return StreamSupport.stream(array.spliterator(), false).map(JsonNode::asString).toList();
  }
}
