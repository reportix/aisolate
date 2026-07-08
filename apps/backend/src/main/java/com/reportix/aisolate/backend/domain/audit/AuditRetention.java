package com.reportix.aisolate.backend.domain.audit;

import com.reportix.aisolate.backend.infrastructure.audit.AuditProperties;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The months of the audit trail: this one and the next exist before a line needs them, and those
 * older than {@code aisolate.audit.retention-months} are dropped whole. At the start, then every
 * six hours. Runs as the owner: the writer may neither create nor drop a partition.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AuditRetention {

  private final AuditRepository repository;
  private final AuditProperties properties;

  @Scheduled(initialDelay = 0, fixedDelayString = "${aisolate.audit.retention-interval:PT6H}")
  public void run() {
    YearMonth month = YearMonth.now(ZoneOffset.UTC);
    repository.ensurePartition(start(month));
    repository.ensurePartition(start(month.plusMonths(1)));
    int dropped = repository.dropBefore(start(month.minusMonths(properties.retentionMonths())));
    if (dropped > 0) {
      log.info(
          "audit trail: dropped {} months older than {} months",
          dropped,
          properties.retentionMonths());
    }
  }

  private static Instant start(YearMonth month) {
    return month.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
  }
}
