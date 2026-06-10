package com.reportix.aisolate.identity;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;

/**
 * The AIsolate UI runs on another origin than the API, so every path its browser calls needs a CORS
 * entry: the control API to work at all, the health probe to tell "back" from "still down".
 */
class SecurityConfigCorsTest {

  private static final IdentityProperties PROPERTIES =
      new IdentityProperties(
          "http://localhost:8080",
          "realm_access.roles",
          new IdentityProperties.Cors(List.of("http://localhost:3001")));

  private CorsConfiguration corsFor(String path) {
    CorsConfigurationSource source = new SecurityConfig().corsConfigurationSource(PROPERTIES);
    MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
    return source.getCorsConfiguration((HttpServletRequest) request);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "/v1/spaces",
        "/v1/airlocks",
        "/v1/spaces/acme/airlocks/hr/chat",
        "/readyz",
        "/.well-known/oauth-protected-resource"
      })
  void givenABrowserPath_whenResolvingCors_thenTheUiOriginIsAllowed(String path) {
    // Arrange / Act
    CorsConfiguration cors = corsFor(path);

    // Assert
    assertThat(cors).as("CORS for %s", path).isNotNull();
    assertThat(cors.getAllowedOriginPatterns()).containsExactly("http://localhost:3001");
  }

  @Test
  void givenTheUisSave_whenItsPreflightIsChecked_thenEveryHeaderItSendsIsAllowed() {
    // Arrange
    CorsConfiguration cors = corsFor("/v1/spaces/acme");
    List<String> sent = List.of("authorization", "content-type", "accept", "if-match");

    // Act
    List<String> allowed = cors.checkHeaders(sent);

    // Assert: a header left out fails the preflight, and the save with it; unexposed, the
    // "saved, not applied" notice could not read the answer's header
    assertThat(allowed).containsExactlyElementsOf(sent);
    assertThat(cors.checkHttpMethod(HttpMethod.PUT)).contains(HttpMethod.PUT);
    assertThat(cors.getExposedHeaders()).contains("AIsolate-Cluster");
  }

  @Test
  void givenAPathNoBrowserCalls_whenResolvingCors_thenNoneIsConfigured() {
    // Arrange / Act
    CorsConfiguration cors = corsFor("/actuator/metrics");

    // Assert
    assertThat(cors).isNull();
  }
}
