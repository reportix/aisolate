package com.reportix.aisolate.airlock.domain.chat.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** The pod's side of the audit trail: in order, kept while the backend is away, bounded. */
class AuditSinkTest {

  @TempDir Path dir;

  private final List<String> received = new CopyOnWriteArrayList<>();
  private final List<String> tokens = new CopyOnWriteArrayList<>();
  private final AtomicInteger failuresLeft = new AtomicInteger();
  private volatile int failWith = 503;
  private HttpServer backend;
  private AuditSink sink;

  @AfterEach
  void stop() throws InterruptedException {
    if (sink != null) sink.close();
    if (backend != null) backend.stop(0);
  }

  @Test
  void givenTheBackendFailsTwice_whenThreeLinesOffered_thenEachArrivesOnceInOrder()
      throws IOException {
    // Arrange
    startBackend();
    failuresLeft.set(2);
    sink = sink(1024 * 1024, true);
    sink.start();

    // Act
    List.of("a", "b", "c").forEach(sink::offer);

    // Assert
    await().atMost(Duration.ofSeconds(10)).until(() -> received.size() == 3);
    assertThat(received).containsExactly("a", "b", "c");
    assertThat(tokens).containsOnly("Bearer the-ingest-token");
    await().atMost(Duration.ofSeconds(5)).until(() -> sink.bufferedBytes() == 0);
  }

  @Test
  void givenTheBackendRefusesALineAsNoAuditLine_whenSent_thenItIsDroppedAndTheNextFollows()
      throws IOException {
    // Arrange
    startBackend();
    failuresLeft.set(1);
    failWith = 400;
    sink = sink(1024 * 1024, true);
    sink.start();

    // Act
    List.of("bad", "good").forEach(sink::offer);

    // Assert: a 400 is never retried, it would hold back every line behind it
    await().atMost(Duration.ofSeconds(10)).until(() -> received.contains("good"));
    assertThat(received).containsExactly("good");
  }

  @Test
  void givenLinesAnEarlierStartLeft_whenStarted_thenTheyAreSentFirstInOrder() throws IOException {
    // Arrange: the container restarted, its emptyDir kept the buffer
    AuditSink before = sink(1024 * 1024, true);
    before.start();
    List.of("old-1", "old-2").forEach(before::offer);
    startBackend();
    sink = sink(1024 * 1024, true);

    // Act
    sink.start();
    sink.offer("new");

    // Assert
    await().atMost(Duration.ofSeconds(10)).until(() -> received.size() == 3);
    assertThat(received).containsExactly("old-1", "old-2", "new");
  }

  @ParameterizedTest
  @CsvSource({"true, false, true", "false, true, false"})
  void givenTheBackendAwayAndTheBufferFull_whenAsked_thenFailsClosedOrDrops(
      boolean failClosed, boolean accepting, boolean keptOverTheBound) throws IOException {
    // Arrange: nothing listens, so nothing drains
    sink = sink(10, failClosed);
    sink.start();
    sink.offer("0123456789");

    // Act
    boolean admits = sink.accepting();
    boolean kept = sink.offer("one more");

    // Assert: failing closed, the exchange already admitted is still kept
    assertThat(admits).isEqualTo(accepting);
    assertThat(kept).isEqualTo(keptOverTheBound);
  }

  @Test
  void givenNoIngest_whenAsked_thenAlwaysAccepting() {
    // Arrange / Act / Assert
    assertThat(AuditSink.OFF.accepting()).isTrue();
    assertThat(AuditSink.OFF.offer("line")).isTrue();
  }

  private AuditSink sink(long maxBytes, boolean failClosed) throws IOException {
    Path token = dir.resolve("token");
    Files.writeString(token, "the-ingest-token\n");
    int port = backend == null ? 9 : backend.getAddress().getPort();
    return new AuditSink(
        URI.create("http://127.0.0.1:" + port + "/v1/audit/events"),
        token,
        dir.resolve("buffer"),
        maxBytes,
        failClosed,
        Duration.ofMillis(20),
        Duration.ofMillis(100));
  }

  private void startBackend() throws IOException {
    backend = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    backend.createContext(
        "/v1/audit/events",
        exchange -> {
          String body =
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
          tokens.add(exchange.getRequestHeaders().getFirst("Authorization"));
          int status = failuresLeft.getAndDecrement() > 0 ? failWith : 201;
          if (status == 201) received.add(body);
          exchange.sendResponseHeaders(status, -1);
          exchange.close();
        });
    backend.start();
  }
}
