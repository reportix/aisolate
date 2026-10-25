package com.reportix.aisolate.backend.application;

import com.reportix.aisolate.backend.infrastructure.metrics.PrometheusClient;
import com.reportix.aisolate.backend.infrastructure.security.Access;
import com.reportix.aisolate.backend.infrastructure.tracing.JaegerClient;
import java.util.Optional;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Which of Monitoring's views this install has: the AIsolate UI shows a tab per one, the rail item
 * with any.
 */
@RestController
public class MonitoringController {

  private final boolean tracing;
  private final boolean metrics;

  public MonitoringController(
      Optional<JaegerClient> traceStore, Optional<PrometheusClient> metricsStore) {
    this.tracing = traceStore.isPresent();
    this.metrics = metricsStore.isPresent();
  }

  public record MonitoringDTO(boolean tracing, boolean metrics) {}

  @GetMapping("/v1/monitoring")
  @PreAuthorize(Access.DEVELOPER_ANYWHERE)
  public MonitoringDTO get() {
    return new MonitoringDTO(tracing, metrics);
  }
}
