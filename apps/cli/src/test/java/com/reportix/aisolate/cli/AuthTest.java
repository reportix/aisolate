package com.reportix.aisolate.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class AuthTest {

  private static final String REDIRECT = "http://127.0.0.1:5000/callback";

  @TempDir Path tmp;

  private IdentityStub stub;
  private Auth auth;
  private Path file;

  @BeforeEach
  void start() throws Exception {
    stub = new IdentityStub();
    auth = new Auth(HttpClient.newHttpClient(), stub.url);
    file = tmp.resolve(Session.FILE);
  }

  @AfterEach
  void stop() {
    stub.close();
  }

  @Test
  void givenDiscovery_whenDiscover_thenTheProvidersEndpoints() {
    // Arrange

    // Act
    Auth.Endpoints endpoints = auth.discover();

    // Assert
    assertThat(endpoints)
        .isEqualTo(
            new Auth.Endpoints(
                stub.issuer, stub.authorizationUrl, stub.tokenUrl, stub.revocationUrl));
  }

  @Test
  void givenProviderWithoutRotation_whenRefresh_thenTheStoredRefreshTokenKept() {
    // Arrange
    stub.refreshAnswer = "{\"access_token\":\"at3\",\"expires_in\":300}";
    Session expired = new Session(stub.url, stub.issuer, "jasmine", "at", "rt", 900);

    // Act
    Optional<Session> refreshed = auth.refresh(expired, 1000);

    // Assert
    assertThat(refreshed)
        .contains(new Session(stub.url, stub.issuer, "jasmine", "at3", "rt", 1300));
  }

  @Test
  void givenValidSession_whenCurrent_thenTheStoredSessionWithoutARequest() throws IOException {
    // Arrange
    Session stored = new Session(stub.url, stub.issuer, "jasmine", "at", "rt", 2000);
    stored.save(file);

    // Act
    Session current = auth.requireSession(file, 1000);

    // Assert
    assertThat(current).isEqualTo(stored);
    assertThat(stub.forms).isEmpty();
  }

  @Test
  void givenExpiredSession_whenCurrent_thenRefreshedAndSaved() throws IOException {
    // Arrange
    new Session(stub.url, stub.issuer, "jasmine", "at", "rt", 900).save(file);

    // Act
    Session current = auth.requireSession(file, 1000);

    // Assert
    assertThat(current)
        .isEqualTo(new Session(stub.url, stub.issuer, "jasmine", "at2", "rt2", 1300));
    assertThat(Session.load(file, stub.url)).contains(current);
  }

  @ParameterizedTest
  @ValueSource(strings = {"revoked", "no refresh token", "elsewhere"})
  void givenNoUsableSession_whenCurrent_thenNotLoggedIn(String stored) throws IOException {
    // Arrange
    switch (stored) {
      case "revoked" ->
          new Session(stub.url, stub.issuer, "jasmine", "at", "revoked", 900).save(file);
      case "no refresh token" ->
          new Session(stub.url, stub.issuer, "jasmine", "at", "", 900).save(file);
      default ->
          new Session("http://elsewhere:8080", stub.issuer, "jasmine", "at", "rt", 2000).save(file);
    }

    // Act
    Throwable thrown = catchThrowable(() -> auth.requireSession(file, 1000));

    // Assert
    assertThat(thrown).isInstanceOf(IllegalStateException.class).hasMessage(Auth.NOT_LOGGED_IN);
  }

  @Test
  void givenSessionOfAnotherIssuer_whenCurrentOrRevoke_thenItsRefreshTokenIsNeverSent()
      throws IOException {
    // Arrange: signed in at one provider; discovery now names the stub
    Session elsewhere =
        new Session(stub.url, "https://idp.example/realms/r", "jasmine", "at", "rt", 900);
    elsewhere.save(file);

    // Act
    Throwable thrown = catchThrowable(() -> auth.requireSession(file, 1000));
    auth.revoke(elsewhere);

    // Assert
    assertThat(thrown).isInstanceOf(IllegalStateException.class).hasMessage(Auth.NOT_LOGGED_IN);
    assertThat(stub.forms).isEmpty();
    assertThat(stub.revocations).isEmpty();
  }

  @Test
  void givenNoRevocationEndpoint_whenRevoke_thenNoRequest() {
    // Arrange
    stub.serve(
        IdentityStub.OPENID_PATH,
        "{\"issuer\":\""
            + stub.issuer
            + "\",\"authorization_endpoint\":\""
            + stub.authorizationUrl
            + "\",\"token_endpoint\":\""
            + stub.tokenUrl
            + "\"}");

    // Act
    auth.revoke(new Session(stub.url, stub.issuer, "jasmine", "at", "rt", 2000));

    // Assert
    assertThat(stub.revocations).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(strings = {"at", "a.!!!.c", "e30.e30.sig"})
  void givenOpaqueOrNamelessToken_whenUserOf_thenEmpty(String token) {
    // Arrange

    // Act
    String user = Auth.extractUser(token);

    // Assert
    assertThat(user).isEmpty();
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      textBlock =
          """
          RESOURCE | {}                                         | names no authorization server
          OPENID   | {"issuer":"x"}                             | names no authorization endpoint
          OPENID   | {"authorization_endpoint":"https://a/auth"} | names no token endpoint
          RESOURCE | not json                                   | names no authorization server
          """)
  void givenDiscoveryMissingAField_whenDiscover_thenIllegalStateInOneLine(
      String document, String json, String problem) {
    // Arrange
    stub.serve(
        document.equals("RESOURCE") ? IdentityStub.RESOURCE_PATH : IdentityStub.OPENID_PATH, json);

    // Act
    Throwable thrown = catchThrowable(() -> auth.discover());

    // Assert
    assertThat(thrown).isInstanceOf(IllegalStateException.class).hasMessageContaining(problem);
    assertThat(thrown.getMessage()).doesNotContain("\n");
  }

  @ParameterizedTest
  @ValueSource(strings = {"file:///C:/Windows/System32/calc.exe#", "-aCalculator"})
  void givenNonHttpAuthorizationEndpoint_whenDiscover_thenRefusedInOneLine(String url) {
    // Arrange: the URL would reach the OS opener
    stub.serve(
        IdentityStub.OPENID_PATH,
        "{\"issuer\":\""
            + stub.issuer
            + "\",\"authorization_endpoint\":\""
            + url
            + "\",\"token_endpoint\":\""
            + stub.tokenUrl
            + "\"}");

    // Act
    Throwable thrown = catchThrowable(() -> auth.discover());

    // Assert
    assertThat(thrown)
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("authorization endpoint names an invalid URL: " + url);
  }

  @Test
  void givenIssuerWithTrailingSlash_whenDiscover_thenOpenIdConfigurationFoundWithoutDoubleSlash() {
    // Arrange
    stub.serve(
        IdentityStub.RESOURCE_PATH, "{\"authorization_servers\":[\"" + stub.issuer + "/\"]}");

    // Act
    Auth.Endpoints endpoints = auth.discover();

    // Assert
    assertThat(endpoints.issuer()).isEqualTo(stub.issuer);
    assertThat(endpoints.tokenUrl()).isEqualTo(stub.tokenUrl);
  }

  @ParameterizedTest
  @CsvSource({
    "https://aisolate.example, http://keycloak.example/realms/r",
    // A DNS name that only starts like loopback
    "https://aisolate.example, http://127.example.com/realms/r",
    "HTTPS://aisolate.example, http://keycloak.example/realms/r",
    // A port-forward to a remote backend
    "http://localhost:8080, http://keycloak.example/realms/r"
  })
  void givenAnyEndpoint_whenProviderIsPlainHttpElsewhere_thenRefusedInOneLine(
      String endpoint, String url) {
    // Arrange

    // Act
    Throwable thrown = catchThrowable(() -> Auth.refuseDowngrade(endpoint, url));

    // Assert
    assertThat(thrown)
        .isInstanceOf(IllegalStateException.class)
        .hasMessage(
            "refusing "
                + url
                + ", named by AIsolate at "
                + endpoint
                + ": the identity provider must use https (plain http is for localhost only)");
  }

  @ParameterizedTest
  @CsvSource({
    "https://aisolate.example, https://keycloak.example/realms/r",
    "https://aisolate.example, http://localhost:8180/realms/r",
    "https://aisolate.example, http://127.0.0.1:8180/realms/r",
    "https://aisolate.example, http://[::1]:8180/realms/r",
    "http://localhost:8080, http://localhost:8180/realms/r",
    "http://aisolate.localhost, http://keycloak.localhost/realms/r"
  })
  void givenHttpsOrLoopbackProvider_whenRefuseDowngrade_thenAllowed(String endpoint, String url) {
    // Arrange

    // Act
    Throwable thrown = catchThrowable(() -> Auth.refuseDowngrade(endpoint, url));

    // Assert
    assertThat(thrown).isNull();
  }

  @Test
  void givenMalformedIssuer_whenRefuseDowngrade_thenOneLineNamingTheValue() {
    // Arrange
    String issuer = "http://bad host/realms/r";

    // Act
    Throwable thrown = catchThrowable(() -> Auth.refuseDowngrade("https://l.example", issuer));

    // Assert
    assertThat(thrown)
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("identity provider names an invalid URL: " + issuer);
  }

  @Test
  void givenNoDiscovery_whenDiscover_thenIllegalStateNamingTheEndpoint() {
    // Arrange
    stub.server.removeContext(IdentityStub.RESOURCE_PATH);

    // Act
    Throwable thrown = catchThrowable(() -> auth.discover());

    // Assert
    assertThat(thrown)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(stub.url)
        .hasMessageContaining("404");
  }
}
