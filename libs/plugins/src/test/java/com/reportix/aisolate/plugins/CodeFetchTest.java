package com.reportix.aisolate.plugins;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** What the conformance cases leave out: the retry, and a pod with code but no backend. */
class CodeFetchTest {

  private static final byte[] JAR = "jar bytes".getBytes(StandardCharsets.UTF_8);

  @TempDir Path dir;

  @ParameterizedTest
  @ValueSource(ints = {503, 429})
  void givenBackendBusyOnce_whenCodeFetched_thenRetriedAndLoaded(int busy) throws IOException {
    // Arrange
    AtomicInteger calls = new AtomicInteger();
    HttpServer server = serve(() -> calls.incrementAndGet() == 1 ? busy : 200);
    Path tokenFile = Files.writeString(dir.resolve("token"), "t");

    // Act
    Path fetched =
        CodeFetch.fetch(
            url(server), JarDigests.of(JAR), tokenFile, dir.resolve("code"), Duration.ofMillis(1));
    server.stop(0);

    // Assert
    assertThat(fetched).hasBinaryContent(JAR);
    assertThat(calls).hasValue(2);
  }

  @Test
  void givenBackendAlwaysFailing_whenCodeFetched_thenRefusedAfterEveryAttempt() throws IOException {
    // Arrange
    AtomicInteger calls = new AtomicInteger();
    HttpServer server =
        serve(
            () -> {
              calls.incrementAndGet();
              return 500;
            });
    Path tokenFile = Files.writeString(dir.resolve("token"), "t");

    // Act
    Throwable thrown =
        catchThrowable(
            () ->
                CodeFetch.fetch(
                    url(server),
                    JarDigests.of(JAR),
                    tokenFile,
                    dir.resolve("code"),
                    Duration.ofMillis(1)));
    server.stop(0);

    // Assert
    assertThat(thrown).hasMessageContaining("answered 500, 6 attempts");
    assertThat(calls).hasValue(CodeFetch.ATTEMPTS);
  }

  @Test
  void givenDigestWithoutUrl_whenResolved_thenRefusedAsNeedingTheBackend() {
    // Arrange / Act
    Throwable thrown =
        catchThrowable(() -> CodeFetch.resolve("", JarDigests.of(JAR), "/no/token", "", ""));

    // Assert
    assertThat(thrown)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("no AISOLATE_CODE_URL")
        .hasMessageContaining("headless=false");
  }

  @Test
  void givenNoDigest_whenResolved_thenThePluginsDirectoryAsIs() {
    // Arrange / Act
    CodeFetch.Code code = CodeFetch.resolve("", "", "", "/plugins", "sha256:x");

    // Assert
    assertThat(code).isEqualTo(new CodeFetch.Code("/plugins", "sha256:x"));
  }

  private static HttpServer serve(java.util.function.IntSupplier status) throws IOException {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          int answer = status.getAsInt();
          byte[] body = answer == 200 ? JAR : new byte[0];
          exchange.sendResponseHeaders(answer, body.length == 0 ? -1 : body.length);
          try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
          }
        });
    server.start();
    return server;
  }

  private static String url(HttpServer server) {
    return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/code/s/a/v1/d";
  }
}
