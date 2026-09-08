package com.reportix.aisolate.cli;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Plays the browser for a login: reads the authorize URL the CLI opens and calls its redirect URI
 * with {@code answer}, where {@code {state}} stands for the state the CLI sent.
 */
final class FakeBrowser implements Consumer<String> {

  private final HttpClient http = HttpClient.newHttpClient();
  private final String answer;

  /** The authorize URL's query, as the provider would read it. */
  volatile Map<String, String> authorize;

  private volatile String page;

  FakeBrowser(String answer) {
    this.answer = answer;
  }

  @Override
  public void accept(String url) {
    authorize = query(URI.create(url).getRawQuery());
    page =
        get(authorize.get("redirect_uri") + "?" + answer.replace("{state}", authorize.get("state")))
            .body();
  }

  /**
   * The page the CLI answered the redirect with. The login opens the browser from its key reader,
   * so it may return before this fake has its page: wait for it.
   */
  String page() {
    long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
    while (page == null && System.nanoTime() < deadline) {
      Thread.onSpinWait();
    }
    return page;
  }

  HttpResponse<String> get(String url) {
    try {
      return http.send(
          HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.ofString());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  static Map<String, String> query(String raw) {
    Map<String, String> query = new HashMap<>();
    for (String pair : raw.split("&")) {
      String[] kv = pair.split("=", 2);
      query.put(
          URLDecoder.decode(kv[0], StandardCharsets.UTF_8),
          kv.length > 1 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "");
    }
    return query;
  }
}
