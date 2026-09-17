package com.reportix.aisolate.backend.application;

import com.reportix.aisolate.backend.domain.metrics.MetricsService;
import com.reportix.aisolate.backend.infrastructure.security.Access;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Monitoring's charts: the admin's over every space, a developer's over their spaces; the auditor
 * and a space's users none. 404 without a metrics store.
 */
@Tag(name = "Metrics", description = "The example dashboard's panels, from fixed queries.")
@RestController
@RequiredArgsConstructor
public class MetricsController {

  private final MetricsService metrics;
  private final Access access;

  @GetMapping("/v1/metrics")
  @PreAuthorize(Access.DEVELOPER_ANYWHERE)
  public MetricsService.Charts get(
      @RequestParam(required = false) String space,
      @RequestParam(required = false) String airlock,
      @RequestParam(required = false) String period) {
    // Another space's, asked by name: the 403 its pages give.
    if (space != null && !space.isBlank()) access.developer(space);
    return metrics.charts(
        blankToNull(space), access.developedSpaces(), blankToNull(airlock), period, Instant.now());
  }

  private static String blankToNull(String s) {
    return s == null || s.isBlank() ? null : s;
  }
}
