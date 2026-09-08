package com.reportix.aisolate.cli;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;

/**
 * The browser half of {@code aisolate login}: the authorization code flow with PKCE (RFC 7636) and
 * a loopback redirect (RFC 8252). The CLI listens on 127.0.0.1 for the one redirect, the provider's
 * own page takes the password, and the code is worth nothing without the verifier this process
 * keeps.
 */
@RequiredArgsConstructor
final class BrowserLogin {

  static final Duration TIMEOUT = Duration.ofMinutes(5);

  private static final String CALLBACK = "/callback";
  private static final SecureRandom RANDOM = new SecureRandom();
  private static final Base64.Encoder BASE64URL = Base64.getUrlEncoder().withoutPadding();

  private final Auth auth;
  private final Console console;

  /** Opens a URL in the browser; tests play the browser instead. */
  private final Consumer<String> browser;

  /** The keyboard: Enter opens the browser; tests type instead. */
  private final InputStream keys;

  private final Duration timeout;

  /** A sign-in that ends in a session: a deploy in a terminal takes one when it has none. */
  @FunctionalInterface
  interface SignIn {
    Session signIn(Auth auth) throws IOException;
  }

  /** How the redirect ended: a session, or why the login failed. */
  private record Callback(Session session, String failure) {}

  Session login() throws IOException {
    // An unreachable endpoint fails before a browser opens.
    Auth.Endpoints endpoints = auth.discover();
    String verifier = generateRandom(32);
    String state = generateRandom(16);
    CompletableFuture<Callback> callback = new CompletableFuture<>();
    // A literal, not "localhost": the redirect URI names 127.0.0.1, the listener must be there.
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    String redirectUri = "http://127.0.0.1:" + server.getAddress().getPort() + CALLBACK;
    server.createContext(
        CALLBACK, exchange -> answer(exchange, state, verifier, redirectUri, callback));
    server.start();
    try {
      String url =
          buildAuthorizeUrl(
              endpoints.authorizationUrl(), redirectUri, state, computeChallenge(verifier));
      console.println("");
      console.offerChoice("Sign in with your account", url);
      console.println("   Press Enter to sign in · Ctrl+C to cancel");
      // Enter opens the browser, a click on the choice opens it too: either ends at the redirect.
      // Never interrupted: a read on the terminal is not interruptible, and a late Enter is a
      // no-op.
      Thread.ofVirtual().start(() -> openOnEnter(url, callback));
      Callback answer = await(callback);
      if (answer.failure() != null) {
        throw new IllegalStateException(answer.failure());
      }
      return answer.session();
    } finally {
      server.stop(0);
    }
  }

  // End of input is nobody typing (a pipe): open at once. A click may have finished it first.
  private void openOnEnter(String url, CompletableFuture<Callback> callback) {
    try {
      int key;
      do {
        key = keys.read();
      } while (key != '\n' && key != -1);
    } catch (IOException noKeyboard) {
      // open at once, as at the end of input
    }
    if (callback.isDone()) {
      return;
    }
    try {
      browser.accept(url);
    } catch (RuntimeException cannotOpen) {
      // Only now the URL itself: the wait goes on, and visiting it still completes the login.
      console.warn(
          "the browser did not open (" + cannotOpen.getMessage() + "); open this URL instead:");
      console.println("   " + url);
    }
  }

  private Callback await(CompletableFuture<Callback> callback) {
    try {
      return callback.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
      throw new IllegalStateException(
          "Login timed out after "
              + timeout.toMinutes()
              + " minutes. Run aisolate login to try again.");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted");
    } catch (ExecutionException e) {
      throw new IllegalStateException(e.getCause().getMessage());
    }
  }

  // The code is exchanged before the page is written, so the tab says what the terminal will.
  private void answer(
      HttpExchange exchange,
      String state,
      String verifier,
      String redirectUri,
      CompletableFuture<Callback> callback)
      throws IOException {
    if (!CALLBACK.equals(exchange.getRequestURI().getPath())) {
      exchange.sendResponseHeaders(404, -1);
      exchange.close();
      return;
    }
    Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
    String failure = describeFailure(query, state);
    Session session = null;
    if (failure == null) {
      try {
        session =
            auth.exchangeCode(
                query.get("code"), verifier, redirectUri, Instant.now().getEpochSecond());
      } catch (IllegalStateException refused) {
        failure = refused.getMessage();
      }
    }
    byte[] page = renderPage(failure).getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
    exchange.sendResponseHeaders(200, page.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(page);
    }
    callback.complete(new Callback(session, failure));
  }

  /** Why the redirect brings no usable code, or null when it does. */
  // State first: without it any local request could end the login and print its own text.
  private static String describeFailure(Map<String, String> query, String state) {
    if (!state.equals(query.get("state"))) {
      return "Login failed: the browser answered for another login attempt";
    }
    if (query.containsKey("error")) {
      String description = query.getOrDefault("error_description", "");
      return "Login failed: "
          + query.get("error")
          + (description.isEmpty() ? "" : " (" + description + ")");
    }
    return query.getOrDefault("code", "").isEmpty()
        ? "Login failed: the provider sent no code"
        : null;
  }

  // The JDK server answers a malformed URI with 400 itself, so the escapes here are valid.
  private static Map<String, String> parseQuery(String raw) {
    Map<String, String> query = new HashMap<>();
    if (raw == null) {
      return query;
    }
    for (String pair : raw.split("&")) {
      int eq = pair.indexOf('=');
      query.putIfAbsent(
          decode(eq < 0 ? pair : pair.substring(0, eq)),
          eq < 0 ? "" : decode(pair.substring(eq + 1)));
    }
    return query;
  }

  private static String decode(String value) {
    return URLDecoder.decode(value, StandardCharsets.UTF_8);
  }

  static String buildAuthorizeUrl(
      String authorizationUrl, String redirectUri, String state, String challenge) {
    Map<String, String> query = new LinkedHashMap<>();
    query.put("client_id", Auth.CLIENT_ID);
    query.put("response_type", "code");
    query.put("redirect_uri", redirectUri);
    query.put("scope", Auth.SCOPE);
    query.put("state", state);
    query.put("code_challenge", challenge);
    query.put("code_challenge_method", "S256");
    return authorizationUrl + (authorizationUrl.contains("?") ? "&" : "?") + Auth.encodeForm(query);
  }

  /** {@code base64url(SHA-256(verifier))}: the S256 challenge. */
  static String computeChallenge(String verifier) {
    try {
      return BASE64URL.encodeToString(
          MessageDigest.getInstance("SHA-256")
              .digest(verifier.getBytes(StandardCharsets.US_ASCII)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }

  private static String generateRandom(int bytes) {
    byte[] value = new byte[bytes];
    RANDOM.nextBytes(value);
    return BASE64URL.encodeToString(value);
  }

  /** The tab the browser lands on, in the AIsolate palette; {@code failure} null is success. */
  static String renderPage(String failure) {
    boolean ok = failure == null;
    String title = ok ? "Signed in to AIsolate" : "Sign-in failed";
    String text =
        ok
            ? "You can close this tab and return to the terminal."
            : escape(failure) + ". Return to the terminal.";
    return """
    <!doctype html>
    <html lang="en">
    <head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>%s</title>
    <style>
      body { margin: 0; min-height: 100vh; display: grid; place-items: center; padding: 16px;
             box-sizing: border-box; background: #F3F5F8; color: #121417;
             font: 16px/1.5 system-ui, -apple-system, "Segoe UI", sans-serif; }
      main { background: #FFFFFF; border: 1px solid #E2E6EC; border-radius: 10px;
             padding: 32px 40px; max-width: 440px; }
      h1 { font-size: 20px; margin: 0 0 8px; }
      .mark { color: %s; margin-right: 8px; }
      p { margin: 0; color: #3A424D; }
    </style>
    </head>
    <body><main><h1><span class="mark">%s</span>%s</h1><p>%s</p></main></body>
    </html>
    """
        .formatted(title, ok ? "#1C448E" : "#B42318", ok ? "✔" : "✘", title, text);
  }

  private static String escape(String text) {
    return text.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;");
  }

  /** The platform's own opener: no AWT, so the native image needs nothing extra. */
  static void open(String url) {
    String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
    List<String> command =
        os.startsWith("mac")
            ? List.of("open", url)
            : os.startsWith("windows")
                ? List.of("rundll32", "url.dll,FileProtocolHandler", url)
                : List.of("xdg-open", url);
    try {
      new ProcessBuilder(command)
          .redirectOutput(ProcessBuilder.Redirect.DISCARD)
          .redirectError(ProcessBuilder.Redirect.DISCARD)
          .start();
    } catch (IOException e) {
      throw new IllegalStateException("cannot run " + command.getFirst() + ": " + e.getMessage());
    }
  }
}
