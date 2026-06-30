package com.reportix.aisolate.plugins.conformance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.api.Named.named;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import com.reportix.aisolate.plugins.CodeFetch;
import com.reportix.aisolate.plugins.JarDigests;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** {@code conformance/cases/delivery/fetch.json}: a pod's code fetched, verified or refused. */
class CodeFetchConformanceTest {

  private static final JsonNode FILE = read();

  @TempDir Path dir;

  static Stream<Arguments> cases() {
    return StreamSupport.stream(FILE.path("cases").spliterator(), false)
        .map(c -> arguments(named(c.path("id").asString(), c)));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("cases")
  void givenBackendAnswer_whenCodeFetched_thenLoadedOrRefusedAsTheCaseSays(JsonNode testCase)
      throws IOException {
    // Arrange
    byte[] artifact = FILE.path("artifact").asString().getBytes(StandardCharsets.UTF_8);
    String token = FILE.path("token").asString();
    Path tokenFile = Files.writeString(dir.resolve("token"), token + "\n");
    byte[] served = testCase.path("served").asString().getBytes(StandardCharsets.UTF_8);
    int status = testCase.path("status").asInt();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          boolean bearer =
              ("Bearer " + token).equals(exchange.getRequestHeaders().getFirst("Authorization"));
          exchange.sendResponseHeaders(bearer ? status : 401, served.length);
          try (OutputStream body = exchange.getResponseBody()) {
            body.write(served);
          }
        });
    server.start();
    String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/code/s/a/v1/d";
    Path target = dir.resolve("code");

    // Act
    Path[] fetched = new Path[1];
    Throwable thrown =
        catchThrowable(
            () -> fetched[0] = CodeFetch.fetch(url, JarDigests.of(artifact), tokenFile, target));
    server.stop(0);

    // Assert
    JsonNode expect = testCase.path("expect");
    if (expect.path("loaded").asBoolean()) {
      assertThat(thrown).isNull();
      assertThat(fetched[0]).hasBinaryContent(artifact);
    } else {
      assertThat(thrown)
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining(expect.path("error").asString());
      assertThat(target.resolve(CodeFetch.FILE)).doesNotExist();
    }
  }

  private static JsonNode read() {
    String dir = System.getProperty("aisolate.conformance.dir");
    if (dir == null) {
      throw new IllegalStateException("the build passes aisolate.conformance.dir");
    }
    return JsonMapper.shared().readTree(Path.of(dir, "delivery", "fetch.json").toFile());
  }
}
