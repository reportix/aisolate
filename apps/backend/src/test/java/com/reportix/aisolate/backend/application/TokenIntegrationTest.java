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

/**
 * The backend accepts an operator's token from AIsolate's realm and nothing that only resembles
 * one.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = BackendApplication.class)
@ContextConfiguration(initializers = BackendTestConfig.class)
class TokenIntegrationTest {

  @LocalServerPort int port;

  static Stream<Arguments> tokensNotForAIsolate() {
    String developer = Tokens.forOperator("jasmine", "password");
    return Stream.of(
        // A developer's real token from AIsolate's realm, issued to a client without the audience.
        arguments(
            named(
                "issued for another audience",
                Tokens.forUser(Keycloak.operatorsIssuerUri(), "other-app", "jasmine", "password"))),
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
  void givenUsersRealmToken_whenBackend_then401() {
    // Arrange: an application user's real token, audience aisolate, from the organisation's realm
    HttpRequest request =
        HttpRequest.newBuilder(uri("/v1/spaces"))
            .header("Authorization", "Bearer " + Tokens.forUser("eric", "password"))
            .GET()
            .build();

    // Act
    HttpResponse<String> response = Http.send(request);

    // Assert: the backend trusts AIsolate's realm alone
    assertThat(response.statusCode()).isEqualTo(401);
  }

  private URI uri(String path) {
    return URI.create("http://localhost:" + port + path);
  }
}
