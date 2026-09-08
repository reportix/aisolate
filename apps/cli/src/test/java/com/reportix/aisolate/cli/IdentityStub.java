package com.reportix.aisolate.cli;

import static java.util.stream.Collectors.toMap;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * An AIsolate endpoint on 127.0.0.1 that names its issuer (RFC 9728), the issuer's OpenID
 * configuration, and token and revocation endpoints that know one authorization code and one
 * refresh token, as Keycloak answers. Every token and revocation form is recorded.
 */
final class IdentityStub implements AutoCloseable {

  static final String CODE = "the-code";
  static final Map<String, String> REFRESH_GRANT =
      Map.of("grant_type", "refresh_token", "refresh_token", "rt", "client_id", "aisolate-cli");
  static final String RESOURCE_PATH = "/.well-known/oauth-protected-resource";
  static final String OPENID_PATH = "/realms/test/.well-known/openid-configuration";

  final HttpServer server;
  final String url;
  final String issuer;
  final String authorizationUrl;
  final String tokenUrl;
  final String revocationUrl;
  final List<Map<String, String>> forms = new CopyOnWriteArrayList<>();
  final List<Map<String, String>> revocations = new CopyOnWriteArrayList<>();

  /**
   * What a good refresh answers; a test drops the refresh token to play a provider without
   * rotation.
   */
  volatile String refreshAnswer =
      "{\"access_token\":\"at2\",\"refresh_token\":\"rt2\",\"expires_in\":300}";

  IdentityStub() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    url = "http://127.0.0.1:" + server.getAddress().getPort();
    issuer = url + "/realms/test";
    authorizationUrl = issuer + "/protocol/openid-connect/auth";
    tokenUrl = issuer + "/protocol/openid-connect/token";
    revocationUrl = issuer + "/protocol/openid-connect/revoke";
    server.createContext(
        RESOURCE_PATH,
        exchange ->
            respond(
                exchange,
                200,
                "{\"resource\":\"" + url + "\",\"authorization_servers\":[\"" + issuer + "\"]}"));
    server.createContext(
        OPENID_PATH,
        exchange ->
            respond(
                exchange,
                200,
                "{\"issuer\":\""
                    + issuer
                    + "\",\"authorization_endpoint\":\""
                    + authorizationUrl
                    + "\",\"token_endpoint\":\""
                    + tokenUrl
                    + "\",\"revocation_endpoint\":\""
                    + revocationUrl
                    + "\"}"));
    server.createContext("/realms/test/protocol/openid-connect/token", this::token);
    server.createContext(
        "/realms/test/protocol/openid-connect/revoke",
        exchange -> {
          revocations.add(form(body(exchange)));
          respond(exchange, 200, "{}");
        });
    server.start();
  }

  /**
   * An access token shaped like Keycloak's: a payload naming the user; header and signature unused.
   */
  static String jwt(String user) {
    String payload = "{\"preferred_username\":\"" + user + "\"}";
    return "e30."
        + Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(payload.getBytes(StandardCharsets.UTF_8))
        + ".sig";
  }

  private void token(HttpExchange exchange) throws IOException {
    Map<String, String> form = form(body(exchange));
    forms.add(form);
    if ("authorization_code".equals(form.get("grant_type"))
        && CODE.equals(form.get("code"))
        && Auth.CLIENT_ID.equals(form.get("client_id"))
        && form.containsKey("code_verifier")
        && form.containsKey("redirect_uri")) {
      respond(
          exchange,
          200,
          "{\"access_token\":\""
              + jwt("jasmine")
              + "\",\"refresh_token\":\"rt\",\"expires_in\":300}");
    } else if (form.equals(REFRESH_GRANT)) {
      respond(exchange, 200, refreshAnswer);
    } else {
      respond(exchange, 401, "{\"error\":\"invalid_grant\"}");
    }
  }

  /** Replaces what {@code path} answers, e.g. a discovery document missing a field. */
  void serve(String path, String json) {
    server.removeContext(path);
    server.createContext(path, exchange -> respond(exchange, 200, json));
  }

  static void respond(HttpExchange exchange, int status, String json) throws IOException {
    byte[] body = json.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, body.length);
    try (var out = exchange.getResponseBody()) {
      out.write(body);
    }
  }

  private static String body(HttpExchange exchange) throws IOException {
    return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
  }

  private static Map<String, String> form(String body) {
    return Arrays.stream(body.split("&"))
        .map(pair -> pair.split("=", 2))
        .collect(toMap(pair -> decode(pair[0]), pair -> decode(pair[1])));
  }

  private static String decode(String value) {
    return URLDecoder.decode(value, StandardCharsets.UTF_8);
  }

  @Override
  public void close() {
    server.stop(0);
  }
}
