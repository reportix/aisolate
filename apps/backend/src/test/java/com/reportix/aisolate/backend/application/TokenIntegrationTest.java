package com.reportix.aisolate.backend.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Named.named;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import com.reportix.aisolate.backend.BackendApplication;
import com.reportix.aisolate.backend.support.BackendTestConfig;
import com.reportix.aisolate.identity.testing.Http;
import com.reportix.aisolate.identity.testing.Keycloak;
import com.reportix.aisolate.identity.testing.Tokens;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ContextConfiguration;

/** The backend accepts a developer's token for AIsolate and nothing that only resembles one. */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = BackendApplication.class)
@ContextConfiguration(initializers = BackendTestConfig.class)
class TokenIntegrationTest {

  @LocalServerPort int port;

  static Stream<Arguments> tokensNotForAIsolate() {
    String developer = Tokens.forUser(Keycloak.issuerUri(), "aisolate-ui", "jasmine", "password");
    return Stream.of(
        // A developer's real token, issued to another client of the organisation's IdP.
        arguments(
            named(
                "issued for another audience",
                Tokens.forUser(Keycloak.issuerUri(), "other-app", "jasmine", "password"))),
        // Every claim is right, only the signature is not the IdP's.
        arguments(
            named(
                "with a signature the IdP never made",
                developer.substring(0, developer.lastIndexOf('.') + 1) + "AAAA")));
  }

  @ParameterizedTest
  @MethodSource("tokensNotForAIsolate")
  void givenTokenNotForAIsolate_whenListingSpaces_then401(String token) {
    // Arrange
    HttpRequest request =
        HttpRequest.newBuilder(uri("/v1/spaces"))
            .header("Authorization", "Bearer " + token)
            .GET()
            .build();

    // Act
    HttpResponse<String> response = Http.send(request);

    // Assert
    assertThat(response.statusCode()).isEqualTo(401);
  }

  @Test
  void givenDefaultSettings_whenTheOpenApiDocumentIsAskedWithoutAToken_thenNotServed() {
    // Arrange
    HttpRequest request = HttpRequest.newBuilder(uri("/v3/api-docs")).GET().build();

    // Act
    HttpResponse<String> response = Http.send(request);

    // Assert: the unknown path falls through to the token check
    assertThat(response.statusCode()).isEqualTo(401);
    assertThat(response.body()).doesNotContain("openapi");
  }

  private URI uri(String path) {
    return URI.create("http://localhost:" + port + path);
  }
}
