package com.reportix.aisolate.backend.domain.audit;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.StreamWriteFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * One audit line as stored: its canonical JSON (keys sorted, compact, decimals as written), the
 * digest an exact repeat shares, and the columns read from it. The columns are a function of the
 * body alone, so verify can tell a column changed behind the body's back.
 */
public record AuditLine(String body, String digest, Columns columns) {

  /** What the hash of an airlock's first line chains to. */
  public static final String GENESIS = "0".repeat(64);

  static final Set<String> EVENTS = Set.of("exchange", "refusal");
  static final Set<String> OUTCOMES = Set.of("answered", "dont_know", "refused", "error");

  private static final JsonMapper CANONICAL =
      JsonMapper.builder()
          .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
          .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(StreamWriteFeature.WRITE_BIGDECIMAL_AS_PLAIN)
          .build();

  /** The columns of {@code audit_events} besides id, received_at and the chain's. */
  public record Columns(
      Instant ts,
      String space,
      String airlock,
      String revision,
      String event,
      String traceId,
      String user,
      String client,
      String outcome,
      String reason,
      String question,
      String answer,
      Integer sourced,
      Integer computed,
      Integer unsourced,
      Integer mismatch,
      Integer inputTokens,
      Integer outputTokens,
      Long durationMs) {}

  /**
   * @throws AuditException 400 when {@code json} is no audit line: no object, or no {@code ts},
   *     {@code event}, {@code space}, {@code airlock} or {@code outcome} of the kinds the airlock
   *     writes
   */
  public static AuditLine parse(String json) {
    Map<String, Object> line;
    try {
      line = read(json);
    } catch (JacksonException | ClassCastException e) {
      throw AuditException.invalid("not a JSON object");
    }
    Columns columns = columnsOf(line);
    if (columns.ts() == null) throw AuditException.invalid("ts: required, an ISO-8601 instant");
    if (!EVENTS.contains(columns.event()))
      throw AuditException.invalid("event: exchange or refusal");
    if (!OUTCOMES.contains(columns.outcome())) {
      throw AuditException.invalid("outcome: answered, dont_know, refused or error");
    }
    required("space", columns.space());
    required("airlock", columns.airlock());
    String body = CANONICAL.writeValueAsString(line);
    return new AuditLine(body, sha256(body), columns);
  }

  /** The columns a stored body gives, for verify. */
  public static Columns columnsOf(String body) {
    return columnsOf(read(body));
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> read(String json) {
    return (Map<String, Object>) CANONICAL.readValue(json, LinkedHashMap.class);
  }

  private static Columns columnsOf(Map<String, Object> line) {
    Map<?, ?> figures = line.get("figures") instanceof Map<?, ?> m ? m : Map.of();
    Map<?, ?> model = line.get("model") instanceof Map<?, ?> m ? m : Map.of();
    return new Columns(
        instant(line.get("ts")),
        text(line.get("space")),
        text(line.get("airlock")),
        text(line.get("revision")),
        text(line.get("event")),
        text(line.get("trace_id")),
        text(line.get("user")),
        text(line.get("client")),
        text(line.get("outcome")),
        text(line.get("reason")),
        text(line.get("question")),
        text(line.get("answer")),
        integer(figures.get("sourced")),
        integer(figures.get("computed")),
        integer(figures.get("unsourced")),
        integer(figures.get("mismatch")),
        integer(model.get("input_tokens")),
        integer(model.get("output_tokens")),
        line.get("duration_ms") instanceof Number n && whole(n) ? n.longValue() : null);
  }

  /** The chain: {@code sha256(prev_hash || body)}, both as text. */
  public static String chain(String prevHash, String body) {
    return sha256(prevHash + body);
  }

  static String sha256(String text) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String text(Object value) {
    return value instanceof String s ? s : null;
  }

  private static void required(String field, String value) {
    if (value == null || value.isBlank()) throw AuditException.invalid(field + ": required");
  }

  /** Microseconds, as PostgreSQL keeps a timestamp. */
  private static Instant instant(Object value) {
    if (!(value instanceof String s)) return null;
    try {
      return Instant.parse(s).truncatedTo(ChronoUnit.MICROS);
    } catch (DateTimeParseException e) {
      return null;
    }
  }

  private static Integer integer(Object value) {
    return value instanceof Number n
            && whole(n)
            && n.longValue() >= Integer.MIN_VALUE
            && n.longValue() <= Integer.MAX_VALUE
        ? n.intValue()
        : null;
  }

  private static boolean whole(Number n) {
    return n instanceof Integer
        || n instanceof Long
        || (n instanceof BigInteger b && b.bitLength() < 64)
        || (n instanceof BigDecimal d && d.scale() <= 0 && d.toBigInteger().bitLength() < 64);
  }
}
