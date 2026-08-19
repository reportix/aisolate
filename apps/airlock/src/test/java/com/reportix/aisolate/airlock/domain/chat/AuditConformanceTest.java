package com.reportix.aisolate.airlock.domain.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Named.named;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import com.reportix.aisolate.airlock.domain.airlock.AirlockDefinition;
import com.reportix.aisolate.airlock.domain.chat.audit.AuditEvent;
import com.reportix.aisolate.airlock.domain.chat.audit.AuditLog;
import com.reportix.aisolate.airlock.support.AuditCapture;
import com.reportix.aisolate.config.AirlockDocument;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code conformance/cases/audit/}: the audit line as the airlock writes it, shared with the Python
 * airlock. In this package because {@link ExchangeAudit} and {@link ChatExchange#rowsHash} are the
 * chat package's own.
 */
class AuditConformanceTest {

  /** As the pod's own mapper writes the line: nothing configured. */
  private static final JsonMapper JSON = JsonMapper.builder().build();

  /** As the airlock reads a tool result: exact decimals. */
  private static final JsonMapper EXACT =
      JsonMapper.builder().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();

  @RegisterExtension final AuditCapture audit = new AuditCapture();

  @ParameterizedTest
  @ValueSource(strings = {"exchange.json", "refusal.json", "error.json", "tool-error.json"})
  void givenExampleLine_whenItsEventIsRecorded_thenTheSameLineInSchemaOrder(String example)
      throws Exception {
    // Arrange
    String expected = Files.readString(path(example), StandardCharsets.UTF_8).strip();
    AuditEvent event = JSON.readValue(expected, AuditEvent.class);

    // Act
    new AuditLog(JSON, new SimpleMeterRegistry()).record(event);

    // Assert
    assertThat(audit.lines()).containsExactly(expected);
    JsonNode schema = EXACT.readTree(path("audit-event.schema.json").toFile());
    List<String> required = new ArrayList<>();
    schema.path("required").forEach(name -> required.add(name.asString()));
    List<String> keys = new ArrayList<>(audit.events().getFirst().propertyNames());
    assertThat(keys).isEqualTo(new ArrayList<>(schema.path("properties").propertyNames()));
    assertThat(keys).isEqualTo(required);
  }

  static Stream<Arguments> outcomes() {
    return cases("outcome.json");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("outcomes")
  void givenExchangeEndingWithAnswerOrFailure_whenItsLineIsBuilt_thenOutcomeReasonAndAnswer(
      JsonNode outcome) {
    // Arrange
    ExchangeAudit exchange = new ExchangeAudit(airlock(), "eric@example.com", "acme-chat", "q");
    if (!outcome.path("answer").isNull()) {
      exchange.accept(new ChatEvent.AnswerChunk(outcome.path("answer").asString()));
    }
    switch (outcome.path("failure").isNull() ? "" : outcome.path("failure").asString()) {
      case "" -> {}
      case "llm_unavailable" -> exchange.accept(ChatEvent.Error.MODEL_UNAVAILABLE);
      case "no_model_endpoint" -> exchange.accept(ChatEvent.Error.NO_MODEL_ENDPOINT);
      // As the agent loop ends a spent budget: the reason, then the stream's error event
      case String reason -> {
        exchange.failedWith(reason);
        exchange.accept(ChatEvent.Error.COULD_NOT_COMPLETE);
      }
    }

    // Act
    AuditEvent line = exchange.toEvent(Instant.now(), 0, null);

    // Assert
    JsonNode expect = outcome.path("expect");
    assertThat(line.outcome().json()).isEqualTo(expect.path("outcome").asString());
    assertThat(line.reason()).isEqualTo(text(expect.path("reason")));
    assertThat(line.answer()).isEqualTo(text(expect.path("answer_on_the_line")));
  }

  static Stream<Arguments> rowsHashes() {
    return cases("rows-hash.json");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("rowsHashes")
  void givenToolResultRows_whenHashed_thenTheCanonicalTextsHash(JsonNode rows) throws Exception {
    // Arrange
    List<Map<String, Object>> parsed =
        EXACT.treeToValue(rows.path("rows"), new TypeReference<List<Map<String, Object>>>() {});

    // Act
    String hash = ChatExchange.rowsHash(parsed);

    // Assert
    assertThat(hash).isEqualTo(rows.path("rows_hash").asString());
    assertThat(sha256(rows.path("canonical").asString()))
        .isEqualTo(rows.path("rows_hash").asString());
  }

  static Stream<Arguments> groundings() {
    return cases("grounding-strip.json");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("groundings")
  void givenAnswersGrounding_whenRecorded_thenTheLineKeepsItWithoutCellValues(JsonNode strip) {
    // Arrange
    Map<String, Object> grounding =
        EXACT.treeToValue(strip.path("grounding"), new TypeReference<Map<String, Object>>() {});
    AuditEvent event =
        new AuditEvent(
            Instant.parse("2026-10-16T20:58:38.434Z"),
            AuditEvent.Event.EXCHANGE,
            null,
            "acme",
            "hr",
            "7b2280eab4b5",
            null,
            null,
            "q",
            "a",
            AuditEvent.Outcome.ANSWERED,
            null,
            List.of(),
            List.of(),
            List.of(),
            new AuditEvent.Figures(0, 0, 0, 0),
            grounding,
            null,
            0);

    // Act
    new AuditLog(JSON, new SimpleMeterRegistry()).record(event);

    // Assert
    assertThat(EXACT.readTree(audit.lines().getFirst()).path("grounding"))
        .isEqualTo(strip.path("expect"));
  }

  private static AirlockDefinition airlock() {
    return new AirlockDefinition(
        "hr",
        "",
        "7b2280eab4b5",
        "acme",
        "HR",
        new AirlockDocument.Access(List.of("eric@example.com"), List.of("*")),
        "ollama",
        List.of(),
        List.of(),
        "prompt",
        new AirlockDefinition.HandlerSelection(null, null, null, null));
  }

  private static Stream<Arguments> cases(String file) {
    List<Arguments> cases = new ArrayList<>();
    EXACT
        .readTree(path(file).toFile())
        .path("cases")
        .forEach(c -> cases.add(arguments(named(c.path("id").asString(), c))));
    return cases.stream();
  }

  private static String text(JsonNode node) {
    return node.isNull() ? null : node.asString();
  }

  private static String sha256(String text) throws Exception {
    return HexFormat.of()
        .formatHex(
            MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
  }

  private static Path path(String file) {
    String cases = System.getProperty("aisolate.conformance.dir");
    if (cases == null) {
      throw new IllegalStateException("the build passes aisolate.conformance.dir");
    }
    return Path.of(cases, "audit", file);
  }
}
