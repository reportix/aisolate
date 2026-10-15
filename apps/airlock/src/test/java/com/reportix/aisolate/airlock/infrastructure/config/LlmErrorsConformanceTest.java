package com.reportix.aisolate.airlock.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.api.Named.named;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import com.reportix.aisolate.sdk.Llm;
import com.reportix.aisolate.sdk.LlmException;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.observation.ObservationRegistry;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code conformance/cases/llm/errors.json}: the pod's LLM catalog from its env, and a handler's
 * {@code llm(id)} on it against a stub model server, shared with the Python airlock.
 */
class LlmErrorsConformanceTest {

  private static final JsonMapper JSON =
      JsonMapper.builder().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();

  private static final JsonNode FILE = read();

  static Stream<Arguments> starts() {
    return cases(false);
  }

  static Stream<Arguments> calls() {
    return cases(true);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("starts")
  void givenPodEnv_whenTheCatalogIsRead_thenItsLlmsOrTheStartError(JsonNode c) {
    // Arrange
    Map<String, String> env = env(c, null);
    LlmCatalog[] read = new LlmCatalog[1];

    // Act
    Throwable thrown = catchThrowable(() -> read[0] = LlmCatalog.read(env::get));

    // Assert
    JsonNode expect = c.path("expect");
    if (expect.has("start_error")) {
      assertThat(thrown)
          .isInstanceOf(IllegalStateException.class)
          .hasMessage(expect.path("start_error").asString());
    } else {
      assertThat(thrown).isNull();
      assertThat(read[0].main()).isEqualTo(expect.path("main").asString());
      assertThat(JSON.valueToTree(read[0].entries()).equals(expect.path("llms"))).isTrue();
    }
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("calls")
  void givenStubServer_whenCodeCallsAnLlm_thenTheRequestAndItsResultOrError(JsonNode c) {
    // Arrange
    List<Request> received = new ArrayList<>();
    HttpServer server = serve(c.path("server"), received);
    JsonNode call = c.path("call");
    Object[] returned = new Object[1];
    try {
      Llms llms =
          Llms.connect(
              LlmCatalog.read(env(c, "http://127.0.0.1:" + server.getAddress().getPort())::get),
              RestClient.builder(),
              ObservationRegistry.NOOP,
              new RetryTemplate(RetryPolicy.builder().maxRetries(0).build()),
              Duration.ofSeconds(10));

      // Act
      Throwable thrown = catchThrowable(() -> returned[0] = invoke(llms, call));

      // Assert
      JsonNode expect = c.path("expect");
      JsonNode request = expect.path("request");
      if (request.isNull()) {
        assertThat(received).isEmpty();
      } else {
        assertThat(received).hasSize(1);
        Request sent = received.getFirst();
        assertThat(sent.path()).isEqualTo(request.path("path").asString());
        JsonNode body = JSON.readTree(sent.body());
        request
            .path("body")
            .properties()
            .forEach(
                field ->
                    assertThat(body.path(field.getKey()))
                        .as(field.getKey())
                        .isEqualTo(field.getValue()));
        request
            .path("headers")
            .properties()
            .forEach(
                header ->
                    assertThat(sent.headers().get(header.getKey()))
                        .as(header.getKey())
                        .isEqualTo(
                            header.getValue().isNull() ? null : header.getValue().asString()));
      }
      if (expect.has("error")) {
        assertThat(thrown)
            .isInstanceOf(LlmException.class)
            .hasMessage(expect.path("error").asString());
      } else if (expect.has("failure")) {
        assertThat(thrown).isNotNull().isNotInstanceOf(LlmException.class);
      } else {
        assertThat(thrown).isNull();
        assertThat(asJson(returned[0])).isEqualTo(JSON.writeValueAsString(expect.path("result")));
      }
    } finally {
      server.stop(0);
    }
  }

  /** What the stub received; header names lower-case, the first value of each. */
  private record Request(String path, String body, Map<String, String> headers) {}

  /** A vector's floats as the decimals the case writes: 1.0f is 1, as in JSON. */
  private static String asJson(Object result) {
    if (result instanceof float[] vector) {
      List<Object> numbers = new ArrayList<>();
      for (float value : vector) {
        numbers.add(new java.math.BigDecimal(Float.toString(value)).stripTrailingZeros());
      }
      return JSON.writeValueAsString(numbers);
    }
    if (result instanceof List<?> vectors) {
      return "["
          + String.join(",", vectors.stream().map(LlmErrorsConformanceTest::asJson).toList())
          + "]";
    }
    return JSON.writeValueAsString(result);
  }

  private static Object invoke(Llms llms, JsonNode call) {
    Llm llm = llms.get(call.path("llm").asString());
    JsonNode input = call.path("input");
    return switch (call.path("op").asString()) {
      case "chat" -> llm.chat(input.asString());
      case "embed" ->
          input.isArray()
              ? llm.embed(input.valueStream().map(JsonNode::asString).toList())
              : llm.embed(input.asString());
      default -> throw new IllegalArgumentException(call.path("op").asString());
    };
  }

  /** Answers every request with the case's status and body, and records what it received. */
  private static HttpServer serve(JsonNode answer, List<Request> received) {
    try {
      HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext(
          "/",
          exchange -> {
            Map<String, String> headers = new HashMap<>();
            exchange
                .getRequestHeaders()
                .forEach((name, values) -> headers.put(name.toLowerCase(), values.getFirst()));
            received.add(
                new Request(
                    exchange.getRequestURI().getPath(),
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8),
                    headers));
            byte[] body =
                answer.isNull() ? new byte[0] : JSON.writeValueAsBytes(answer.path("body"));
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(
                answer.isNull() ? 500 : answer.path("status").asInt(), body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
          });
      server.start();
      return server;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** The file's env with the case's merged over it; every URL on the stub, its path kept. */
  private static Map<String, String> env(JsonNode c, String stub) {
    Map<String, String> env = new HashMap<>();
    FILE.path("env").properties().forEach(e -> env.put(e.getKey(), e.getValue().asString()));
    c.path("env")
        .properties()
        .forEach(
            e -> {
              if (e.getValue().isNull()) {
                env.remove(e.getKey());
              } else {
                env.put(e.getKey(), e.getValue().asString());
              }
            });
    if (stub != null) {
      env.replaceAll(
          (name, value) ->
              name.endsWith("_URL") ? value.replaceFirst("^https?://[^/]+", stub) : value);
    }
    return env;
  }

  private static Stream<Arguments> cases(boolean calls) {
    List<Arguments> cases = new ArrayList<>();
    FILE.path("cases")
        .forEach(
            c -> {
              if (c.has("call") == calls) {
                cases.add(arguments(named(c.path("id").asString(), c)));
              }
            });
    return cases.stream();
  }

  private static JsonNode read() {
    String dir = System.getProperty("aisolate.conformance.dir");
    if (dir == null) {
      throw new IllegalStateException("the build passes aisolate.conformance.dir");
    }
    return JSON.readTree(Path.of(dir, "llm", "errors.json").toFile());
  }
}
