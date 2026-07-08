package com.reportix.aisolate.backend.infrastructure.directory;

import com.reportix.aisolate.backend.domain.directory.Directory;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The directory as Keycloak's admin API serves it, through a service account's client credentials
 * (OAuth 2 client credentials grant). Reads users only: the service account holds
 * realm-management's {@code view-users} and {@code query-users}, nothing that writes. Every failure
 * is the same plain {@link Directory.UnavailableException}; the log names the call and its status,
 * never the secret.
 */
@Component
@ConditionalOnExpression(
    "T(org.springframework.util.StringUtils).hasText('${aisolate.directory.url:}')")
@Slf4j
public class KeycloakDirectory implements Directory {

  /** A token is renewed this long before Keycloak says it expires. */
  private static final long TOKEN_MARGIN_SECONDS = 30;

  private final DirectoryProperties properties;
  private final JsonMapper json;
  private final HttpClient http;
  private final String base;

  private String token;
  private Instant tokenExpires = Instant.MIN;

  public KeycloakDirectory(DirectoryProperties properties, JsonMapper json) {
    if (Stream.of(properties.realm(), properties.clientId(), properties.clientSecret())
        .anyMatch(s -> s == null || s.isBlank())) {
      throw new IllegalStateException(
          "aisolate.directory.realm, client-id and client-secret are required with"
              + " aisolate.directory.url");
    }
    this.properties = properties;
    this.json = json;
    this.http = HttpClient.newBuilder().connectTimeout(properties.timeout()).build();
    this.base = properties.url().replaceAll("/+$", "");
  }

  @Override
  public List<Person> search(String text) {
    return users("search=" + encode(text) + "&max=" + MAX_RESULTS).limit(MAX_RESULTS).toList();
  }

  @Override
  public Optional<Person> findByEmail(String email) {
    return users("email=" + encode(email) + "&exact=true&max=2")
        .filter(person -> person.email().equalsIgnoreCase(email))
        .findFirst();
  }

  /** Enabled users with an e-mail: the others cannot sign in to be admitted. */
  private Stream<Person> users(String query) {
    String uri = base + "/admin/realms/" + encode(properties.realm()) + "/users?";
    JsonNode users = adminGet(uri + "briefRepresentation=true&" + query);
    return users.valueStream().map(KeycloakDirectory::person).flatMap(Optional::stream);
  }

  private static Optional<Person> person(JsonNode user) {
    String email = user.path("email").asString("");
    if (email.isBlank() || !user.path("enabled").asBoolean(true)) {
      return Optional.empty();
    }
    String name =
        (user.path("firstName").asString("") + " " + user.path("lastName").asString("")).strip();
    if (name.isEmpty()) {
      name = user.path("name").asString("").strip();
    }
    return Optional.of(
        new Person(name.isEmpty() ? null : name, user.path("username").asString(""), email));
  }

  /** One retry with a fresh token: Keycloak may have restarted, or revoked the cached one. */
  private JsonNode adminGet(String uri) {
    for (int attempt = 1; ; attempt++) {
      HttpResponse<String> response =
          send(
              HttpRequest.newBuilder(URI.create(uri))
                  .timeout(properties.timeout())
                  .header("Authorization", "Bearer " + token())
                  .header("Accept", "application/json")
                  .GET()
                  .build(),
              "the users query");
      if (response.statusCode() == 401 && attempt == 1) {
        forgetToken();
        continue;
      }
      return read(response, "the users query");
    }
  }

  private synchronized String token() {
    if (token != null && Instant.now().isBefore(tokenExpires)) {
      return token;
    }
    String form =
        "grant_type=client_credentials&client_id="
            + encode(properties.clientId())
            + "&client_secret="
            + encode(properties.clientSecret());
    JsonNode body =
        read(
            send(
                HttpRequest.newBuilder(
                        URI.create(
                            base
                                + "/realms/"
                                + encode(properties.realm())
                                + "/protocol/openid-connect/token"))
                    .timeout(properties.timeout())
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(form))
                    .build(),
                "the token request"),
            "the token request");
    String issued = body.path("access_token").asString("");
    if (issued.isEmpty()) {
      log.warn("directory: the token request answered without an access_token");
      throw new UnavailableException();
    }
    token = issued;
    tokenExpires =
        Instant.now().plusSeconds(body.path("expires_in").asLong(60) - TOKEN_MARGIN_SECONDS);
    return token;
  }

  private synchronized void forgetToken() {
    token = null;
  }

  private HttpResponse<String> send(HttpRequest request, String what) {
    try {
      return http.send(request, HttpResponse.BodyHandlers.ofString());
    } catch (IOException e) {
      // The exception names the host and the failure (a timeout, a refused connection), no body.
      log.warn("directory: {} failed: {}", what, e.toString());
      throw new UnavailableException();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new UnavailableException();
    }
  }

  /** A 200's JSON; any other status is logged by number alone, its body may echo the request. */
  private JsonNode read(HttpResponse<String> response, String what) {
    if (response.statusCode() != 200) {
      log.warn("directory: {} answered HTTP {}", what, response.statusCode());
      throw new UnavailableException();
    }
    try {
      return json.readTree(response.body());
    } catch (JacksonException e) {
      log.warn("directory: {} answered no JSON", what);
      throw new UnavailableException();
    }
  }

  /** %20 for a space, which every server reads as one; a + some read as a plus. */
  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
  }
}
