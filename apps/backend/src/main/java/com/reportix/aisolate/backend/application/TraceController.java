package com.reportix.aisolate.backend.application;

import com.fasterxml.jackson.annotation.JsonRawValue;
import com.reportix.aisolate.backend.domain.audit.AuditLine;
import com.reportix.aisolate.backend.domain.trace.TraceService;
import com.reportix.aisolate.backend.domain.trace.TraceTree;
import com.reportix.aisolate.backend.infrastructure.security.Access;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * Monitoring's traces (docs/api.md): the admin reads every space's, a developer their spaces'; the
 * auditor and those who only ask none. 404 throughout without a trace store.
 */
@RestController
@RequestMapping("/v1/traces")
@RequiredArgsConstructor
public class TraceController {

  private final TraceService traces;
  private final Access access;

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record SummaryDTO(
      long id,
      Instant ts,
      String event,
      String space,
      String airlock,
      String traceId,
      String user,
      String outcome,
      String reason,
      String question,
      String model,
      Integer modelCalls,
      Integer toolCalls,
      Integer inputTokens,
      Integer outputTokens,
      Long durationMs) {

    static SummaryDTO of(TraceService.Summary s) {
      AuditLine.Columns c = s.event().columns();
      return new SummaryDTO(
          s.event().id(),
          c.ts(),
          c.event(),
          c.space(),
          c.airlock(),
          c.traceId(),
          c.user(),
          c.outcome(),
          c.reason(),
          c.question(),
          s.model(),
          s.modelCalls(),
          s.toolCalls(),
          c.inputTokens(),
          c.outputTokens(),
          c.durationMs());
    }
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record PageDTO(List<SummaryDTO> traces, String nextCursor) {}

  /**
   * {@code event} is the audit line as stored: the question and the answer come from it, never from
   * a span.
   */
  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record TraceDTO(
      String traceId,
      long eventId,
      @JsonRawValue String event,
      String spansStatus,
      List<TraceTree.Step> steps) {}

  @GetMapping
  @PreAuthorize(Access.DEVELOPER_ANYWHERE)
  public PageDTO list(
      @RequestParam(required = false) String space,
      @RequestParam(required = false) String airlock,
      @RequestParam(required = false) String q,
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String to,
      @RequestParam(required = false) String cursor,
      @RequestParam(defaultValue = "50") int limit) {
    // Another space's, asked by name: the 403 its pages give.
    if (space != null && !space.isBlank()) access.developer(space);
    TraceService.Page page =
        traces.list(space, access.developedSpaces(), airlock, q, from, to, cursor, limit);
    return new PageDTO(page.traces().stream().map(SummaryDTO::of).toList(), page.nextCursor());
  }

  @GetMapping("/{traceId}")
  @PreAuthorize(Access.DEVELOPER_ANYWHERE)
  public TraceDTO get(@PathVariable String traceId) {
    TraceService.Trace trace = traces.get(traceId, access.developedSpaces());
    return new TraceDTO(
        traceId, trace.event().id(), trace.event().body(), trace.spansStatus(), trace.steps());
  }
}
