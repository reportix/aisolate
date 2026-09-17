package com.reportix.aisolate.backend.domain.metrics;

import com.reportix.aisolate.backend.domain.audit.AuditException;
import com.reportix.aisolate.backend.infrastructure.metrics.PrometheusClient;
import com.reportix.aisolate.config.Names;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * Monitoring's charts: the example dashboard's five panels, as fixed queries over the series the
 * pods serve, scoped to the caller's spaces by the {@code space} label the scraper copies from the
 * pod. Nothing a caller sends becomes PromQL but a space or an airlock id, each checked against the
 * id rule first. Without a metrics store every call is 404.
 */
@Service
public class MetricsService {

  /** The periods the charts cover, as the Tracing tab's but for all time. */
  public static final Map<String, Duration> PERIODS =
      Map.of(
          "10m", Duration.ofMinutes(10),
          "1h", Duration.ofHours(1),
          "24h", Duration.ofDays(1),
          "7d", Duration.ofDays(7),
          "30d", Duration.ofDays(30));

  /** About 120 points per line, never closer than the scraper's 15 s. */
  private static final int POINTS = 120;

  private final Optional<PrometheusClient> store;

  public MetricsService(Optional<PrometheusClient> store) {
    this.store = store;
  }

  /** One line of a time chart: whose it is, and its points as [epoch seconds, value]. */
  public record Line(String space, String airlock, List<double[]> points) {}

  /** One bar: whose it is, what it counts (a reason, a tool), and how many over the period. */
  public record Bar(String space, String airlock, String name, double count) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record Charts(
      Instant from,
      Instant to,
      long stepSeconds,
      List<Line> exchangesPerMinute,
      List<Line> tokensPerMinute,
      List<Line> p95DurationSeconds,
      List<Bar> refusals,
      List<Bar> toolCalls) {}

  /**
   * @param spaces the only spaces to chart, a developer's; null for every space (the admin)
   * @throws AuditException 404 without a metrics store, 400 for an id or a period not of the rule,
   *     502 when the store did not answer
   */
  public Charts charts(
      String space, List<String> spaces, String airlock, String period, Instant now) {
    PrometheusClient prometheus =
        store.orElseThrow(
            () -> new AuditException(HttpStatus.NOT_FOUND, "no metrics store is connected"));
    Duration range = PERIODS.get(period == null ? "24h" : period);
    if (range == null)
      throw new AuditException(HttpStatus.BAD_REQUEST, "period: 10m, 1h, 24h, 7d or 30d");
    checkId("space", space);
    checkId("airlock", airlock);
    Instant from = now.minus(range);
    Duration step = Duration.ofSeconds(Math.max(15, range.toSeconds() / POINTS));
    if (spaces != null && spaces.isEmpty()) {
      // A matcher on no space would match the series that have none: ask nothing.
      return new Charts(
          from, now, step.toSeconds(), List.of(), List.of(), List.of(), List.of(), List.of());
    }
    String s = selector(space, spaces, airlock);
    try {
      return new Charts(
          from,
          now,
          step.toSeconds(),
          lines(prometheus.range(exchanges(s, step), from, now, step)),
          lines(prometheus.range(tokens(s, step), from, now, step)),
          lines(prometheus.range(p95(s, step), from, now, step)),
          bars(
              prometheus.instant(counted("aisolate_refusals_total", "reason", s, range), now),
              "reason"),
          bars(
              prometheus.instant(counted("aisolate_tool_calls_total", "tool", s, range), now),
              "tool"));
    } catch (PrometheusClient.UnavailableException e) {
      throw new AuditException(HttpStatus.BAD_GATEWAY, "the metrics store did not answer");
    }
  }

  /** The label matchers every query carries: the space or the caller's spaces, the airlock. */
  static String selector(String space, List<String> spaces, String airlock) {
    List<String> matchers = new ArrayList<>();
    if (space != null) matchers.add("space=\"" + space + "\"");
    else if (spaces != null) matchers.add("space=~\"" + String.join("|", spaces) + "\"");
    if (airlock != null) matchers.add("airlock=\"" + airlock + "\"");
    return String.join(", ", matchers);
  }

  // The dashboard's queries (the chart's examples/grafana-dashboard.json), per minute where it is
  // per
  // second, the rate's window at least the step so no scrape falls between two points.

  static String exchanges(String s, Duration step) {
    return "sum by (space, airlock) (rate(aisolate_exchanges_total{%s}[%s])) * 60"
        .formatted(s, window(step, Duration.ofMinutes(1)));
  }

  static String tokens(String s, Duration step) {
    return "sum by (space, airlock) (rate(gen_ai_client_token_usage_total{%s}[%s])) * 60"
        .formatted(
            s.isEmpty() ? "gen_ai_token_type!=\"total\"" : "gen_ai_token_type!=\"total\", " + s,
            window(step, Duration.ofMinutes(5)));
  }

  static String p95(String s, Duration step) {
    return ("histogram_quantile(0.95, sum by (le, space, airlock)"
            + " (rate(aisolate_exchange_duration_seconds_bucket{%s}[%s])))")
        .formatted(s, window(step, Duration.ofMinutes(5)));
  }

  /** The dashboard's count over the period, which also counts a series that began within it. */
  static String counted(String metric, String by, String s, Duration range) {
    String r = range.toSeconds() + "s";
    return ("sum by (space, airlock, %2$s) ((max_over_time(%1$s{%3$s}[%4$s]) unless %1$s{%3$s}"
            + " offset %4$s) or increase(%1$s{%3$s}[%4$s])) > 0")
        .formatted(metric, by, s, r);
  }

  private static String window(Duration step, Duration least) {
    return Math.max(step.toSeconds(), least.toSeconds()) + "s";
  }

  private static void checkId(String field, String id) {
    if (id != null && !Names.SLUG.matcher(id).matches()) {
      throw new AuditException(HttpStatus.BAD_REQUEST, field + ": not an id");
    }
  }

  private static List<Line> lines(List<PrometheusClient.Series> series) {
    return series.stream()
        .filter(s -> !s.points().isEmpty())
        .map(s -> new Line(s.labels().get("space"), s.labels().get("airlock"), s.points()))
        .sorted(Comparator.comparing(l -> l.space() + "/" + l.airlock()))
        .toList();
  }

  private static List<Bar> bars(List<PrometheusClient.Series> series, String by) {
    return series.stream()
        .filter(s -> !s.points().isEmpty())
        .map(
            s ->
                new Bar(
                    s.labels().get("space"),
                    s.labels().get("airlock"),
                    s.labels().get(by),
                    Math.round(s.points().getFirst()[1])))
        .sorted(Comparator.comparingDouble(Bar::count).reversed())
        .toList();
  }
}
