package com.reportix.aisolate.identity.testing;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.stream.Collectors;
import tools.jackson.databind.json.JsonMapper;

/**
 * Obtains real user tokens from the test Keycloak (password grant is enabled for dev/tests only).
 */
public final class Tokens {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  /** An application user's token from the organisation's realm, as its chat obtains it. */
  public static String forUser(String username, String password) {
    return forUser(Keycloak.usersIssuerUri(), "acme-chat", username, password);
  }

  /** An operator's token from AIsolate's realm, as the dashboard obtains it. */
  public static String forOperator(String username, String password) {
    return forUser(Keycloak.operatorsIssuerUri(), "aisolate-ui", username, password);
  }

  public static String forUser(String issuer, String clientId, String username, String password) {
    String form =
        Map.of(
                "grant_type", "password",
                "client_id", clientId,
                "username", username,
                "password", password,
                "scope", "openid profile email")
            .entrySet()
            .stream()
            .map(
                e ->
                    URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8)
                        + "="
                        + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
            .collect(Collectors.joining("&"));
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(issuer + "/protocol/openid-connect/token"))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(form))
            .build();
    HttpResponse<String> response = Http.send(request);
    if (response.statusCode() != 200) {
      throw new IllegalStateException("token request failed: " + response.body());
    }
    return JSON.readTree(response.body()).get("access_token").asString();
  }

  private Tokens() {}
}
