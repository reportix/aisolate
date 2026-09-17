package com.reportix.aisolate.backend.infrastructure.tracing;

import com.reportix.aisolate.backend.domain.trace.TraceTree;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The trace store as Jaeger's query API serves a trace, {@code GET /api/traces/{id}}, in-cluster
 * ({@code aisolate.tracing.query-url}); any store that answers that call alike works. The browser
 * never calls it: the backend reads it for the trace page alone.
 */
@Component
@ConditionalOnExpression(
    "T(org.springframework.util.StringUtils).hasText('${aisolate.tracing.query-url:}')")
@Slf4j
public class JaegerClient {

  private static final Duration TIMEOUT = Duration.ofSeconds(5);

  private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
  private final JsonMapper json;
  private final String base;

  public JaegerClient(@Value("${aisolate.tracing.query-url}") String url, JsonMapper json) {
    this.json = json;
    this.base = url.replaceAll("/+$", "");
  }

  /** The store did not answer, or not as Jaeger does; the log says how. */
  public static class UnavailableException extends RuntimeException {}

  /**
   * @param traceId 32 hex digits, checked by the caller: it goes into the path
   * @return empty when the store holds no such trace (Jaeger keeps them for a while only)
   * @throws UnavailableException the store did not answer, or not with a trace
   */
  // The whole answer in memory; a cap on its size if a trace ever runs to thousands of
  // spans.
  public Optional<List<TraceTree.Span>> spans(String traceId) {
    HttpResponse<String> response;
    try {
      response =
          http.send(
              HttpRequest.newBuilder(URI.create(base + "/api/traces/" + traceId))
                  .timeout(TIMEOUT)
                  .header("Accept", "application/json")
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());
    } catch (IOException e) {
      log.warn("trace store: the trace query failed: {}", e.toString());
      throw new UnavailableException();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new UnavailableException();
    }
    if (response.statusCode() == 404) return Optional.empty();
    if (response.statusCode() != 200) {
      log.warn("trace store: the trace query answered HTTP {}", response.statusCode());
      throw new UnavailableException();
    }
    JsonNode trace;
    try {
      trace = json.readTree(response.body()).path("data").path(0);
    } catch (JacksonException e) {
      log.warn("trace store: the trace query answered no JSON");
      throw new UnavailableException();
    }
    if (!trace.path("spans").isArray() || trace.path("spans").isEmpty()) return Optional.empty();
    JsonNode processes = trace.path("processes");
    return Optional.of(
        trace.path("spans").valueStream().map(span -> span(span, processes)).toList());
  }

  private static TraceTree.Span span(JsonNode span, JsonNode processes) {
    Map<String, Object> tags = new HashMap<>();
    span.path("tags").forEach(tag -> tags.put(tag.path("key").asString(""), value(tag)));
    JsonNode parent = span.path("references").path(0);
    return new TraceTree.Span(
        span.path("spanID").asString(""),
        parent.isMissingNode() ? null : parent.path("spanID").asString(null),
        span.path("operationName").asString(""),
        processes.path(span.path("processID").asString("")).path("serviceName").asString(null),
        span.path("startTime").asLong(0),
        span.path("duration").asLong(0),
        tags);
  }

  /** As Jaeger types it: string, bool, int64 or float64. */
  private static Object value(JsonNode tag) {
    JsonNode v = tag.path("value");
    if (v.isBoolean()) return v.booleanValue();
    if (v.isIntegralNumber()) return v.longValue();
    if (v.isNumber()) return v.doubleValue();
    return v.asString("");
  }
}
