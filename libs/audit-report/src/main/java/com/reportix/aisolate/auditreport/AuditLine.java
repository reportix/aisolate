package com.reportix.aisolate.auditreport;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * One line of an airlock's audit trail, as the airlock pod writes it: one exchange or one refused
 * request, with what the report holds of it. Read from the JSON tree, not bound to a class.
 *
 * @param traceId null when nothing traced the exchange
 * @param user the asker's e-mail; a machine client, which has none, by its client id
 * @param answer null unless the model answered
 * @param model {@code provider/model}, or the name alone; null when no model was reached
 * @param inputTokens null when the provider reported none
 */
public record AuditLine(
    Instant ts,
    String traceId,
    String space,
    String airlock,
    String user,
    String question,
    String answer,
    String outcome,
    List<Tool> tools,
    List<Verifier> verifiers,
    String model,
    Integer inputTokens,
    Integer outputTokens,
    long durationMs) {

  /**
   * @param dataSource null when the airlock does not know it
   * @param rows null when the call failed
   */
  public record Tool(String name, String dataSource, Integer rows) {}

  /**
   * @param verdict {@code pass}, {@code warn} or {@code fail}
   */
  public record Verifier(String name, String verdict) {}

  static final Set<String> OUTCOMES = Set.of("answered", "dont_know", "refused", "error");
  private static final Set<String> VERDICTS = Set.of("PASS", "WARN", "FAIL");

  private static final JsonMapper JSON = JsonMapper.builder().build();

  /**
   * The audit line in a log line, or null for any other line: a log line of the pod, a {@code
   * kubectl logs --timestamps} or {@code logcli} prefix before the object is skipped. The object
   * starts at the first <code>{"</code>: a Loki label set before it is <code>{key="…"}</code>.
   *
   * @throws IllegalArgumentException an audit line the report cannot use: no space or airlock, no
   *     time, an outcome or verdict it does not know, a count that is no whole number from zero up
   */
  public static AuditLine parse(String logLine) {
    int start = logLine.indexOf("{\"");
    if (start < 0) {
      return null;
    }
    JsonNode line;
    try {
      line = JSON.readTree(logLine.substring(start));
    } catch (JacksonException notJson) {
      return null;
    }
    String event = text(line, "event");
    if (!"exchange".equals(event) && !"refusal".equals(event)) {
      return null;
    }
    if (text(line, "space") == null || text(line, "airlock") == null) {
      throw new IllegalArgumentException("no space or airlock");
    }
    if (!OUTCOMES.contains(text(line, "outcome"))) {
      throw new IllegalArgumentException("outcome " + text(line, "outcome"));
    }
    Instant ts;
    try {
      ts = Instant.parse(String.valueOf(text(line, "ts")));
    } catch (DateTimeParseException noTime) {
      throw new IllegalArgumentException("ts " + text(line, "ts"));
    }
    JsonNode model = line.path("model");
    String provider = text(model, "provider");
    String name = text(model, "name");
    return new AuditLine(
        ts,
        text(line, "trace_id"),
        text(line, "space"),
        text(line, "airlock"),
        text(line, "user") != null ? text(line, "user") : text(line, "client"),
        text(line, "question"),
        text(line, "answer"),
        AuditReport.outcomeOf(text(line, "outcome"), text(line, "answer")),
        list(
            line.path("tools"),
            t -> new Tool(text(t, "name"), text(t, "data_source"), integer(t, "rows"))),
        list(line.path("verifiers"), v -> new Verifier(text(v, "name"), verdict(v))),
        name == null ? null : provider == null ? name : provider + "/" + name,
        integer(model, "input_tokens"),
        integer(model, "output_tokens"),
        Objects.requireNonNullElse(count(line, "duration_ms", Long.MAX_VALUE), 0L));
  }

  boolean refused() {
    return "refused".equals(outcome);
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node.path(field);
    return value.isValueNode() && !value.isNull() ? value.asString() : null;
  }

  private static String verdict(JsonNode verifier) {
    String verdict = text(verifier, "verdict");
    if (!VERDICTS.contains(verdict)) {
      throw new IllegalArgumentException("verdict " + verdict);
    }
    return verdict.toLowerCase(Locale.ROOT);
  }

  private static Integer integer(JsonNode node, String field) {
    Long count = count(node, field, Integer.MAX_VALUE);
    return count == null ? null : count.intValue();
  }

  /** A whole number from zero to {@code max}; null when absent. A fact may hold no other. */
  private static Long count(JsonNode node, String field, long max) {
    JsonNode value = node.path(field);
    if (value.isMissingNode() || value.isNull()) {
      return null;
    }
    if (!value.isIntegralNumber()
        || !value.canConvertToLong()
        || value.asLong() < 0
        || value.asLong() > max) {
      throw new IllegalArgumentException(field + " " + value);
    }
    return value.asLong();
  }

  private static <T> List<T> list(JsonNode array, Function<JsonNode, T> item) {
    List<T> items = new ArrayList<>();
    array.forEach(node -> items.add(item.apply(node)));
    return items;
  }
}
