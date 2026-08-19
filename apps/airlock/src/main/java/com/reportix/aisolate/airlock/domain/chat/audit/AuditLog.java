package com.reportix.aisolate.airlock.domain.chat.audit;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The audit trail: each line handed to the backend through the {@link AuditSink}, and, unless
 * {@code aisolate.audit.stdout} is off, also one JSON object per line on the logger {@code
 * aisolate.audit}, which the pod's logging configuration sends to stdout apart from its own log,
 * for a SIEM to collect. Without an ingest (a headless install) stdout is the only copy, so it is
 * always written.
 *
 * <p>The pod's metrics are counted from the same lines, so the two never disagree: {@code
 * aisolate.exchanges} by outcome (refusals included), {@code aisolate.refusals} by reason, {@code
 * aisolate.tool.calls} by tool and the {@code aisolate.exchange.duration} timer, each by airlock.
 * Every label comes from a closed set: the served airlock's names, its tools, the enums.
 */
@Slf4j
public class AuditLog {

  public static final String LOGGER = "aisolate.audit";

  private static final Logger TRAIL = LoggerFactory.getLogger(LOGGER);

  private final JsonMapper jsonMapper;
  private final MeterRegistry meters;
  private final AuditSink sink;
  private final boolean stdout;

  /** stdout only: no ingest. */
  public AuditLog(JsonMapper jsonMapper, MeterRegistry meters) {
    this(jsonMapper, meters, AuditSink.OFF, true);
  }

  public AuditLog(JsonMapper jsonMapper, MeterRegistry meters, AuditSink sink, boolean stdout) {
    this.jsonMapper = jsonMapper;
    this.meters = meters;
    this.sink = sink;
    this.stdout = stdout || !sink.enabled();
    meters.gauge("aisolate.audit.buffered.bytes", sink, AuditSink::bufferedBytes);
  }

  /** Whether a new question may be admitted: false while the sink fails closed on a full buffer. */
  public boolean accepting() {
    return sink.accepting();
  }

  /** Never throws: the exchange is over, and a line that cannot be written must not undo it. */
  public void record(AuditEvent event) {
    try {
      JsonNode tree = jsonMapper.valueToTree(event);
      stripRowValues(tree.path("grounding"));
      String line = jsonMapper.writeValueAsString(tree);
      if (stdout) TRAIL.info(line);
      if (!sink.offer(line)) {
        meters.counter("aisolate.audit.dropped").increment();
        log.error(
            "audit line for {}/{} dropped: the buffer is full", event.space(), event.airlock());
      }
    } catch (JacksonException unwritable) {
      // The pod's own log, not the trail: names the request, never what was said.
      log.error(
          "audit line for {}/{} could not be written", event.space(), event.airlock(), unwritable);
    }
    count(event);
  }

  /** What a cell held stays with the rows: the grounding keeps where each figure came from. */
  private static final Set<String> ROW_VALUES = Set.of("value", "subject", "period", "unit");

  private static void stripRowValues(JsonNode node) {
    if (node instanceof ObjectNode object) ROW_VALUES.forEach(object::remove);
    for (JsonNode child : node) stripRowValues(child);
  }

  private void count(AuditEvent event) {
    String airlock = event.airlock();
    meters
        .counter("aisolate.exchanges", "airlock", airlock, "outcome", event.outcome().json())
        .increment();
    if (event.event() == AuditEvent.Event.REFUSAL) {
      meters.counter("aisolate.refusals", "airlock", airlock, "reason", event.reason()).increment();
      return;
    }
    event
        .tools()
        .forEach(
            tool ->
                meters
                    .counter("aisolate.tool.calls", "airlock", airlock, "tool", tool.name())
                    .increment());
    Timer.builder("aisolate.exchange.duration")
        .tags("airlock", airlock)
        .publishPercentileHistogram()
        .register(meters)
        .record(Duration.ofMillis(event.durationMs()));
  }
}
