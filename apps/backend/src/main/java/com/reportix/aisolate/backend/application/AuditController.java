package com.reportix.aisolate.backend.application;

import com.fasterxml.jackson.annotation.JsonRawValue;
import com.reportix.aisolate.backend.domain.audit.AuditLine;
import com.reportix.aisolate.backend.domain.audit.AuditRepository;
import com.reportix.aisolate.backend.domain.audit.AuditService;
import com.reportix.aisolate.backend.infrastructure.security.Access;
import io.swagger.v3.oas.annotations.tags.Tag;
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
 * The audit trail as the AIsolate UI, the CLI and an auditor read it: the admin and the auditor all
 * of it, a developer the events of the spaces they develop.
 */
@Tag(name = "Audit", description = "The audit trail: exchanges and refusals, and its chain.")
@RestController
@RequestMapping("/v1/audit")
@RequiredArgsConstructor
public class AuditController {

  private final AuditService audit;
  private final Access access;

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record Figures(int sourced, int computed, int unsourced, int mismatch) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record SummaryDTO(
      long id,
      Instant receivedAt,
      Instant ts,
      String event,
      String space,
      String airlock,
      String revision,
      String traceId,
      String user,
      String client,
      String outcome,
      String reason,
      String question,
      Figures figures,
      Integer inputTokens,
      Integer outputTokens,
      Long durationMs) {

    static SummaryDTO of(AuditRepository.Row row) {
      AuditLine.Columns c = row.columns();
      // A line without its four counts reports none, which is not zero.
      Figures figures =
          c.sourced() == null
                  || c.computed() == null
                  || c.unsourced() == null
                  || c.mismatch() == null
              ? null
              : new Figures(c.sourced(), c.computed(), c.unsourced(), c.mismatch());
      return new SummaryDTO(
          row.id(),
          row.receivedAt(),
          c.ts(),
          c.event(),
          c.space(),
          c.airlock(),
          c.revision(),
          c.traceId(),
          c.user(),
          c.client(),
          c.outcome(),
          c.reason(),
          c.question(),
          figures,
          c.inputTokens(),
          c.outputTokens(),
          c.durationMs());
    }
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record PageDTO(List<SummaryDTO> events, String nextCursor) {}

  /** {@code event} is the stored line itself, not a re-rendering of it. */
  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record EventDTO(
      long id, Instant receivedAt, String prevHash, String hash, @JsonRawValue String event) {

    static EventDTO of(AuditRepository.Row row) {
      return new EventDTO(row.id(), row.receivedAt(), row.prevHash(), row.hash(), row.body());
    }
  }

  @GetMapping("/events")
  @PreAuthorize(Access.AUDIT_READER)
  public PageDTO list(
      @RequestParam(required = false) String space,
      @RequestParam(required = false) String airlock,
      @RequestParam(required = false) String user,
      @RequestParam(required = false) String outcome,
      @RequestParam(name = "trace_id", required = false) String traceId,
      @RequestParam(required = false) String q,
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String to,
      @RequestParam(required = false) String cursor,
      @RequestParam(defaultValue = "50") int limit) {
    requireAudited(space);
    AuditService.Page page =
        audit.list(
            space,
            access.auditedSpaces(),
            airlock,
            user,
            outcome,
            traceId,
            null,
            q,
            from,
            to,
            cursor,
            limit);
    return new PageDTO(page.events().stream().map(SummaryDTO::of).toList(), page.nextCursor());
  }

  @GetMapping("/events/{id}")
  @PreAuthorize(Access.AUDIT_READER)
  public EventDTO get(@PathVariable long id) {
    AuditRepository.Row row = audit.find(id);
    requireAudited(row.columns().space());
    return EventDTO.of(row);
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record DataErrorDTO(AuditService.DataError dataError) {}

  /** Whether the airlock's newest exchange that called a tool could read its data sources. */
  @GetMapping("/data-error")
  @PreAuthorize(Access.AUDIT_READER)
  public DataErrorDTO dataError(@RequestParam String space, @RequestParam String airlock) {
    requireAudited(space);
    return new DataErrorDTO(audit.dataError(space, airlock).orElse(null));
  }

  @GetMapping("/verify")
  @PreAuthorize(Access.AUDIT_READER)
  public AuditService.Verification verify(
      @RequestParam(required = false) String space,
      @RequestParam(required = false) String airlock) {
    requireAudited(space);
    return audit.verify(space, airlock);
  }

  /** A developer asking for another space's audit gets the 403 its pages give. */
  private void requireAudited(String space) {
    if (space != null && !space.isBlank() && access.auditedSpaces() != null) {
      access.developer(space);
    }
  }
}
