package com.reportix.aisolate.backend.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.reportix.aisolate.backend.BackendApplication;
import com.reportix.aisolate.backend.support.BackendTestConfig;
import com.reportix.aisolate.identity.testing.Http;
import com.reportix.aisolate.identity.testing.Keycloak;
import com.reportix.aisolate.identity.testing.Tokens;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ContextConfiguration;
import tools.jackson.databind.json.JsonMapper;

/**
 * Who may call what, GitLab's group model: andrew is the admin; in roles-lab jasmine is a developer
 * and eric a user; in roles-other jasmine is only a user; the auditor reads the audit. Bodies are
 * empty, so an allowed call fails its own checks (400, 404) and changes nothing: only the guard's
 * 403 counts.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = BackendApplication.class)
@ContextConfiguration(initializers = BackendTestConfig.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AccessIntegrationTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Map<String, String> TOKENS = new ConcurrentHashMap<>();

  @LocalServerPort int port;

  @BeforeAll
  void createSpaces() {
    for (String space :
        new String[] {
          """
          {"id":"roles-lab","name":"Roles lab","members":[
           {"email":"jasmine@example.com","role":"developer"},
           {"email":"eric@example.com","role":"user"}]}
          """,
          """
          {"id":"roles-other","name":"Roles other","members":[
           {"email":"jasmine@example.com","role":"user"}]}
          """
        }) {
      assertThat(send("andrew", "POST", "/v1/spaces", space).statusCode()).isIn(201, 409);
    }
  }

  @ParameterizedTest(name = "{0} {1} {2}: allowed {3}")
  @CsvSource({
    // Spaces: the list holds what one may open; a space's own parts are the admin's
    "eric,       GET,    /v1/spaces,                          true",
    "auditor,    GET,    /v1/spaces,                          true",
    "andrew, GET,    /v1/spaces/roles-lab,                true",
    "jasmine,    GET,    /v1/spaces/roles-lab,                true",
    "eric,       GET,    /v1/spaces/roles-lab,                false",
    "auditor,    GET,    /v1/spaces/roles-lab,                false",
    "jasmine,    GET,    /v1/spaces/roles-other,              false",
    "jasmine,    GET,    /v1/spaces/roles-lab/users,          true",
    "eric,       GET,    /v1/spaces/roles-lab/users,          false",
    "andrew, POST,   /v1/spaces,                          true",
    "jasmine,    POST,   /v1/spaces,                          false",
    "andrew, PUT,    /v1/spaces/roles-lab,                true",
    "jasmine,    PUT,    /v1/spaces/roles-lab,                false",
    "jasmine,    DELETE, /v1/spaces,                          false",
    // Airlocks and deploys: the space's developers and the admin
    "andrew, POST,   /v1/spaces/roles-lab/airlocks,       true",
    "jasmine,    POST,   /v1/spaces/roles-lab/airlocks,       true",
    "eric,       POST,   /v1/spaces/roles-lab/airlocks,       false",
    "jasmine,    POST,   /v1/spaces/roles-other/airlocks,     false",
    "jasmine,    GET,    /v1/spaces/roles-other/airlocks,     false",
    "jasmine,    PUT,    /v1/spaces/roles-other/airlocks/x,   false",
    "jasmine,    DEPLOY, /v1/spaces/roles-lab/deploy,         true",
    "eric,       DEPLOY, /v1/spaces/roles-lab/deploy,         false",
    "jasmine,    DEPLOY, /v1/spaces/roles-other/deploy,       false",
    // The directory: whoever picks people, the admin and the developers
    "andrew, GET,    /v1/directory/people?q=nor,          true",
    "jasmine,    GET,    /v1/directory/people?q=nor,          true",
    "eric,       GET,    /v1/directory/people?q=nor,          false",
    "auditor,    GET,    /v1/directory/people?q=nor,          false",
    // The audit: the admin and the auditor all, a developer their own spaces
    "auditor,    GET,    /v1/audit/events,                    true",
    "jasmine,    GET,    /v1/audit/events?space=roles-lab,    true",
    "jasmine,    GET,    /v1/audit/events?space=roles-other,  false",
    "eric,       GET,    /v1/audit/events,                    false",
    // Credentials: the admin reveals, after a fresh sign-in
    "andrew,     POST,   /v1/credentials/x/reveal,            true",
    "jasmine,    POST,   /v1/credentials/x/reveal,            false",
    "eric,       POST,   /v1/credentials/x/reveal,            false",
    "auditor,    POST,   /v1/credentials/x/reveal,            false"
  })
  void givenCallerRole_whenEndpointCalled_thenAllowedOrForbidden(
      String caller, String method, String path, boolean allowed) {
    // Arrange: an empty body, or for a deploy an empty multipart
    String body = "{}";

    // Act
    HttpResponse<String> response = send(caller, method, path, body);

    // Assert
    assertThat(response.statusCode() != 403)
        .as("%s %s %s: %s %s", caller, method, path, response.statusCode(), response.body())
        .isEqualTo(allowed);
  }

  @ParameterizedTest(name = "{0} {1} {2}: allowed {3}")
  @CsvSource({
    // The install's LLMs and data sources: every developer reads them, the admin alone writes
    "andrew,    GET,    '',     true",
    "andrew,    GET,    /x,     true",
    "andrew,    POST,   '',     true",
    "andrew,    PUT,    /x,     true",
    "andrew,    DELETE, /x,     true",
    "jasmine,   GET,    '',     true",
    "jasmine,   GET,    /x,     true",
    "jasmine,   POST,   '',     false",
    "jasmine,   PUT,    /x,     false",
    "jasmine,   DELETE, /x,     false",
    // eric asks roles-lab's airlocks and develops no space
    "eric,      GET,    '',     false",
    "eric,      GET,    /x,     false",
    "eric,      POST,   '',     false",
    "eric,      PUT,    /x,     false",
    "eric,      DELETE, /x,     false",
    "anonymous, GET,    '',     false",
    "anonymous, GET,    /x,     false",
    "anonymous, POST,   '',     false",
    "anonymous, PUT,    /x,     false",
    "anonymous, DELETE, /x,     false"
  })
  void givenCallerRole_whenGlobalLlmOrDataSourceEndpointCalled_thenAllowedOrRefused(
      String caller, String method, String id, boolean allowed) {
    // Arrange: x exists in neither list, so an allowed call ends in 400 or 404
    List<String> paths = List.of("/v1/llms" + id, "/v1/data-sources" + id);

    // Act
    List<Integer> statuses =
        paths.stream().map(path -> send(caller, method, path, "{}").statusCode()).toList();

    // Assert
    assertThat(statuses)
        .as("%s %s %s: %s", caller, method, paths, statuses)
        .allMatch(status -> (status != 401 && status != 403) == allowed);
  }

  @Test
  void givenUserOfTheSpace_whenDeploy_then403SayingWhomToAsk() {
    // Arrange: jasmine only asks roles-other's airlocks

    // Act
    HttpResponse<String> response = send("jasmine", "DEPLOY", "/v1/spaces/roles-other/deploy", "");

    // Assert
    assertThat(response.statusCode()).isEqualTo(403);
    assertThat(JSON.readTree(response.body()).path("detail").asString())
        .isEqualTo(
            "You are not a developer of space roles-other: ask its admin to add you as Developer.");
  }

  /** DEPLOY is a POST of the CLI's multipart form, without its parts. */
  private HttpResponse<String> send(String caller, String method, String path, String body) {
    String token =
        caller.equals("anonymous")
            ? null
            : TOKENS.computeIfAbsent(
                caller,
                user -> Tokens.forUser(Keycloak.issuerUri(), "aisolate-ui", user, "password"));
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
    if (method.equals("DEPLOY")) {
      request = Http.multipart(request, "POST", Map.of());
    } else {
      request
          .header("Content-Type", "application/json")
          .method(
              method,
              method.equals("GET") || method.equals("DELETE")
                  ? HttpRequest.BodyPublishers.noBody()
                  : HttpRequest.BodyPublishers.ofString(body));
    }
    return Http.send(Http.bearer(request, token).build());
  }
}
