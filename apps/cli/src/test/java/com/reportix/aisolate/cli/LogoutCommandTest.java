package com.reportix.aisolate.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

class LogoutCommandTest {

  private static final String ELSEWHERE = "http://elsewhere:8080";

  @TempDir Path tmp;

  private final FakeConsole console = new FakeConsole();
  private IdentityStub stub;
  private Path project;
  private Path home;
  private Path sessionFile;

  @BeforeEach
  void start() throws IOException {
    stub = new IdentityStub();
    project = TestProject.create(tmp.resolve("hr-lab"), "hr-lab", stub.url);
    home = tmp.resolve("home");
    sessionFile = home.resolve(Session.FILE);
  }

  @AfterEach
  void stop() {
    stub.close();
  }

  @Test
  void givenSession_whenLogout_thenRevokedForgottenAndTheOtherEndpointKept() throws IOException {
    // Arrange
    new Session(stub.url, stub.issuer, "jasmine", "at", "rt", 9_999_999_999L).save(sessionFile);
    new Session(ELSEWHERE, stub.issuer, "jasmine", "at", "rt-other", 9_999_999_999L)
        .save(sessionFile);

    // Act
    int exit = logout();

    // Assert
    assertThat(exit).isZero();
    assertThat(stub.revocations)
        .containsExactly(
            Map.of("token", "rt", "token_type_hint", "refresh_token", "client_id", "aisolate-cli"));
    assertThat(Session.load(sessionFile, stub.url)).isEmpty();
    assertThat(Session.load(sessionFile, ELSEWHERE)).isPresent();
    assertThat(console.lines).containsExactly("✔ Logged out of " + stub.url);
  }

  @Test
  void givenNoSession_whenLogout_thenNotLoggedInLineAndNoRequest() {
    // Arrange

    // Act
    int exit = logout();

    // Assert
    assertThat(exit).isZero();
    assertThat(console.lines).containsExactly("⏺ Not logged in to " + stub.url);
    assertThat(stub.revocations).isEmpty();
  }

  @Test
  void givenUnreachableProvider_whenLogout_thenWarnedAndStillForgotten() throws IOException {
    // Arrange
    new Session(stub.url, stub.issuer, "jasmine", "at", "rt", 9_999_999_999L).save(sessionFile);
    stub.close();

    // Act
    int exit = logout();

    // Assert
    assertThat(exit).isZero();
    assertThat(console.lines)
        .satisfiesExactly(
            line ->
                assertThat(line)
                    .startsWith("! the identity provider was not told: cannot reach 127.0.0.1:"),
            line -> assertThat(line).isEqualTo("✔ Logged out of " + stub.url));
    assertThat(Session.load(sessionFile, stub.url)).isEmpty();
  }

  private int logout() {
    return new CommandLine(new LogoutCommand(console, HttpClient.newHttpClient(), home))
        .execute("-p=" + project);
  }
}
