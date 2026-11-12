package com.reportix.aisolate.backend.infrastructure.metrics;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Prometheus's HTTP API in the cluster ({@code aisolate.metrics.query-url}): {@code /api/v1/query}
 * and {@code /api/v1/query_range}, which any compatible store answers alike. Only the backend's own
 * fixed queries reach it; the browser never calls it.
 */
@Component
@ConditionalOnExpression(
    "T(org.springframework.util.StringUtils).hasText('${aisolate.metrics.query-url:}')")
@Slf4j
public class PrometheusClient {

  private static final Duration TIMEOUT = Duration.ofSeconds(10);

  /** The series MetricsService asks for: the pods' own and Spring AI's token usage. */
  private static final String PRODUCT_SERIES = "{__name__=~\"aisolate_.*|gen_ai_.*\"}";

  private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
  private final JsonMapper json;
  private final String base;

  public PrometheusClient(@Value("${aisolate.metrics.query-url}") String url, JsonMapper json) {
    this.json = json;
    this.base = url.replaceAll("/+$", "");
  }

  /** The store did not answer, or not as Prometheus does; the log says how. */
  public static class UnavailableException extends RuntimeException {
    public UnavailableException() {}

    public UnavailableException(String message) {
      super(message);
    }
  }

  /**
   * One series: its labels, and its points as [epoch seconds, value]; NaN and infinite values left
   * out.
   */
  public record Series(Map<String, String> labels, List<double[]> points) {}

  public List<Series> range(String query, Instant from, Instant to, Duration step) {
    return ask(
        "/api/v1/query_range",
        "query="
            + encode(query)
            + "&start="
            + from.getEpochSecond()
            + "&end="
            + to.getEpochSecond()
            + "&step="
            + step.toSeconds());
  }

  public List<Series> instant(String query, Instant at) {
    return ask("/api/v1/query", "query=" + encode(query) + "&time=" + at.getEpochSecond());
  }

  /**
   * The product's series, every one, gone: the install's clean slate ({@code DELETE /v1/spaces}).
   * Prometheus's admin API, on with {@code --web.enable-admin-api}.
   *
   * @throws UnavailableException the store refused or did not answer; the message says how
   */
  public void deleteAll() {
    admin("/api/v1/admin/tsdb/delete_series", "match[]=" + encode(PRODUCT_SERIES));
    admin("/api/v1/admin/tsdb/clean_tombstones", "");
  }

  private void admin(String path, String form) {
    HttpResponse<String> response = post(path, form);
    if (response.statusCode() != 204) {
      String why;
      try {
        why = json.readTree(response.body()).path("error").asString("");
      } catch (JacksonException e) {
        why = "";
      }
      log.warn("metrics store: {} answered HTTP {} {}", path, response.statusCode(), why);
      throw new UnavailableException(
          "HTTP " + response.statusCode() + " from " + path + (why.isEmpty() ? "" : ": " + why));
    }
  }

  private HttpResponse<String> post(String path, String form) {
    try {
      return http.send(
          HttpRequest.newBuilder(URI.create(base + path))
              .timeout(TIMEOUT)
              .header("Content-Type", "application/x-www-form-urlencoded")
              .header("Accept", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(form))
              .build(),
          HttpResponse.BodyHandlers.ofString());
    } catch (IOException e) {
      log.warn("metrics store: {} failed: {}", path, e.toString());
      throw new UnavailableException(path + " failed: " + e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new UnavailableException(path + " interrupted");
    }
  }

  private List<Series> ask(String path, String form) {
    HttpResponse<String> response = post(path, form);
    if (response.statusCode() != 200) {
      log.warn("metrics store: {} answered HTTP {}", path, response.statusCode());
      throw new UnavailableException();
    }
    JsonNode result;
    try {
      result = json.readTree(response.body()).path("data").path("result");
    } catch (JacksonException e) {
      log.warn("metrics store: {} answered no JSON", path);
      throw new UnavailableException();
    }
    List<Series> series = new ArrayList<>();
    for (JsonNode s : result) {
      Map<String, String> labels = new LinkedHashMap<>();
      s.path("metric").properties().forEach(e -> labels.put(e.getKey(), e.getValue().asString("")));
      List<double[]> points = new ArrayList<>();
      JsonNode values =
          s.has("values") ? s.path("values") : json.createArrayNode().add(s.path("value"));
      for (JsonNode v : values) {
        double value = parse(v.path(1).asString(""));
        if (Double.isFinite(value)) points.add(new double[] {v.path(0).asDouble(), value});
      }
      series.add(new Series(labels, points));
    }
    return series;
  }

  private static double parse(String value) {
    try {
      return Double.parseDouble(value);
    } catch (NumberFormatException e) {
      return Double.NaN;
    }
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }
}
