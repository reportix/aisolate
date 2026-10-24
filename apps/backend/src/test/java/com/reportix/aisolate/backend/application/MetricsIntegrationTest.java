package com.reportix.aisolate.backend.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.reportix.aisolate.backend.BackendApplication;
import com.reportix.aisolate.backend.support.BackendTestConfig;
import com.reportix.aisolate.backend.support.OwnDatabase;
import com.reportix.aisolate.identity.testing.Http;
import com.reportix.aisolate.identity.testing.Tokens;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Monitoring's charts end to end, against a stand-in store that answers as Prometheus does and
 * keeps every query it was asked: who reads which spaces, and that only the backend's queries reach
 * it.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = BackendApplication.class,
    properties = "aisolate.test.database=metrics")
@ContextConfiguration(initializers = {BackendTestConfig.class, OwnDatabase.class})
class MetricsIntegrationTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Queue<String> ASKED = new ConcurrentLinkedQueue<>();
  private static final HttpServer PROMETHEUS = prometheus();

  @DynamicPropertySource
  static void metricsStore(DynamicPropertyRegistry registry) {
    registry.add(
        "aisolate.metrics.query-url",
        () -> "http://localhost:" + PROMETHEUS.getAddress().getPort());
  }

  @LocalServerPort int port;

  @BeforeEach
  void forgetQueries() {
    ASKED.clear();
  }

  @ParameterizedTest
  @CsvSource({
    // The admin: every space, no space matcher
    "andrew, , 200, ''",
    // A developer of hr-acme: their space alone, whatever they ask for
    "jasmine, , 200, 'space=~\"hr-acme\"'",
    "jasmine, hr-acme, 200, 'space=\"hr-acme\"'",
    "jasmine, other, 403, ",
    "auditor, , 403, "
  })
  void givenAUser_whenReadingTheCharts_thenTheirSpacesAloneAreAsked(
      String username, String space, int status, String matcher) {
    // Arrange
    String user = Tokens.forOperator(username, "password");

    // Act
    HttpResponse<String> response =
        send("/v1/metrics?period=1h" + (space == null ? "" : "&space=" + space), user);

    // Assert
    assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
    if (status != 200) {
      assertThat(ASKED).isEmpty();
      return;
    }
    assertThat(ASKED).hasSize(5);
    if (matcher.isEmpty()) assertThat(ASKED).noneMatch(q -> q.contains("space="));
    else assertThat(ASKED).allMatch(q -> q.contains(matcher));
  }

  @Test
  void givenAnAirlockThatIsNoId_whenCharted_then400AndNothingAsked() {
    // Arrange
    String injected = URLEncoder.encode("hr\"} or vector(1) #", StandardCharsets.UTF_8);

    // Act
    HttpResponse<String> response = send("/v1/metrics?airlock=" + injected, admin());

    // Assert
    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(ASKED).isEmpty();
  }

  @Test
  void givenTheStoresSeries_whenCharted_thenTheLinesAndTheBarsByWhoseTheyAre() {
    // Arrange / Act
    JsonNode charts = JSON.readTree(send("/v1/metrics?period=24h", admin()).body());

    // Assert
    JsonNode line = charts.path("exchanges_per_minute").path(0);
    assertThat(line.path("space").asString()).isEqualTo("hr-acme");
    assertThat(line.path("airlock").asString()).isEqualTo("hr");
    assertThat(line.path("points").path(1).path(1).asDouble()).isEqualTo(1.5);
    assertThat(charts.path("step_seconds").asLong()).isEqualTo(720);
    JsonNode bar = charts.path("refusals").path(0);
    assertThat(bar.path("name").asString()).isEqualTo("read_records");
    assertThat(bar.path("count").asDouble()).isEqualTo(3);
  }

  // Helpers

  private HttpResponse<String> send(String path, String token) {
    return Http.send(
        Http.bearer(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)), token)
            .GET()
            .build());
  }

  private static String admin() {
    return Tokens.forOperator("andrew", "password");
  }

  /** A range query answers one line, an instant query one count; both of hr-acme's hr. */
  private static HttpServer prometheus() {
    try {
      HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
      server.createContext(
          "/api/v1/",
          exchange -> {
            String form =
                new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            for (String pair : form.split("&")) {
              if (pair.startsWith("query=")) {
                ASKED.add(URLDecoder.decode(pair.substring(6), StandardCharsets.UTF_8));
              }
            }
            String labels =
                "{\"space\":\"hr-acme\",\"airlock\":\"hr\",\"reason\":\"read_records\",\"tool\":\"read_records\"}";
            String body =
                exchange.getRequestURI().getPath().endsWith("query_range")
                    ? "{\"status\":\"success\",\"data\":{\"resultType\":\"matrix\",\"result\":[{\"metric\":"
                        + labels
                        + ",\"values\":[[1792399400,\"0.5\"],[1792399415,\"1.5\"],[1792399430,\"NaN\"]]}]}}"
                    : "{\"status\":\"success\",\"data\":{\"resultType\":\"vector\",\"result\":[{\"metric\":"
                        + labels
                        + ",\"value\":[1792399430,\"3\"]}]}}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
          });
      server.start();
      return server;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
