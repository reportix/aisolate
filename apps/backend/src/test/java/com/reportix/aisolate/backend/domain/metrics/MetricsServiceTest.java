package com.reportix.aisolate.backend.domain.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class MetricsServiceTest {

  @ParameterizedTest
  @CsvSource(
      delimiter = ';',
      value = {
        "        ;           ;         ; ''",
        "acme  ;           ;         ; space=\"acme\"",
        "        ; hr-acme ;         ; space=~\"hr-acme\"",
        "        ; a,b       ; hr      ; space=~\"a|b\", airlock=\"hr\"",
        // A space named outright is already the caller's: no list beside it
        "acme  ; a,b       ; sales   ; space=\"acme\", airlock=\"sales\""
      })
  void givenTheScope_whenTheSelectorIsBuilt_thenItsMatchers(
      String space, String spaces, String airlock, String selector) {
    // Arrange
    List<String> list = spaces == null ? null : List.of(spaces.split(","));

    // Act
    String built = MetricsService.selector(space, list, airlock);

    // Assert
    assertThat(built).isEqualTo(selector);
  }

  @Test
  void givenTheDashboardsPanels_whenQueriedOverAnHour_thenTheDashboardsQueriesPerMinute() {
    // Arrange
    Duration step = Duration.ofSeconds(30);
    String s = "space=\"acme\"";

    // Act
    List<String> queries =
        List.of(
            MetricsService.exchanges(s, step),
            MetricsService.tokens(s, step),
            MetricsService.p95(s, step),
            MetricsService.counted("aisolate_refusals_total", "reason", s, Duration.ofHours(1)));

    // Assert
    assertThat(queries)
        .containsExactly(
            "sum by (space, airlock) (rate(aisolate_exchanges_total{space=\"acme\"}[60s])) * 60",
            "sum by (space, airlock)"
                + " (rate(gen_ai_client_token_usage_total{gen_ai_token_type!=\"total\","
                + " space=\"acme\"}[300s])) * 60",
            "histogram_quantile(0.95, sum by (le, space, airlock)"
                + " (rate(aisolate_exchange_duration_seconds_bucket{space=\"acme\"}[300s])))",
            "sum by (space, airlock, reason)"
                + " ((max_over_time(aisolate_refusals_total{space=\"acme\"}[3600s]) unless"
                + " aisolate_refusals_total{space=\"acme\"} offset 3600s) or"
                + " increase(aisolate_refusals_total{space=\"acme\"}[3600s])) > 0");
  }

  @Test
  void givenADeveloperOfNoSpace_whenCharted_thenNothingIsAskedOfTheStore() {
    // Arrange: a store that would fail any query
    MetricsService service =
        new MetricsService(
            Optional.of(
                new com.reportix.aisolate.backend.infrastructure.metrics.PrometheusClient(
                    "http://localhost:1",
                    tools.jackson.databind.json.JsonMapper.builder().build())));

    // Act
    MetricsService.Charts charts = service.charts(null, List.of(), null, "1h", Instant.EPOCH);

    // Assert
    assertThat(charts.exchangesPerMinute()).isEmpty();
    assertThat(charts.refusals()).isEmpty();
  }

  @ParameterizedTest
  @CsvSource({"10m, 600, 15", "1h, 3600, 30", "24h, 86400, 720"})
  void givenAPeriod_whenCharted_thenItsRangeInStepsOfAbout120PointsNeverUnder15s(
      String period, long rangeSeconds, long stepSeconds) {
    // Arrange
    MetricsService service =
        new MetricsService(
            Optional.of(
                new com.reportix.aisolate.backend.infrastructure.metrics.PrometheusClient(
                    "http://localhost:1",
                    tools.jackson.databind.json.JsonMapper.builder().build())));
    Instant now = Instant.parse("2026-10-19T15:00:00Z");

    // Act
    MetricsService.Charts charts = service.charts(null, List.of(), null, period, now);

    // Assert
    assertThat(charts.from()).isEqualTo(now.minusSeconds(rangeSeconds));
    assertThat(charts.stepSeconds()).isEqualTo(stepSeconds);
  }
}
