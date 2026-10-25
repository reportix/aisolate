package com.reportix.aisolate.plugins;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;

/**
 * How a pod gets its airlock's code (docs/configuration.md, "The code"): fetched at start from the
 * backend's cluster-internal {@code AISOLATE_CODE_URL} with the airlock's code token, written to
 * the pod's scratch directory and its sha256 checked against {@code AISOLATE_CODE_DIGEST}. Any
 * refusal fails the start; the pod is not ready before its code is loaded.
 */
@Slf4j
public final class CodeFetch {

  /** The fetched jar's name in its directory. */
  public static final String FILE = "aisolate-code.jar";

  static final int ATTEMPTS = 6;

  /**
   * The directory a pod loads its jars from, and the digests pinning them.
   *
   * @param dir blank for none
   */
  public record Code(String dir, String digests) {}

  /**
   * The airlock's code fetched into {@code <java.io.tmpdir>/aisolate/code} when a digest names it;
   * otherwise {@code pluginsDir} as is, the input of local runs and tests.
   *
   * @throws IllegalStateException the fetch was refused or the bytes are not the digest's
   */
  public static Code resolve(
      String url, String digest, String tokenFile, String pluginsDir, String pluginDigests) {
    if (digest == null || digest.isBlank()) {
      return new Code(pluginsDir, pluginDigests);
    }
    if (url == null || url.isBlank()) {
      throw new IllegalStateException(
          "the airlock has code (AISOLATE_CODE_DIGEST) but no AISOLATE_CODE_URL to fetch it from:"
              + " code needs the backend (headless=false)");
    }
    Path dir = Path.of(System.getProperty("java.io.tmpdir"), "aisolate", "code");
    fetch(url, digest, Path.of(tokenFile == null ? "" : tokenFile), dir);
    return new Code(dir.toString(), digest);
  }

  /**
   * {@code dir/aisolate-code.jar}: the body of {@code GET url}, whose sha256 is {@code digest}.
   * Retried with backoff (1, 2, 4, 8, 16 s) on a connection failure, a 5xx or a 429; any other
   * answer refuses at once. A refusal leaves no file.
   */
  public static Path fetch(String url, String digest, Path tokenFile, Path dir) {
    return fetch(url, digest, tokenFile, dir, Duration.ofSeconds(1));
  }

  static Path fetch(String url, String digest, Path tokenFile, Path dir, Duration firstBackoff) {
    String token;
    try {
      token = Files.readString(tokenFile).strip();
    } catch (IOException unreadable) {
      throw new IllegalStateException(
          "the code token cannot be read from AISOLATE_CODE_TOKEN_FILE " + tokenFile, unreadable);
    }
    Path file = dir.resolve(FILE);
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(url))
            .header("Authorization", "Bearer " + token)
            // Until the headers: the body itself may take as long as its size needs.
            .timeout(Duration.ofSeconds(60))
            .GET()
            .build();
    try (HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()) {
      for (int attempt = 1; ; attempt++) {
        String failure;
        try {
          HttpResponse<InputStream> response =
              http.send(request, HttpResponse.BodyHandlers.ofInputStream());
          try (InputStream body = response.body()) {
            int status = response.statusCode();
            if (status == 200) {
              return write(body, file, url, digest);
            }
            failure = "fetching the code from " + url + " answered " + status;
            if (status < 500 && status != 429) {
              throw new IllegalStateException(failure);
            }
          }
        } catch (IOException broken) {
          failure = "fetching the code from " + url + " failed: " + broken;
        }
        if (attempt == ATTEMPTS) {
          throw new IllegalStateException(failure + ", " + ATTEMPTS + " attempts");
        }
        log.warn("{}; attempt {} of {}", failure, attempt, ATTEMPTS);
        Thread.sleep(firstBackoff.multipliedBy(1L << (attempt - 1)));
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("fetching the code from " + url + " was interrupted");
    } catch (RuntimeException refused) {
      deleteQuietly(file);
      throw refused;
    }
  }

  /** Hashed as it streams to disk: no copy in memory, however large. */
  private static Path write(InputStream body, Path file, String url, String digest)
      throws IOException {
    Files.createDirectories(file.getParent());
    MessageDigest sha = JarDigests.sha256();
    try (InputStream in = new DigestInputStream(body, sha)) {
      Files.copy(in, file, StandardCopyOption.REPLACE_EXISTING);
    }
    String fetched = JarDigests.format(sha);
    if (!fetched.equalsIgnoreCase(digest.strip())) {
      throw new IllegalStateException(
          "the code fetched from "
              + url
              + " has digest "
              + fetched
              + ", not AISOLATE_CODE_DIGEST "
              + digest.strip());
    }
    log.info("fetched the code {} from {}: {} bytes", fetched, url, Files.size(file));
    return file;
  }

  private static void deleteQuietly(Path file) {
    try {
      Files.deleteIfExists(file);
    } catch (IOException ignored) {
      // The start fails anyway; the scratch directory goes with the pod.
    }
  }

  private CodeFetch() {}
}
