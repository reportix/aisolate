package com.reportix.aisolate.cli;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.MissingNode;

/**
 * Tokens for the AIsolate endpoint: the endpoint says which identity provider protects it (RFC
 * 9728), the provider says where to sign in and where tokens come from (OpenID discovery), and the
 * public client {@code aisolate-cli} gets them through the browser (BrowserLogin). Later commands
 * refresh them silently; the CLI never sees a password.
 */
@RequiredArgsConstructor
final class Auth {

  static final String CLIENT_ID = "aisolate-cli";
  // offline_access: a refresh token that outlives the browser session, so a login lasts while used.
  static final String SCOPE = "openid profile email offline_access";
  static final String NOT_LOGGED_IN = "You are not logged in. Run aisolate login to sign in.";

  /** Where the provider signs in, issues and revokes tokens; {@code revocationUrl} may be empty. */
  record Endpoints(String issuer, String authorizationUrl, String tokenUrl, String revocationUrl) {}

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final HttpClient client;
  private final String endpoint;
  private Endpoints endpoints;

  String getEndpoint() {
    return endpoint;
  }

  /** Asked once per {@code Auth}; later calls reuse the answer. */
  Endpoints discover() {
    if (endpoints == null) {
      endpoints = fetchEndpoints();
    }
    return endpoints;
  }

  private Endpoints fetchEndpoints() {
    JsonNode resource =
        getJson(
            endpoint + "/.well-known/oauth-protected-resource",
            "AIsolate at " + endpoint + " does not publish its identity provider");
    String issuer = resource.path("authorization_servers").path(0).asString("");
    if (issuer.isEmpty()) {
      throw new IllegalStateException("AIsolate at " + endpoint + " names no authorization server");
    }
    issuer = issuer.endsWith("/") ? issuer.substring(0, issuer.length() - 1) : issuer;
    parseUri(issuer, "issuer");
    refuseDowngrade(endpoint, issuer);
    JsonNode openid =
        getJson(
            issuer + "/.well-known/openid-configuration",
            "identity provider " + issuer + " does not publish its OpenID configuration");
    String authorizationUrl =
        requireUrl(openid, "authorization_endpoint", issuer, "authorization endpoint");
    String tokenUrl = requireUrl(openid, "token_endpoint", issuer, "token endpoint");
    String revocationUrl = openid.path("revocation_endpoint").asString("");
    if (!revocationUrl.isEmpty()) {
      parseUri(revocationUrl, "revocation endpoint");
      refuseDowngrade(endpoint, revocationUrl);
    }
    return new Endpoints(issuer, authorizationUrl, tokenUrl, revocationUrl);
  }

  private String requireUrl(JsonNode openid, String field, String issuer, String what) {
    String url = openid.path(field).asString("");
    if (url.isEmpty()) {
      throw new IllegalStateException("identity provider " + issuer + " names no " + what);
    }
    parseUri(url, what);
    refuseDowngrade(endpoint, url);
    return url;
  }

  /**
   * Codes and tokens only travel over https, except to a loopback provider (a local Keycloak):
   * whatever the endpoint, since a loopback endpoint may be a port-forward to a remote backend.
   */
  static void refuseDowngrade(String endpoint, String url) {
    URI target = parseUri(url, "identity provider");
    if (!"https".equalsIgnoreCase(target.getScheme()) && !isLoopback(target.getHost())) {
      throw new IllegalStateException(
          "refusing "
              + url
              + ", named by AIsolate at "
              + endpoint
              + ": the identity provider must use https (plain http is for localhost only)");
    }
  }

  // http(s) with a host only: a discovered URL reaches the OS opener and the HTTP client.
  static URI parseUri(String value, String what) {
    try {
      URI uri = URI.create(value);
      String scheme = uri.getScheme();
      if (("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
          && uri.getHost() != null) {
        return uri;
      }
    } catch (IllegalArgumentException invalid) {
      // reported below
    }
    throw new IllegalStateException(what + " names an invalid URL: " + value);
  }

  // Literals and the RFC 6761 names only: no DNS lookup, so 127.example.com is not loopback.
  static boolean isLoopback(String host) {
    if (host == null) {
      return false;
    }
    String name = host.toLowerCase(Locale.ROOT);
    if (name.equals("localhost") || name.endsWith(".localhost")) {
      return true;
    }
    try {
      return InetAddress.ofLiteral(host.replaceAll("^\\[|\\]$", "")).isLoopbackAddress();
    } catch (IllegalArgumentException notALiteral) {
      return false;
    }
  }

  /** The authorization code grant with the PKCE verifier (RFC 7636 §4.5). */
  Session exchangeCode(String code, String verifier, String redirectUri, long now) {
    Map<String, String> form = new LinkedHashMap<>();
    form.put("grant_type", "authorization_code");
    form.put("client_id", CLIENT_ID);
    form.put("code", code);
    form.put("redirect_uri", redirectUri);
    form.put("code_verifier", verifier);
    HttpResponse<String> response = post(discover().tokenUrl(), form);
    JsonNode json = parse(response.body());
    if (response.statusCode() == 200 && !json.path("access_token").asString("").isEmpty()) {
      return buildSession(json, now, "", "");
    }
    String error = json.path("error").asString("HTTP " + response.statusCode());
    String description = json.path("error_description").asString("");
    throw new IllegalStateException(
        "Login failed: " + error + (description.isEmpty() ? "" : " (" + description + ")"));
  }

  /**
   * New tokens for the session's refresh token; empty when the provider refuses it, or when the
   * endpoint now names another provider than the one that issued it.
   */
  Optional<Session> refresh(Session session, long now) {
    if (!issuedHere(session)) {
      return Optional.empty();
    }
    Map<String, String> form = new LinkedHashMap<>();
    form.put("grant_type", "refresh_token");
    form.put("client_id", CLIENT_ID);
    form.put("refresh_token", session.refreshToken());
    HttpResponse<String> response = post(discover().tokenUrl(), form);
    JsonNode json = parse(response.body());
    return response.statusCode() == 200 && !json.path("access_token").asString("").isEmpty()
        ? Optional.of(buildSession(json, now, session.refreshToken(), session.user()))
        : Optional.empty();
  }

  /**
   * The endpoint's session from {@code file}, refreshed and saved again when it is about to expire.
   * Never a browser: {@code deploy} stops before its build instead of prompting.
   */
  Session requireSession(Path file, long now) throws IOException {
    Optional<Session> stored = Session.load(file, endpoint);
    if (stored.isPresent() && stored.get().isValidAt(now)) {
      return stored.get();
    }
    if (stored.isPresent() && !stored.get().refreshToken().isEmpty()) {
      Optional<Session> refreshed = refresh(stored.get(), now);
      if (refreshed.isPresent()) {
        refreshed.get().save(file);
        return refreshed.get();
      }
    }
    throw new IllegalStateException(NOT_LOGGED_IN);
  }

  /** Ends the session at the provider (RFC 7009); the answer is ignored, as RFC 7009 allows. */
  void revoke(Session session) {
    String url = discover().revocationUrl();
    if (url.isEmpty() || session.refreshToken().isEmpty() || !issuedHere(session)) {
      return;
    }
    Map<String, String> form = new LinkedHashMap<>();
    form.put("token", session.refreshToken());
    form.put("token_type_hint", "refresh_token");
    form.put("client_id", CLIENT_ID);
    post(url, form);
  }

  /**
   * {@code preferred_username} from a JWT access token, for display only: the server verifies the
   * token. Empty for an opaque one.
   */
  static String extractUser(String accessToken) {
    String[] parts = accessToken.split("\\.", -1);
    if (parts.length < 2) {
      return "";
    }
    try {
      String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
      return parse(payload).path("preferred_username").asString("");
    } catch (IllegalArgumentException notBase64) {
      return "";
    }
  }

  // Discovery runs again on every command; a refresh token must not follow a changed answer.
  private boolean issuedHere(Session session) {
    return session.issuer().equals(discover().issuer());
  }

  // A provider may keep the refresh token (no rotation) or send an opaque token: keep ours then.
  private Session buildSession(JsonNode json, long now, String refreshToken, String user) {
    String accessToken = json.path("access_token").asString();
    String named = extractUser(accessToken);
    return new Session(
        endpoint,
        discover().issuer(),
        named.isEmpty() ? user : named,
        accessToken,
        json.path("refresh_token").asString(refreshToken),
        now + json.path("expires_in").asLong(300));
  }

  private HttpResponse<String> post(String url, Map<String, String> form) {
    return send(
        client,
        HttpRequest.newBuilder(parseUri(url, "identity provider"))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(encodeForm(form)))
            .build());
  }

  /** {@code application/x-www-form-urlencoded}; also the authorize URL's query. */
  static String encodeForm(Map<String, String> fields) {
    return fields.entrySet().stream()
        .map(e -> encode(e.getKey()) + "=" + encode(e.getValue()))
        .collect(Collectors.joining("&"));
  }

  private JsonNode getJson(String url, String problem) {
    HttpResponse<String> response =
        send(client, HttpRequest.newBuilder(URI.create(url)).GET().build());
    if (response.statusCode() != 200) {
      throw new IllegalStateException(problem + " (HTTP " + response.statusCode() + ")");
    }
    return parse(response.body());
  }

  /** A body that is not JSON reads as missing, so every field lookup falls to its default. */
  static JsonNode parse(String body) {
    try {
      return JSON.readTree(body);
    } catch (JacksonException notJson) {
      return MissingNode.getInstance();
    }
  }

  /** One request; a network failure is one line naming the host, never a stack trace. */
  static HttpResponse<String> send(HttpClient client, HttpRequest request) {
    try {
      return client.send(request, HttpResponse.BodyHandlers.ofString());
    } catch (IOException e) {
      throw new IllegalStateException(
          "cannot reach "
              + request.uri().getAuthority()
              + ": "
              + Objects.requireNonNullElse(e.getMessage(), e.getClass().getSimpleName()));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted");
    }
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }
}
