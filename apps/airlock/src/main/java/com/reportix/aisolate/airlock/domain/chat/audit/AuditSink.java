package com.reportix.aisolate.airlock.domain.chat.audit;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;

/**
 * Hands every audit line to the backend's ingest, in order, and keeps what is not yet taken in a
 * bounded buffer on the pod's disk (an emptyDir, so a container restart keeps it): one file per
 * line, named by its sequence. One sender sends the oldest, retries with backoff while the backend
 * is away, and deletes it once taken. When the buffer is full, {@link #accepting()} says so and,
 * failing closed, the airlock admits no new question; an exchange admitted is always kept.
 */
@Slf4j
public class AuditSink implements AutoCloseable {

  /** No ingest configured (a headless install): the line goes to stdout alone. */
  public static final AuditSink OFF =
      new AuditSink(null, null, null, 0, false, Duration.ZERO, Duration.ZERO);

  private static final Duration DRAIN = Duration.ofSeconds(10);

  private final URI url;
  private final Path tokenFile;
  private final Path dir;
  private final long maxBytes;
  private final boolean failClosed;
  private final Duration firstBackoff;
  private final Duration maxBackoff;
  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

  private final AtomicLong bytes = new AtomicLong();
  private final Semaphore wake = new Semaphore(0);
  private long next;
  private volatile boolean closing;
  private Thread sender;

  /**
   * @param url the backend's ingest; null for {@link #OFF}
   * @param tokenFile the airlock's ingest token, read for every send so a new one is picked up
   * @param maxBytes the buffer's bound, checked when a question is admitted
   */
  public AuditSink(
      URI url,
      Path tokenFile,
      Path dir,
      long maxBytes,
      boolean failClosed,
      Duration firstBackoff,
      Duration maxBackoff) {
    this.url = url;
    this.tokenFile = tokenFile;
    this.dir = dir;
    this.maxBytes = maxBytes;
    this.failClosed = failClosed;
    this.firstBackoff = firstBackoff;
    this.maxBackoff = maxBackoff;
  }

  public boolean enabled() {
    return url != null;
  }

  /** Picks up what an earlier start left, then starts the sender. */
  public void start() throws IOException {
    if (!enabled()) return;
    Files.createDirectories(dir);
    try (Stream<Path> files = Files.list(dir)) {
      for (Path file : files.toList()) {
        String name = file.getFileName().toString();
        if (name.endsWith(".tmp")) {
          Files.delete(file); // a write that never finished: its exchange never answered either
        } else if (name.endsWith(".json")) {
          next = Math.max(next, sequenceOf(name) + 1);
          bytes.addAndGet(Files.size(file));
        }
      }
    }
    sender = Thread.ofVirtual().name("audit-sink").start(this::run);
  }

  /** Room for another exchange; always without an ingest, or when not failing closed. */
  public boolean accepting() {
    return !enabled() || !failClosed || bytes.get() < maxBytes;
  }

  public long bufferedBytes() {
    return bytes.get();
  }

  /**
   * Keeps the line for the sender. One at a time, so a line is on disk before the next sequence is.
   *
   * @return false when it was dropped: the buffer is full and the sink does not fail closed, or the
   *     disk refused it
   */
  public synchronized boolean offer(String line) {
    if (!enabled()) return true;
    if (!failClosed && bytes.get() >= maxBytes) return false;
    byte[] data = line.getBytes(StandardCharsets.UTF_8);
    long sequence = next++;
    Path tmp = dir.resolve(sequence + ".tmp");
    try {
      Files.write(tmp, data);
      Files.move(tmp, dir.resolve(nameOf(sequence)), StandardCopyOption.ATOMIC_MOVE);
    } catch (IOException e) {
      log.error("audit line {} could not be buffered", sequence, e);
      return false;
    }
    bytes.addAndGet(data.length);
    wake.release();
    return true;
  }

  /** Sends what it can within {@link #DRAIN}: a redeploy replaces the pod and its emptyDir. */
  @Override
  public void close() throws InterruptedException {
    if (sender == null) return;
    closing = true;
    wake.release();
    sender.join(DRAIN);
    sender.interrupt();
    if (bytes.get() > 0) {
      log.error("audit trail: {} bytes of lines not handed over at shutdown", bytes.get());
    }
  }

  private enum Delivery {
    TAKEN,
    REFUSED,
    RETRY
  }

  private void run() {
    Duration backoff = firstBackoff;
    try {
      while (true) {
        Optional<Path> oldest = oldest();
        if (oldest.isEmpty()) {
          if (closing) return;
          wake.tryAcquire(1, TimeUnit.SECONDS);
          continue;
        }
        Path file = oldest.get();
        Delivery delivery = deliver(file);
        if (delivery == Delivery.RETRY) {
          if (closing) return;
          Thread.sleep(backoff);
          backoff = backoff.multipliedBy(2);
          if (backoff.compareTo(maxBackoff) > 0) backoff = maxBackoff;
          continue;
        }
        long size = Files.size(file);
        Files.delete(file);
        bytes.addAndGet(-size);
        backoff = firstBackoff;
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (IOException e) {
      log.error("audit sink stopped: its buffer cannot be read", e);
    }
  }

  private Delivery deliver(Path file) throws IOException, InterruptedException {
    try {
      HttpResponse<String> response =
          http.send(
              HttpRequest.newBuilder(url)
                  .timeout(Duration.ofSeconds(10))
                  .header("Authorization", "Bearer " + Files.readString(tokenFile).strip())
                  .header("Content-Type", "application/json")
                  .POST(HttpRequest.BodyPublishers.ofFile(file))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      int status = response.statusCode();
      if (status == 200 || status == 201) return Delivery.TAKEN;
      if (status == 400) {
        // Never acceptable, so never retried: it would hold back every line after it.
        log.error("audit line {} refused by the backend: {}", file.getFileName(), response.body());
        return Delivery.REFUSED;
      }
      log.warn("audit ingest answered {}; retrying", status);
    } catch (IOException e) {
      log.warn("audit ingest unreachable ({}); retrying", e.getMessage());
    }
    return Delivery.RETRY;
  }

  // Lists the directory per send; an index in memory if buffers grow to many thousands.
  private Optional<Path> oldest() throws IOException {
    try (Stream<Path> files = Files.list(dir)) {
      return files.filter(f -> f.getFileName().toString().endsWith(".json")).min(Path::compareTo);
    }
  }

  /** Zero-padded, so the names sort as the sequence does. */
  private static String nameOf(long sequence) {
    return String.format("%019d.json", sequence);
  }

  private static long sequenceOf(String name) {
    return Long.parseLong(name.substring(0, name.length() - ".json".length()));
  }
}
