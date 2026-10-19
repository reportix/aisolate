package com.reportix.aisolate.backend.domain.trace;

import com.reportix.aisolate.backend.domain.audit.AuditException;
import com.reportix.aisolate.backend.domain.audit.AuditRepository;
import com.reportix.aisolate.backend.domain.audit.AuditService;
import com.reportix.aisolate.backend.infrastructure.tracing.JaegerClient;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.MissingNode;

/**
 * Monitoring's traces (docs/observability.md): the list is the audit trail's exchanges, scoped as
 * the audit is; a trace is its audit line and the spans the trace store holds for it. Without a
 * trace store every call is 404, as if the endpoints did not exist.
 */
@Service
@Slf4j
public class TraceService {

  /** The exchange span's trace id; nothing else reaches the trace store's path. */
  private static final Pattern TRACE_ID = Pattern.compile("[0-9a-f]{32}");

  private final AuditService audit;
  private final Optional<JaegerClient> store;
  private final JsonMapper json;

  public TraceService(AuditService audit, Optional<JaegerClient> store, JsonMapper json) {
    this.audit = audit;
    this.store = store;
    this.json = json;
  }

  /**
   * @param model the main LLM's name; null on a refusal
   * @param modelCalls every model request of the exchange, failed ones included
   */
  public record Summary(
      AuditRepository.Row event, String model, Integer modelCalls, Integer toolCalls) {}

  public record Page(List<Summary> traces, String nextCursor) {}

  /**
   * @param spaces the only spaces to list, a developer's; null for every space
   * @param q only exchanges whose question holds it, case ignored
   */
  public Page list(
      String space,
      List<String> spaces,
      String airlock,
      String q,
      String from,
      String to,
      String cursor,
      int limit) {
    store();
    AuditService.Page page =
        audit.list(space, spaces, airlock, null, null, null, q, null, from, to, cursor, limit);
    Map<Long, String> bodies =
        audit.bodies(page.events().stream().map(AuditRepository.Row::id).toList());
    return new Page(
        page.events().stream().map(row -> summary(row, bodies.get(row.id()))).toList(),
        page.nextCursor());
  }

  /**
   * @param spansStatus {@code held}; {@code gone} when the store no longer holds the trace; {@code
   *     unavailable} when it did not answer
   * @param steps the span tree; null unless held
   */
  public record Trace(AuditRepository.Row event, String spansStatus, List<TraceTree.Step> steps) {}

  /**
   * @param spaces the only spaces whose traces the caller reads; null for every space
   * @throws AuditException 404 for a trace no audit line of those spaces names
   */
  public Trace get(String traceId, List<String> spaces) {
    JaegerClient jaeger = store();
    if (!TRACE_ID.matcher(traceId).matches()) throw unknown();
    AuditRepository.Row listed =
        audit
            .list(null, spaces, null, null, null, traceId, null, null, null, null, null, 1)
            .events()
            .stream()
            .findFirst()
            .orElseThrow(TraceService::unknown);
    AuditRepository.Row event = audit.find(listed.id());
    List<TraceTree.Span> spans;
    try {
      spans = jaeger.spans(traceId).orElse(null);
    } catch (JaegerClient.UnavailableException e) {
      return new Trace(event, "unavailable", null);
    }
    if (spans == null) return new Trace(event, "gone", null);
    String space = event.columns().space();
    // A pod writes its own audit lines: one naming another space's trace id gets no span of it.
    if (!spans.stream()
        .map(s -> s.tags().get("aisolate.space"))
        .filter(Objects::nonNull)
        .allMatch(space::equals)) {
      log.warn("trace {}: spans of another space than its audit line's, {}", traceId, space);
      return new Trace(event, "gone", null);
    }
    return new Trace(event, "held", TraceTree.of(spans));
  }

  private JaegerClient store() {
    return store.orElseThrow(
        () -> new AuditException(HttpStatus.NOT_FOUND, "no trace store is connected"));
  }

  private static AuditException unknown() {
    return new AuditException(HttpStatus.NOT_FOUND, "unknown trace");
  }

  private Summary summary(AuditRepository.Row row, String body) {
    // The body was parsed when it was stored.
    JsonNode line = body == null ? MissingNode.getInstance() : json.readTree(body);
    JsonNode model = line.path("model");
    JsonNode tools = line.path("tools");
    return new Summary(
        row,
        model.path("name").isString() ? model.path("name").asString() : null,
        model.path("calls").isIntegralNumber() ? model.path("calls").asInt() : null,
        tools.isArray() ? tools.size() : null);
  }
}
